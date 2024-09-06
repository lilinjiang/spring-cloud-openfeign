/*
 * Copyright 2013-2022 the original author or authors.
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

package org.springframework.cloud.openfeign.annotation;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.Map;

import feign.MethodMetadata;

import org.springframework.cloud.openfeign.AnnotatedParameterProcessor;
import org.springframework.web.bind.annotation.RequestParam;

import static feign.Util.checkState;
import static feign.Util.emptyToNull;

/**
 * {@link RequestParam} parameter processor.
 *
 * @author Jakub Narloch
 * @author Abhijit Sarkar
 * @see AnnotatedParameterProcessor
 */
public class RequestParamParameterProcessor implements AnnotatedParameterProcessor {

	private static final Class<RequestParam> ANNOTATION = RequestParam.class;

	@Override
	public Class<? extends Annotation> getAnnotationType() {
		return ANNOTATION;
	}

	@Override
	public boolean processArgument(AnnotatedParameterContext context, Annotation annotation, Method method) {
		// 参数下标
		int parameterIndex = context.getParameterIndex();
		// 参数类型
		Class<?> parameterType = method.getParameterTypes()[parameterIndex];
		// 方法原数据
		MethodMetadata data = context.getMethodMetadata();

		// 查询映射只能有一个
		if (Map.class.isAssignableFrom(parameterType)) {
			checkState(data.queryMapIndex() == null, "Query map can only be present once.");
			data.queryMapIndex(parameterIndex);

			return true;
		}

		RequestParam requestParam = ANNOTATION.cast(annotation);
		String name = requestParam.value();
		// 这里就是校验必须要有参数名
		checkState(emptyToNull(name) != null, "RequestParam.value() was empty on parameter %s of method %s",
				parameterIndex, method.getName());
		// 添加Parameter 参数名与参数下标的映射关系
		// 从这个方法的处理逻辑来看,一个参数支持 多个 @RequestParam注解
		context.setParameterName(name);

		// 这一行其实就是把 RequestTemplate 中 QueryTemplate 模板中的值全部拿出来形成一个List,然后再将name对应的值占位符模板加进去
		// 正常情况下 这里应该是取出来空列表,因为正常来讲一个QueryString参数的名称只会对应一个值
		Collection<String> query = context.setTemplateParameter(name, data.template().queries().get(name));
		// 这里才是将 值占位符模板加到 queries 中
		data.template().query(name, query);
		return true;
	}

}
