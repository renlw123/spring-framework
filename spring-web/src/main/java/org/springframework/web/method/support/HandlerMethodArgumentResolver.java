/*
 * Copyright 2002-2014 the original author or authors.
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

package org.springframework.web.method.support;

import org.springframework.core.MethodParameter;
import org.springframework.lang.Nullable;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;

/**
 * Strategy interface for resolving method parameters into argument values in
 * the context of a given request.
 *
 * @author Arjen Poutsma
 * @since 3.1
 * @see HandlerMethodReturnValueHandler
 */
public interface HandlerMethodArgumentResolver {

	/**
	 * 判断给定的方法参数是否被此解析器支持。
	 *
	 * <p>每个参数解析器都需要实现此方法，根据自身的能力范围来判断
	 * 是否可以处理当前传入的方法参数。解析器通常基于以下条件进行判断：
	 * <ul>
	 *   <li>参数上是否有特定的注解（如 @RequestParam、@PathVariable）</li>
	 *   <li>参数的类型（如 HttpServletRequest、Model、Map）</li>
	 *   <li>参数的泛型信息（如 List<User>、Optional<Long>）</li>
	 *   <li>参数的其他元数据（如是否可选、是否必需）</li>
	 * </ul>
	 *
	 * <p>Spring MVC 会遍历所有已注册的参数解析器，调用此方法逐个检查，
	 * 找到第一个返回 {@code true} 的解析器来处理该参数。
	 *
	 * @param parameter 需要检查的方法参数，包含参数类型、注解、泛型等信息
	 * @return 如果此解析器支持该参数则返回 {@code true}，否则返回 {@code false}
	 */
	boolean supportsParameter(MethodParameter parameter);

	/**
	 * Resolves a method parameter into an argument value from a given request.
	 * A {@link ModelAndViewContainer} provides access to the model for the
	 * request. A {@link WebDataBinderFactory} provides a way to create
	 * a {@link WebDataBinder} instance when needed for data binding and
	 * type conversion purposes.
	 * @param parameter the method parameter to resolve. This parameter must
	 * have previously been passed to {@link #supportsParameter} which must
	 * have returned {@code true}.
	 * @param mavContainer the ModelAndViewContainer for the current request
	 * @param webRequest the current request
	 * @param binderFactory a factory for creating {@link WebDataBinder} instances
	 * @return the resolved argument value, or {@code null} if not resolvable
	 * @throws Exception in case of errors with the preparation of argument values
	 */
	@Nullable
	Object resolveArgument(MethodParameter parameter, @Nullable ModelAndViewContainer mavContainer,
			NativeWebRequest webRequest, @Nullable WebDataBinderFactory binderFactory) throws Exception;

}
