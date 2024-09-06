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

package org.springframework.cloud.openfeign;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import feign.Capability;
import feign.Client;
import feign.Contract;
import feign.ExceptionPropagationPolicy;
import feign.Feign;
import feign.Logger;
import feign.QueryMapEncoder;
import feign.Request;
import feign.RequestInterceptor;
import feign.Retryer;
import feign.Target.HardCodedTarget;
import feign.codec.Decoder;
import feign.codec.Encoder;
import feign.codec.ErrorDecoder;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.springframework.beans.BeanUtils;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.beans.factory.FactoryBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.cloud.openfeign.clientconfig.FeignClientConfigurer;
import org.springframework.cloud.openfeign.loadbalancer.FeignBlockingLoadBalancerClient;
import org.springframework.cloud.openfeign.loadbalancer.RetryableFeignBlockingLoadBalancerClient;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

/**
 * @author Spencer Gibb
 * @author Venil Noronha
 * @author Eko Kurniawan Khannedy
 * @author Gregor Zurowski
 * @author Matt King
 * @author Olga Maciaszek-Sharma
 * @author Ilia Ilinykh
 * @author Marcin Grzejszczak
 * @author Jonatan Ivanov
 * @author Sam Kruglov
 * @author Jasbir Singh
 * @author Hyeonmin Park
 * @author Felix Dittrich
 */
public class FeignClientFactoryBean
		implements FactoryBean<Object>, InitializingBean, ApplicationContextAware, BeanFactoryAware {

	/***********************************
	 * WARNING! Nothing in this class should be @Autowired. It causes NPEs because of some
	 * lifecycle race condition.
	 ***********************************/

	private static final Log LOG = LogFactory.getLog(FeignClientFactoryBean.class);

	private Class<?> type;

	private String name;

	private String url;

	private String contextId;

	private String path;

	private boolean decode404;

	private boolean inheritParentContext = true;

	private ApplicationContext applicationContext;

	private BeanFactory beanFactory;

	private Class<?> fallback = void.class;

	private Class<?> fallbackFactory = void.class;

	private int readTimeoutMillis = new Request.Options().readTimeoutMillis();

	private int connectTimeoutMillis = new Request.Options().connectTimeoutMillis();

	private boolean followRedirects = new Request.Options().isFollowRedirects();

	private boolean refreshableClient = false;

	/**
	 *  默认情况应该是空的
	 */
	private final List<FeignBuilderCustomizer> additionalCustomizers = new ArrayList<>();

	@Override
	public void afterPropertiesSet() {
		Assert.hasText(contextId, "Context id must be set");
		Assert.hasText(name, "Name must be set");
	}

	protected Feign.Builder feign(FeignContext context) {
		FeignLoggerFactory loggerFactory = get(context, FeignLoggerFactory.class);
		Logger logger = loggerFactory.create(type);

		// Feign 实例对象 建造器
		// @formatter:off
		Feign.Builder builder = get(context, Feign.Builder.class)
				// required values
				.logger(logger)
				.encoder(get(context, Encoder.class))
				.decoder(get(context, Decoder.class))
				.contract(get(context, Contract.class));
		// @formatter:on

		// 配置 feign 客户端
		configureFeign(context, builder);

		return builder;
	}

	private void applyBuildCustomizers(FeignContext context, Feign.Builder builder) {
		// 容器上下文找到 FeignBuilderCustomizer
		Map<String, FeignBuilderCustomizer> customizerMap = context.getInstances(contextId,
				FeignBuilderCustomizer.class);

		if (customizerMap != null) {
			// 根据排序执行定制化器链
			customizerMap.values().stream().sorted(AnnotationAwareOrderComparator.INSTANCE)
					.forEach(feignBuilderCustomizer -> feignBuilderCustomizer.customize(builder));
		}
		// 走一遍 FeignClientFactoryBean 对象内部维护的定制化器
		additionalCustomizers.forEach(customizer -> customizer.customize(builder));
	}

	protected void configureFeign(FeignContext context, Feign.Builder builder) {
		// 配置文件
		FeignClientProperties properties = beanFactory != null ? beanFactory.getBean(FeignClientProperties.class)
				: applicationContext.getBean(FeignClientProperties.class);

		FeignClientConfigurer feignClientConfigurer = getOptional(context, FeignClientConfigurer.class);
		// todo 看字面意思应该是是否要继承父上下文 默认 true
		setInheritParentContext(feignClientConfigurer.inheritParentConfiguration());

		if (properties != null && inheritParentContext) {
			if (properties.isDefaultToProperties()) {
				// 先捞Java Config 中的配置，再用配置文件中的覆盖
				configureUsingConfiguration(context, builder);
				// 再捞配置文件中的默认配置
				configureUsingProperties(properties.getConfig().get(properties.getDefaultConfig()), builder);
				// 针对 contextId 的个性化配置
				configureUsingProperties(properties.getConfig().get(contextId), builder);
			}
			else {
				// 再捞配置文件中的默认配置
				configureUsingProperties(properties.getConfig().get(properties.getDefaultConfig()), builder);
				// 针对 contextId 的个性化配置
				configureUsingProperties(properties.getConfig().get(contextId), builder);
                // todo 搞不懂为什么要调换位置 回头可以问问作者
				// todo 推测 defaultToProperties 的意思是什么配置类型优先的意思，默认为配置文件优先
				// todo 代码无注释，读者两行泪
				configureUsingConfiguration(context, builder);
			}
		}
		else {
			configureUsingConfiguration(context, builder);
		}
	}

	protected void configureUsingConfiguration(FeignContext context, Feign.Builder builder) {
		// 日志等级
		Logger.Level level = getInheritedAwareOptional(context, Logger.Level.class);
		if (level != null) {
			builder.logLevel(level);
		}
		// 重试器
		Retryer retryer = getInheritedAwareOptional(context, Retryer.class);
		if (retryer != null) {
			builder.retryer(retryer);
		}
		// 错误解码器
		ErrorDecoder errorDecoder = getInheritedAwareOptional(context, ErrorDecoder.class);
		if (errorDecoder != null) {
			builder.errorDecoder(errorDecoder);
		}
		else {
			// 错误解码器工厂
			FeignErrorDecoderFactory errorDecoderFactory = getOptional(context, FeignErrorDecoderFactory.class);
			if (errorDecoderFactory != null) {
				ErrorDecoder factoryErrorDecoder = errorDecoderFactory.create(type);
				builder.errorDecoder(factoryErrorDecoder);
			}
		}
		// 设置项
		Request.Options options = getInheritedAwareOptional(context, Request.Options.class);
		if (options == null) {
			options = getOptionsByName(context, contextId);
		}

		if (options != null) {
			builder.options(options);
			readTimeoutMillis = options.readTimeoutMillis();
			connectTimeoutMillis = options.connectTimeoutMillis();
			followRedirects = options.isFollowRedirects();
		}
		Map<String, RequestInterceptor> requestInterceptors = getInheritedAwareInstances(context,
				RequestInterceptor.class);
		if (requestInterceptors != null) {
            // 请求拦截器
			List<RequestInterceptor> interceptors = new ArrayList<>(requestInterceptors.values());
			AnnotationAwareOrderComparator.sort(interceptors);
			builder.requestInterceptors(interceptors);
		}
        // 查询映射编码器
        // todo 比较少用 它用于将对象编码为查询参数名称和值的映射（Map），从而在使用 Feign 客户端进行 HTTP 请求时，可以将对象的属性作为查询参数传递。这个接口提供了一种灵活的方式来将对象的字段转换为 HTTP 查询字符串，使得构建 URL 查询参数变得更加简单和模块化。
		QueryMapEncoder queryMapEncoder = getInheritedAwareOptional(context, QueryMapEncoder.class);
		if (queryMapEncoder != null) {
			builder.queryMapEncoder(queryMapEncoder);
		}
        // todo 一般为false 此标志指示 应 decoder 处理具有 404 状态的响应，特别是返回 null 或 empty 而不是引发 FeignException。
		if (decode404) {
			builder.dismiss404();
		}
        // 异常传播策略
		ExceptionPropagationPolicy exceptionPropagationPolicy = getInheritedAwareOptional(context,
				ExceptionPropagationPolicy.class);
		if (exceptionPropagationPolicy != null) {
			builder.exceptionPropagationPolicy(exceptionPropagationPolicy);
		}

        // todo 能力增强接口 笔记中有详细描述
		Map<String, Capability> capabilities = getInheritedAwareInstances(context, Capability.class);
		if (capabilities != null) {
			capabilities.values().stream().sorted(AnnotationAwareOrderComparator.INSTANCE)
					.forEach(builder::addCapability);
		}
	}

	protected void configureUsingProperties(FeignClientProperties.FeignClientConfiguration config,
			Feign.Builder builder) {
		if (config == null) {
			return;
		}
		// 日志级别 设置
		if (config.getLoggerLevel() != null) {
			builder.logLevel(config.getLoggerLevel());
		}

		// 连接、读取 超时时间设置
		if (!refreshableClient) {
			connectTimeoutMillis = config.getConnectTimeout() != null ? config.getConnectTimeout()
					: connectTimeoutMillis;
			readTimeoutMillis = config.getReadTimeout() != null ? config.getReadTimeout() : readTimeoutMillis;
			followRedirects = config.isFollowRedirects() != null ? config.isFollowRedirects() : followRedirects;

			builder.options(new Request.Options(connectTimeoutMillis, TimeUnit.MILLISECONDS, readTimeoutMillis,
					TimeUnit.MILLISECONDS, followRedirects));
		}

		// 重试器设置
		if (config.getRetryer() != null) {
			Retryer retryer = getOrInstantiate(config.getRetryer());
			builder.retryer(retryer);
		}

		// 错误解码器设置
		if (config.getErrorDecoder() != null) {
			ErrorDecoder errorDecoder = getOrInstantiate(config.getErrorDecoder());
			builder.errorDecoder(errorDecoder);
		}

		// 请求拦截器设置
		if (config.getRequestInterceptors() != null && !config.getRequestInterceptors().isEmpty()) {
			// this will add request interceptor to builder, not replace existing
			for (Class<RequestInterceptor> bean : config.getRequestInterceptors()) {
				RequestInterceptor interceptor = getOrInstantiate(bean);
				builder.requestInterceptor(interceptor);
			}
		}

		//  此标志指示 应 decoder 处理具有 404 状态的响应，特别是返回 null 或 empty 而不是引发 FeignException。
		if (config.getDecode404() != null) {
			if (config.getDecode404()) {
				builder.dismiss404();
			}
		}

		// 编码器
		if (Objects.nonNull(config.getEncoder())) {
			builder.encoder(getOrInstantiate(config.getEncoder()));
		}

		// 设置固定请求头
		addDefaultRequestHeaders(config, builder);

		// 设置固定请求查询参数
		addDefaultQueryParams(config, builder);

		// 解码器
		if (Objects.nonNull(config.getDecoder())) {
			builder.decoder(getOrInstantiate(config.getDecoder()));
		}

		// 设置契约（定义哪些注释和值在接口上有效。）
		if (Objects.nonNull(config.getContract())) {
			builder.contract(getOrInstantiate(config.getContract()));
		}

		// 异常传播策略 todo 目前不知道干啥的
		if (Objects.nonNull(config.getExceptionPropagationPolicy())) {
			builder.exceptionPropagationPolicy(config.getExceptionPropagationPolicy());
		}

		// todo 能力增强接口 笔记中有详细描述
		if (config.getCapabilities() != null) {
			config.getCapabilities().stream().map(this::getOrInstantiate).forEach(builder::addCapability);
		}

		// 查询映射编码器
		if (config.getQueryMapEncoder() != null) {
			builder.queryMapEncoder(getOrInstantiate(config.getQueryMapEncoder()));
		}
	}

	private void addDefaultQueryParams(FeignClientProperties.FeignClientConfiguration config, Feign.Builder builder) {
		Map<String, Collection<String>> defaultQueryParameters = config.getDefaultQueryParameters();
		if (Objects.nonNull(defaultQueryParameters)) {
			builder.requestInterceptor(requestTemplate -> {
				Map<String, Collection<String>> queries = requestTemplate.queries();
				defaultQueryParameters.keySet().forEach(key -> {
					if (!queries.containsKey(key)) {
						requestTemplate.query(key, defaultQueryParameters.get(key));
					}
				});
			});
		}
	}

	private void addDefaultRequestHeaders(FeignClientProperties.FeignClientConfiguration config,
			Feign.Builder builder) {
		Map<String, Collection<String>> defaultRequestHeaders = config.getDefaultRequestHeaders();
		if (Objects.nonNull(defaultRequestHeaders)) {
			builder.requestInterceptor(requestTemplate -> {
				Map<String, Collection<String>> headers = requestTemplate.headers();
				defaultRequestHeaders.keySet().forEach(key -> {
					if (!headers.containsKey(key)) {
						requestTemplate.header(key, defaultRequestHeaders.get(key));
					}
				});
			});
		}
	}

	private <T> T getOrInstantiate(Class<T> tClass) {
		try {
			return beanFactory != null ? beanFactory.getBean(tClass) : applicationContext.getBean(tClass);
		}
		catch (NoSuchBeanDefinitionException e) {
			return BeanUtils.instantiateClass(tClass);
		}
	}

	protected <T> T get(FeignContext context, Class<T> type) {
		T instance = context.getInstance(contextId, type);
		if (instance == null) {
			throw new IllegalStateException("No bean found of type " + type + " for " + contextId);
		}
		return instance;
	}

	protected <T> T getOptional(FeignContext context, Class<T> type) {
		return context.getInstance(contextId, type);
	}

	protected <T> T getInheritedAwareOptional(FeignContext context, Class<T> type) {
		if (inheritParentContext) {
			return getOptional(context, type);
		}
		else {
			return context.getInstanceWithoutAncestors(contextId, type);
		}
	}

	protected <T> Map<String, T> getInheritedAwareInstances(FeignContext context, Class<T> type) {
		if (inheritParentContext) {
			return context.getInstances(contextId, type);
		}
		else {
			return context.getInstancesWithoutAncestors(contextId, type);
		}
	}

	protected <T> T loadBalance(Feign.Builder builder, FeignContext context, HardCodedTarget<T> target) {
		Client client = getOptional(context, Client.class);
		if (client != null) {
			// 设置客户端
			builder.client(client);
			// 走 Feign.Builder 定制化器链
			applyBuildCustomizers(context, builder);
			Targeter targeter = get(context, Targeter.class);
			return targeter.target(this, builder, context, target);
		}

		throw new IllegalStateException(
				"No Feign Client for loadBalancing defined. Did you forget to include spring-cloud-starter-loadbalancer?");
	}

	/**
	 * Meant to get Options bean from context with bean name.
	 * @param context context of Feign client
	 * @param contextId name of feign client
	 * @return returns Options found in context
	 */
	protected Request.Options getOptionsByName(FeignContext context, String contextId) {
		if (refreshableClient) {
			return context.getInstance(contextId, Request.Options.class.getCanonicalName() + "-" + contextId,
					Request.Options.class);
		}
		return null;
	}

	@Override
	public Object getObject() {
		return getTarget();
	}

	/**
	 * @param <T> the target type of the Feign client
	 * @return a {@link Feign} client created with the specified data and the context
	 * information
	 */
	<T> T getTarget() {
		FeignContext context = beanFactory != null ? beanFactory.getBean(FeignContext.class)
				: applicationContext.getBean(FeignContext.class);
		Feign.Builder builder = feign(context);

		// 不存在 url 就说明需要走服务发现
		if (!StringUtils.hasText(url)) {

			if (LOG.isInfoEnabled()) {
				LOG.info("For '" + name + "' URL not provided. Will try picking an instance via load-balancing.");
			}
			if (!name.startsWith("http://") && !name.startsWith("https://")) {
				url = "http://" + name;
			}
			else {
				url = name;
			}
			url += cleanPath();
			return (T) loadBalance(builder, context, new HardCodedTarget<>(type, name, url));
		}
		// 具体的URL 不需要走服务发现
		if (StringUtils.hasText(url) && !url.startsWith("http://") && !url.startsWith("https://")) {
			url = "http://" + url;
		}
		String url = this.url + cleanPath();
		// 获取 Client
		Client client = getOptional(context, Client.class);
		if (client != null) {
			// 因为普通URL不需要客户端负载均衡所以这里需要取到原始的 Client（能够直接执行 普通URL的）
			if (client instanceof FeignBlockingLoadBalancerClient) {
				// not load balancing because we have a url,
				// but Spring Cloud LoadBalancer is on the classpath, so unwrap
				client = ((FeignBlockingLoadBalancerClient) client).getDelegate();
			}
			if (client instanceof RetryableFeignBlockingLoadBalancerClient) {
				// not load balancing because we have a url,
				// but Spring Cloud LoadBalancer is on the classpath, so unwrap
				client = ((RetryableFeignBlockingLoadBalancerClient) client).getDelegate();
			}
			builder.client(client);
		}
		// todo 这里以下未阅读
		applyBuildCustomizers(context, builder);

		Targeter targeter = get(context, Targeter.class);
		return (T) targeter.target(this, builder, context, new HardCodedTarget<>(type, name, url));
	}

	private String cleanPath() {
		if (path == null) {
			return "";
		}
		String path = this.path.trim();
		if (StringUtils.hasLength(path)) {
			if (!path.startsWith("/")) {
				path = "/" + path;
			}
			if (path.endsWith("/")) {
				path = path.substring(0, path.length() - 1);
			}
		}
		return path;
	}

	@Override
	public Class<?> getObjectType() {
		return type;
	}

	@Override
	public boolean isSingleton() {
		return true;
	}

	public Class<?> getType() {
		return type;
	}

	public void setType(Class<?> type) {
		this.type = type;
	}

	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}

	public String getContextId() {
		return contextId;
	}

	public void setContextId(String contextId) {
		this.contextId = contextId;
	}

	public String getUrl() {
		return url;
	}

	public void setUrl(String url) {
		this.url = url;
	}

	public String getPath() {
		return path;
	}

	public void setPath(String path) {
		this.path = path;
	}

	public boolean isDecode404() {
		return decode404;
	}

	public void setDecode404(boolean decode404) {
		this.decode404 = decode404;
	}

	public boolean isInheritParentContext() {
		return inheritParentContext;
	}

	public void setInheritParentContext(boolean inheritParentContext) {
		this.inheritParentContext = inheritParentContext;
	}

	public void addCustomizer(FeignBuilderCustomizer customizer) {
		additionalCustomizers.add(customizer);
	}

	public ApplicationContext getApplicationContext() {
		return applicationContext;
	}

	@Override
	public void setApplicationContext(ApplicationContext context) throws BeansException {
		applicationContext = context;
		beanFactory = context;
	}

	public Class<?> getFallback() {
		return fallback;
	}

	public void setFallback(Class<?> fallback) {
		this.fallback = fallback;
	}

	public Class<?> getFallbackFactory() {
		return fallbackFactory;
	}

	public void setFallbackFactory(Class<?> fallbackFactory) {
		this.fallbackFactory = fallbackFactory;
	}

	public void setRefreshableClient(boolean refreshableClient) {
		this.refreshableClient = refreshableClient;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) {
			return true;
		}
		if (o == null || getClass() != o.getClass()) {
			return false;
		}
		FeignClientFactoryBean that = (FeignClientFactoryBean) o;
		return Objects.equals(applicationContext, that.applicationContext)
				&& Objects.equals(beanFactory, that.beanFactory) && decode404 == that.decode404
				&& inheritParentContext == that.inheritParentContext && Objects.equals(fallback, that.fallback)
				&& Objects.equals(fallbackFactory, that.fallbackFactory) && Objects.equals(name, that.name)
				&& Objects.equals(path, that.path) && Objects.equals(type, that.type) && Objects.equals(url, that.url)
				&& Objects.equals(connectTimeoutMillis, that.connectTimeoutMillis)
				&& Objects.equals(readTimeoutMillis, that.readTimeoutMillis)
				&& Objects.equals(followRedirects, that.followRedirects)
				&& Objects.equals(refreshableClient, that.refreshableClient);
	}

	@Override
	public int hashCode() {
		return Objects.hash(applicationContext, beanFactory, decode404, inheritParentContext, fallback, fallbackFactory,
				name, path, type, url, readTimeoutMillis, connectTimeoutMillis, followRedirects, refreshableClient);
	}

	@Override
	public String toString() {
		return new StringBuilder("FeignClientFactoryBean{").append("type=").append(type).append(", ").append("name='")
				.append(name).append("', ").append("url='").append(url).append("', ").append("path='").append(path)
				.append("', ").append("decode404=").append(decode404).append(", ").append("inheritParentContext=")
				.append(inheritParentContext).append(", ").append("applicationContext=").append(applicationContext)
				.append(", ").append("beanFactory=").append(beanFactory).append(", ").append("fallback=")
				.append(fallback).append(", ").append("fallbackFactory=").append(fallbackFactory).append("}")
				.append("connectTimeoutMillis=").append(connectTimeoutMillis).append("}").append("readTimeoutMillis=")
				.append(readTimeoutMillis).append("}").append("followRedirects=").append(followRedirects)
				.append("refreshableClient=").append(refreshableClient).append("}").toString();
	}

	@Override
	public void setBeanFactory(BeanFactory beanFactory) throws BeansException {
		this.beanFactory = beanFactory;
	}

}
