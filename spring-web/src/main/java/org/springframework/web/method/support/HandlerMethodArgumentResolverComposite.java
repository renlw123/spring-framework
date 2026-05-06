/*
 * Copyright 2002-2022 the original author or authors.
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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.core.MethodParameter;
import org.springframework.lang.Nullable;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;

/**
 * Resolves method parameters by delegating to a list of registered
 * {@link HandlerMethodArgumentResolver HandlerMethodArgumentResolvers}.
 * Previously resolved method parameters are cached for faster lookups.
 *
 * @author Rossen Stoyanchev
 * @author Juergen Hoeller
 * @since 3.1
 */
public class HandlerMethodArgumentResolverComposite implements HandlerMethodArgumentResolver {

	private final List<HandlerMethodArgumentResolver> argumentResolvers = new ArrayList<>();

	private final Map<MethodParameter, HandlerMethodArgumentResolver> argumentResolverCache =
			new ConcurrentHashMap<>(256);


	/**
	 * Add the given {@link HandlerMethodArgumentResolver}.
	 */
	public HandlerMethodArgumentResolverComposite addResolver(HandlerMethodArgumentResolver resolver) {
		this.argumentResolvers.add(resolver);
		return this;
	}

	/**
	 * Add the given {@link HandlerMethodArgumentResolver HandlerMethodArgumentResolvers}.
	 * @since 4.3
	 */
	public HandlerMethodArgumentResolverComposite addResolvers(
			@Nullable HandlerMethodArgumentResolver... resolvers) {

		if (resolvers != null) {
			Collections.addAll(this.argumentResolvers, resolvers);
		}
		return this;
	}

	/**
	 * Add the given {@link HandlerMethodArgumentResolver HandlerMethodArgumentResolvers}.
	 */
	public HandlerMethodArgumentResolverComposite addResolvers(
			@Nullable List<? extends HandlerMethodArgumentResolver> resolvers) {

		if (resolvers != null) {
			this.argumentResolvers.addAll(resolvers);
		}
		return this;
	}

	/**
	 * Return a read-only list with the contained resolvers, or an empty list.
	 */
	public List<HandlerMethodArgumentResolver> getResolvers() {
		return Collections.unmodifiableList(this.argumentResolvers);
	}

	/**
	 * Clear the list of configured resolvers and the resolver cache.
	 * @since 4.3
	 */
	public void clear() {
		this.argumentResolvers.clear();
		this.argumentResolverCache.clear();
	}


	/**
	 * 判断给定的方法参数是否被任何已注册的 {@link HandlerMethodArgumentResolver} 所支持。
	 *
	 * <p>这是一个快速检查方法，通过查找匹配的参数解析器来判断参数是否可解析。
	 * 通常在使用 {@link #resolveArgument} 之前调用此方法进行预检。
	 *
	 * @param parameter 需要检查的方法参数，包含参数类型、注解、泛型等信息
	 * @return 如果存在至少一个支持该参数的参数解析器则返回 {@code true}，否则返回 {@code false}
	 */
	@Override
	public boolean supportsParameter(MethodParameter parameter) {
		// 根据参数查找对应的解析器，如果找到则返回 true，否则返回 false
		return getArgumentResolver(parameter) != null;
	}

	/**
	 * Iterate over registered
	 * {@link HandlerMethodArgumentResolver HandlerMethodArgumentResolvers}
	 * and invoke the one that supports it.
	 * @throws IllegalArgumentException if no suitable argument resolver is found
	 */
	@Override
	@Nullable
	public Object resolveArgument(MethodParameter parameter, @Nullable ModelAndViewContainer mavContainer,
			NativeWebRequest webRequest, @Nullable WebDataBinderFactory binderFactory) throws Exception {

		HandlerMethodArgumentResolver resolver = getArgumentResolver(parameter);
		if (resolver == null) {
			throw new IllegalArgumentException("Unsupported parameter type [" +
					parameter.getParameterType().getName() + "]. supportsParameter should be called first.");
		}
		return resolver.resolveArgument(parameter, mavContainer, webRequest, binderFactory);
	}

	/**
	 * 查找一个已注册的、支持给定方法参数的 {@link HandlerMethodArgumentResolver}。
	 *
	 * <p>此方法首先尝试从缓存中获取参数对应的解析器，如果缓存中不存在，
	 * 则遍历所有已注册的参数解析器，找到第一个支持该参数的解析器，
	 * 并将其放入缓存中以供后续使用。
	 *
	 * <p>这种缓存机制可以显著提升性能，避免对同一参数类型的重复查找，
	 * 因为方法参数的定义在运行时是固定的。
	 *
	 * @param parameter 需要查找解析器的方法参数，包含参数类型、注解、泛型等信息
	 * @return 支持该参数的参数解析器，如果没有找到任何支持的解析器则返回 {@code null}
	 */
	@Nullable
	private HandlerMethodArgumentResolver getArgumentResolver(MethodParameter parameter) {
		// ==================== 1. 从缓存中查找 ====================
		// 首先尝试从缓存中获取该参数对应的解析器
		// argumentResolverCache 是 ConcurrentHashMap，线程安全
		HandlerMethodArgumentResolver result = this.argumentResolverCache.get(parameter);

		if (result == null) {
			// ==================== 2. 缓存未命中，遍历所有解析器 ====================
			// 遍历所有已注册的参数解析器（按注册顺序）
			for (HandlerMethodArgumentResolver resolver : this.argumentResolvers) {
				// 检查当前解析器是否支持该参数
				if (resolver.supportsParameter(parameter)) {
					// 找到第一个支持的解析器
					result = resolver;
					// ==================== 3. 存入缓存 ====================
					// 将解析器缓存起来，下次遇到相同的 MethodParameter 时可直接使用
					// 注意：MethodParameter 实现了 equals/hashCode，基于方法、参数索引等
					this.argumentResolverCache.put(parameter, result);
					break;  // 找到后立即退出循环
				}
			}
		}

		return result;
	}

}
