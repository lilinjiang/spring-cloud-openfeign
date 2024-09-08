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

import feign.Feign;
import feign.Target;

/**
 * todo 名字听起来怪怪的
 * Targeter 是 Spring Cloud Feign 框架中的一个接口，
 * 用于定义如何将 Feign 客户端实例与具体的目标服务（Feign Client 注解接口）进行绑定。
 * 这个接口的主要作用是负责创建和配置 Feign 客户端实例，并将其与目标服务进行关联。
 *
 * 个人理解就是在生成实际的代理对象时加一层封装去支持一些功能比如：服务降级 FeignCircuitBreakerTargeter
 *
 * @author Spencer Gibb
 */
public interface Targeter {

	<T> T target(FeignClientFactoryBean factory, Feign.Builder feign, FeignContext context,
			Target.HardCodedTarget<T> target);

}
