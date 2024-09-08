/*
 * Copyright 2013-2023 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.cloud.openfeign.support;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import feign.Contract;
import feign.Feign;
import feign.MethodMetadata;
import feign.Param;
import feign.QueryMap;
import feign.Request;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.springframework.cloud.openfeign.AnnotatedParameterProcessor;
import org.springframework.cloud.openfeign.CollectionFormat;
import org.springframework.cloud.openfeign.SpringQueryMap;
import org.springframework.cloud.openfeign.annotation.CookieValueParameterProcessor;
import org.springframework.cloud.openfeign.annotation.MatrixVariableParameterProcessor;
import org.springframework.cloud.openfeign.annotation.PathVariableParameterProcessor;
import org.springframework.cloud.openfeign.annotation.QueryMapParameterProcessor;
import org.springframework.cloud.openfeign.annotation.RequestHeaderParameterProcessor;
import org.springframework.cloud.openfeign.annotation.RequestParamParameterProcessor;
import org.springframework.cloud.openfeign.annotation.RequestPartParameterProcessor;
import org.springframework.cloud.openfeign.encoding.HttpEncoding;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.ResourceLoaderAware;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.MethodParameter;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.core.ResolvableType;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.core.convert.ConversionService;
import org.springframework.core.convert.TypeDescriptor;
import org.springframework.core.convert.support.DefaultConversionService;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.ResourceLoader;
import org.springframework.data.domain.Pageable;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;

import static feign.Util.checkState;
import static feign.Util.emptyToNull;
import static org.springframework.cloud.openfeign.support.FeignUtils.addTemplateParameter;
import static org.springframework.core.annotation.AnnotatedElementUtils.findMergedAnnotation;

/**
 * @author Spencer Gibb
 * @author Abhijit Sarkar
 * @author Halvdan Hoem Grelland
 * @author Aram Peres
 * @author Olga Maciaszek-Sharma
 * @author Aaron Whiteside
 * @author Artyom Romanenko
 * @author Darren Foong
 * @author Ram Anaswara
 * @author Sam Kruglov
 */
public class SpringMvcContract extends Contract.BaseContract implements ResourceLoaderAware {

	private static final Log LOG = LogFactory.getLog(SpringMvcContract.class);

	private static final String ACCEPT = "Accept";

	private static final String CONTENT_TYPE = "Content-Type";

	private static final TypeDescriptor STRING_TYPE_DESCRIPTOR = TypeDescriptor.valueOf(String.class);

	private static final TypeDescriptor ITERABLE_TYPE_DESCRIPTOR = TypeDescriptor.valueOf(Iterable.class);

	private static final ParameterNameDiscoverer PARAMETER_NAME_DISCOVERER = new DefaultParameterNameDiscoverer();

	private final Map<Class<? extends Annotation>, AnnotatedParameterProcessor> annotatedArgumentProcessors;

	private final Map<String, Method> processedMethods = new HashMap<>();

	private final ConversionService conversionService;

	private final ConvertingExpanderFactory convertingExpanderFactory;

	private ResourceLoader resourceLoader = new DefaultResourceLoader();

	private final boolean decodeSlash;

	public SpringMvcContract() {
		this(Collections.emptyList());
	}

	public SpringMvcContract(List<AnnotatedParameterProcessor> annotatedParameterProcessors) {
		this(annotatedParameterProcessors, new DefaultConversionService());
	}

	public SpringMvcContract(List<AnnotatedParameterProcessor> annotatedParameterProcessors,
			ConversionService conversionService) {
		this(annotatedParameterProcessors, conversionService, true);
	}

	public SpringMvcContract(List<AnnotatedParameterProcessor> annotatedParameterProcessors,
			ConversionService conversionService, boolean decodeSlash) {
		Assert.notNull(annotatedParameterProcessors, "Parameter processors can not be null.");
		Assert.notNull(conversionService, "ConversionService can not be null.");

		List<AnnotatedParameterProcessor> processors = getDefaultAnnotatedArgumentsProcessors();
		processors.addAll(annotatedParameterProcessors);

		annotatedArgumentProcessors = toAnnotatedArgumentProcessorMap(processors);
		this.conversionService = conversionService;
		convertingExpanderFactory = new ConvertingExpanderFactory(conversionService);
		this.decodeSlash = decodeSlash;
	}

	private static TypeDescriptor createTypeDescriptor(Method method, int paramIndex) {
		Parameter parameter = method.getParameters()[paramIndex];
		MethodParameter methodParameter = MethodParameter.forParameter(parameter);
		TypeDescriptor typeDescriptor = new TypeDescriptor(methodParameter);

		// Feign applies the Param.Expander to each element of an Iterable, so in those
		// cases we need to provide a TypeDescriptor of the element.
		if (typeDescriptor.isAssignableTo(ITERABLE_TYPE_DESCRIPTOR)) {
			TypeDescriptor elementTypeDescriptor = getElementTypeDescriptor(typeDescriptor);

			checkState(elementTypeDescriptor != null,
					"Could not resolve element type of Iterable type %s. Not declared?", typeDescriptor);

			typeDescriptor = elementTypeDescriptor;
		}
		return typeDescriptor;
	}

	private static TypeDescriptor getElementTypeDescriptor(TypeDescriptor typeDescriptor) {
		TypeDescriptor elementTypeDescriptor = typeDescriptor.getElementTypeDescriptor();
		// that means it's not a collection, but it is iterable, gh-135
		if (elementTypeDescriptor == null && Iterable.class.isAssignableFrom(typeDescriptor.getType())) {
			ResolvableType type = typeDescriptor.getResolvableType().as(Iterable.class).getGeneric(0);
			if (type.resolve() == null) {
				return null;
			}
			return new TypeDescriptor(type, null, typeDescriptor.getAnnotations());
		}
		return elementTypeDescriptor;
	}

	@Override
	public void setResourceLoader(ResourceLoader resourceLoader) {
		this.resourceLoader = resourceLoader;
	}

	@Override
	protected void processAnnotationOnClass(MethodMetadata data, Class<?> clz) {
		// todo @RequestMapping annotation is not allowed on @FeignClient interfaces.
		RequestMapping classAnnotation = findMergedAnnotation(clz, RequestMapping.class);
		if (classAnnotation != null) {
			LOG.error("Cannot process class: " + clz.getName()
					+ ". @RequestMapping annotation is not allowed on @FeignClient interfaces.");
			throw new IllegalArgumentException("@RequestMapping annotation not allowed on @FeignClient interfaces");
		}
		CollectionFormat collectionFormat = findMergedAnnotation(clz, CollectionFormat.class);
		if (collectionFormat != null) {
			data.template().collectionFormat(collectionFormat.value());
		}
	}

	@Override
	public MethodMetadata parseAndValidateMetadata(Class<?> targetType, Method method) {
		// 设置处理中的方法
		processedMethods.put(Feign.configKey(targetType, method), method);
		return super.parseAndValidateMetadata(targetType, method);
	}

	@Override
	protected void processAnnotationOnMethod(MethodMetadata data, Annotation methodAnnotation, Method method) {
		// CollectionFormat 针对于集合数据格式化方式
		if (CollectionFormat.class.isInstance(methodAnnotation)) {
			CollectionFormat collectionFormat = findMergedAnnotation(method, CollectionFormat.class);
			data.template().collectionFormat(collectionFormat.value());
		}

		if (!RequestMapping.class.isInstance(methodAnnotation)
				&& !methodAnnotation.annotationType().isAnnotationPresent(RequestMapping.class)) {
			return;
		}

		// 获取注解
		RequestMapping methodMapping = findMergedAnnotation(method, RequestMapping.class);
		// HTTP Method GET / POST / PUT / DELETE
		RequestMethod[] methods = methodMapping.method();
		if (methods.length == 0) {
			methods = new RequestMethod[] { RequestMethod.GET };
		}
		// 校验只能添加一个 HTTP Method
		checkOne(method, methods, "method");
		// 设置 HTTP Method
		data.template().method(Request.HttpMethod.valueOf(methods[0].name()));

		// 校验 path 只能最多有一个
		checkAtMostOne(method, methodMapping.value(), "value");
		if (methodMapping.value().length > 0) {
			String pathValue = emptyToNull(methodMapping.value()[0]);
			if (pathValue != null) {
				// 解析真实 path  如果是${}取${}对应的真实值,非${}返回原值
				pathValue = resolve(pathValue);
				// Append path from @RequestMapping if value is present on method
				if (!pathValue.startsWith("/") && !data.template().path().endsWith("/")) {
					// 补齐 "/" 分割符
					pathValue = "/" + pathValue;
				}
				data.template().uri(pathValue, true);
				if (data.template().decodeSlash() != decodeSlash) {
					data.template().decodeSlash(decodeSlash);
				}
			}
		}

		// produces 期望相应报文的格式  Accept
		parseProduces(data, method, methodMapping);

		// 仅接受 consumes 配置的请求报文格式 Content-Type
		parseConsumes(data, method, methodMapping);

		// headers 见名知意设置请求头
		parseHeaders(data, method, methodMapping);

		// todo 目前不知道什么用暂时先不管
		data.indexToExpander(new LinkedHashMap<>());
	}

	private String resolve(String value) {
		if (StringUtils.hasText(value) && resourceLoader instanceof ConfigurableApplicationContext) {
			return ((ConfigurableApplicationContext) resourceLoader).getEnvironment().resolvePlaceholders(value);
		}
		return value;
	}

	private void checkAtMostOne(Method method, Object[] values, String fieldName) {
		checkState(values != null && (values.length == 0 || values.length == 1),
				"Method %s can only contain at most 1 %s field. Found: %s", method.getName(), fieldName,
				values == null ? null : Arrays.asList(values));
	}

	private void checkOne(Method method, Object[] values, String fieldName) {
		checkState(values != null && values.length == 1, "Method %s can only contain 1 %s field. Found: %s",
				method.getName(), fieldName, values == null ? null : Arrays.asList(values));
	}

	@Override
	protected boolean processAnnotationsOnParameter(MethodMetadata data, Annotation[] annotations, int paramIndex) {
		boolean isHttpAnnotation = false;

		try {
			// 当前参数的类型是否是Pageable.class或者是Pageable.class的子类
			if (Pageable.class.isAssignableFrom(data.method().getParameterTypes()[paramIndex])) {
				// do not set a Pageable as QueryMap if there's an actual QueryMap param
				// 如果存在实际的 QueryMap 参数，请不要将 Pageable 设置为 QueryMap
				// present
				if (!queryMapParamPresent(data)) {
					data.queryMapIndex(paramIndex);
					return false;
				}
			}
		}
		catch (NoClassDefFoundError ignored) {
			// Do nothing; added to avoid exceptions if optional dependency not present
		}

		AnnotatedParameterProcessor.AnnotatedParameterContext context = new SimpleAnnotatedParameterContext(data,
				paramIndex);
		// todo 此处的 Method 与 data.method() 有什么区别 ? 好像没有
 		Method method = processedMethods.get(data.configKey());
		 // 循环处理当前参数上的每一个注解
		for (Annotation parameterAnnotation : annotations) {
			// 获取对应的注解参数处理器
			AnnotatedParameterProcessor processor = annotatedArgumentProcessors
					.get(parameterAnnotation.annotationType());
			if (processor != null) {
				Annotation processParameterAnnotation;
				// synthesize, handling @AliasFor, while falling back to parameter name on
				// missing String #value():
				// 如果注解上的value未写参数名就改成参数名
				processParameterAnnotation = synthesizeWithMethodParameterNameAsFallbackValue(parameterAnnotation,
						method, paramIndex);
				isHttpAnnotation |= processor.processArgument(context, processParameterAnnotation, method);
			}
		}

		if (!isMultipartFormData(data) && isHttpAnnotation && data.indexToExpander().get(paramIndex) == null) {
			TypeDescriptor typeDescriptor = createTypeDescriptor(method, paramIndex);
			if (conversionService.canConvert(typeDescriptor, STRING_TYPE_DESCRIPTOR)) {
				Param.Expander expander = convertingExpanderFactory.getExpander(typeDescriptor);
				if (expander != null) {
					data.indexToExpander().put(paramIndex, expander);
				}
			}
		}
		return isHttpAnnotation;
	}

	/**
	 * 方法是否存在 QueryMap 参数
	 * @param data 方法原数据
	 * @return true 存在
	 */
	private boolean queryMapParamPresent(MethodMetadata data) {
		Annotation[][] paramsAnnotations = data.method().getParameterAnnotations();
		for (int i = 0; i < paramsAnnotations.length; i++) {
			Annotation[] paramAnnotations = paramsAnnotations[i];
			Class<?> parameterType = data.method().getParameterTypes()[i];
			if (Arrays.stream(paramAnnotations).anyMatch(
					annotation -> Map.class.isAssignableFrom(parameterType) && annotation instanceof RequestParam
							|| annotation instanceof SpringQueryMap || annotation instanceof QueryMap)) {
				return true;
			}
		}
		return false;
	}

	private void parseProduces(MethodMetadata md, Method method, RequestMapping annotation) {
		String[] serverProduces = annotation.produces();
		String clientAccepts = serverProduces.length == 0 ? null : emptyToNull(serverProduces[0]);
		if (clientAccepts != null) {
			md.template().header(ACCEPT, clientAccepts);
		}
	}

	private void parseConsumes(MethodMetadata md, Method method, RequestMapping annotation) {
		String[] serverConsumes = annotation.consumes();
		String clientProduces = serverConsumes.length == 0 ? null : emptyToNull(serverConsumes[0]);
		if (clientProduces != null) {
			md.template().header(CONTENT_TYPE, clientProduces);
		}
	}

	private void parseHeaders(MethodMetadata md, Method method, RequestMapping annotation) {
		// TODO: only supports one header value per key
		if (annotation.headers() != null && annotation.headers().length > 0) {
			for (String header : annotation.headers()) {
				int index = header.indexOf('=');
				if (!header.contains("!=") && index >= 0) {
					md.template().header(resolve(header.substring(0, index)),
							resolve(header.substring(index + 1).trim()));
				}
			}
		}
	}

	private Map<Class<? extends Annotation>, AnnotatedParameterProcessor> toAnnotatedArgumentProcessorMap(
			List<AnnotatedParameterProcessor> processors) {
		Map<Class<? extends Annotation>, AnnotatedParameterProcessor> result = new HashMap<>();
		for (AnnotatedParameterProcessor processor : processors) {
			result.put(processor.getAnnotationType(), processor);
		}
		return result;
	}

	private List<AnnotatedParameterProcessor> getDefaultAnnotatedArgumentsProcessors() {

		List<AnnotatedParameterProcessor> annotatedArgumentResolvers = new ArrayList<>();

		annotatedArgumentResolvers.add(new MatrixVariableParameterProcessor());
		annotatedArgumentResolvers.add(new PathVariableParameterProcessor());
		annotatedArgumentResolvers.add(new RequestParamParameterProcessor());
		annotatedArgumentResolvers.add(new RequestHeaderParameterProcessor());
		annotatedArgumentResolvers.add(new QueryMapParameterProcessor());
		annotatedArgumentResolvers.add(new RequestPartParameterProcessor());
		annotatedArgumentResolvers.add(new CookieValueParameterProcessor());

		return annotatedArgumentResolvers;
	}

	/**
	 * 从方法参数注解中提取属性值。
	 * 检查注解的 value 属性是否使用了默认值。
	 * 如果使用了默认值，并且适合用方法参数名来替代，则将参数名作为 value 的值进行回填。
	 * 最后，生成并返回一个带有修改属性的合成注解。
	 *
	 */
	private Annotation synthesizeWithMethodParameterNameAsFallbackValue(Annotation parameterAnnotation, Method method,
			int parameterIndex) {
		// 注解属性
		Map<String, Object> annotationAttributes = AnnotationUtils.getAnnotationAttributes(parameterAnnotation);
		// 获取注解默认值
		Object defaultValue = AnnotationUtils.getDefaultValue(parameterAnnotation);
		// 检查默认值是否是 String 类型，并且是否等于注解属性 value 的值。换句话说，如果 value 属性值等于默认值，就进入下一步。
		if (defaultValue instanceof String && defaultValue.equals(annotationAttributes.get(AnnotationUtils.VALUE))) {
			// 方法参数列表
			Type[] parameterTypes = method.getGenericParameterTypes();
			// 方法的参数名称
			String[] parameterNames = PARAMETER_NAME_DISCOVERER.getParameterNames(method);
			if (shouldAddParameterName(parameterIndex, parameterTypes, parameterNames)) {
				// 则将参数名作为 value 的值进行回填。
				annotationAttributes.put(AnnotationUtils.VALUE, parameterNames[parameterIndex]);
			}
		}
		// 最后，生成并返回一个带有修改属性的合成注解。
		return AnnotationUtils.synthesizeAnnotation(annotationAttributes, parameterAnnotation.annotationType(), null);
	}

	private boolean shouldAddParameterName(int parameterIndex, Type[] parameterTypes, String[] parameterNames) {
		// has a parameter name
		return parameterNames != null && parameterNames.length > parameterIndex
		// has a type
				&& parameterTypes != null && parameterTypes.length > parameterIndex;
	}

	private boolean isMultipartFormData(MethodMetadata data) {
		Collection<String> contentTypes = data.template().headers().get(HttpEncoding.CONTENT_TYPE);

		if (contentTypes != null && !contentTypes.isEmpty()) {
			String type = contentTypes.iterator().next();
			try {
				return Objects.equals(MediaType.valueOf(type), MediaType.MULTIPART_FORM_DATA);
			}
			catch (InvalidMediaTypeException ignored) {
				return false;
			}
		}

		return false;
	}

	private static class ConvertingExpanderFactory {

		private final ConversionService conversionService;

		ConvertingExpanderFactory(ConversionService conversionService) {
			this.conversionService = conversionService;
		}

		Param.Expander getExpander(TypeDescriptor typeDescriptor) {
			return value -> {
				Object converted = conversionService.convert(value, typeDescriptor, STRING_TYPE_DESCRIPTOR);
				return (String) converted;
			};
		}

	}

	private class SimpleAnnotatedParameterContext implements AnnotatedParameterProcessor.AnnotatedParameterContext {

		private final MethodMetadata methodMetadata;

		private final int parameterIndex;

		SimpleAnnotatedParameterContext(MethodMetadata methodMetadata, int parameterIndex) {
			this.methodMetadata = methodMetadata;
			this.parameterIndex = parameterIndex;
		}

		@Override
		public MethodMetadata getMethodMetadata() {
			return methodMetadata;
		}

		@Override
		public int getParameterIndex() {
			return parameterIndex;
		}

		@Override
		public void setParameterName(String name) {
			nameParam(methodMetadata, name, parameterIndex);
		}

		@Override
		public Collection<String> setTemplateParameter(String name, Collection<String> rest) {
			// 添加模板参数
			return addTemplateParameter(rest, name);
		}

	}

}
