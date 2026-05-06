/*
 * Copyright 2002-2020 the original author or authors.
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

import org.springframework.core.MethodParameter;
import org.springframework.lang.Nullable;
import org.springframework.web.context.request.NativeWebRequest;

/**
 * Handles method return values by delegating to a list of registered
 * {@link HandlerMethodReturnValueHandler HandlerMethodReturnValueHandlers}.
 * Previously resolved return types are cached for faster lookups.
 *
 * @author Rossen Stoyanchev
 * @since 3.1
 */
public class HandlerMethodReturnValueHandlerComposite implements HandlerMethodReturnValueHandler {

	private final List<HandlerMethodReturnValueHandler> returnValueHandlers = new ArrayList<>();


	/**
	 * Return a read-only list with the registered handlers, or an empty list.
	 */
	public List<HandlerMethodReturnValueHandler> getHandlers() {
		return Collections.unmodifiableList(this.returnValueHandlers);
	}

	/**
	 * Whether the given {@linkplain MethodParameter method return type} is supported by any registered
	 * {@link HandlerMethodReturnValueHandler}.
	 */
	@Override
	public boolean supportsReturnType(MethodParameter returnType) {
		return getReturnValueHandler(returnType) != null;
	}

	@Nullable
	private HandlerMethodReturnValueHandler getReturnValueHandler(MethodParameter returnType) {
		for (HandlerMethodReturnValueHandler handler : this.returnValueHandlers) {
			if (handler.supportsReturnType(returnType)) {
				return handler;
			}
		}
		return null;
	}

	/**
	 * 遍历所有已注册的 {@link HandlerMethodReturnValueHandler}，
	 * 找到支持当前返回值类型的处理器并调用它。
	 *
	 * <p>返回值处理器负责处理控制器方法返回的各种类型：
	 * <ul>
	 *   <li>String - 视图名称</li>
	 *   <li>ModelAndView - 包含视图和模型</li>
	 *   <li>@ResponseBody - 写入响应体（JSON/XML）</li>
	 *   <li>ResponseEntity - 包含响应状态和头信息</li>
	 *   <li>Callable/DeferredResult - 异步返回值</li>
	 *   <li>void/null - 无返回值</li>
	 * </ul>
	 *
	 * @param returnValue 控制器方法返回的值（可能为 null）
	 * @param returnType  返回值类型信息，包含类型、泛型、注解等元数据
	 * @param mavContainer ModelAndView 容器，管理模型和视图信息
	 * @param webRequest   当前请求对象，封装了 HttpServletRequest 和 HttpServletResponse
	 * @throws IllegalStateException 如果找不到合适的 {@link HandlerMethodReturnValueHandler} 时抛出
	 * @throws IllegalArgumentException 如果找不到合适的处理器时抛出（更精确地说）
	 */
	@Override
	public void handleReturnValue(@Nullable Object returnValue, MethodParameter returnType,
								  ModelAndViewContainer mavContainer, NativeWebRequest webRequest) throws Exception {

		// ==================== 1. 选择合适的返回值处理器 ====================
		// 遍历所有已注册的返回值处理器，找到第一个支持当前返回值类型的处理器
		HandlerMethodReturnValueHandler handler = selectHandler(returnValue, returnType);

		// ==================== 2. 检查是否找到处理器 ====================
		if (handler == null) {
			// 没有找到任何支持该返回值类型的处理器，抛出异常
			throw new IllegalArgumentException("Unknown return value type: " + returnType.getParameterType().getName());
		}

		// ==================== 3. 委托给选中的处理器执行处理 ====================
		// 由具体的处理器完成返回值的实际处理逻辑
		// 例如：写入响应体、设置视图、处理重定向等
		handler.handleReturnValue(returnValue, returnType, mavContainer, webRequest);
	}

	/**
	 * 从已注册的返回值处理器中选择一个支持给定返回类型的处理器。
	 *
	 * <p>此方法会考虑异步返回值的特殊情况：
	 * 如果返回值是异步类型（如 Callable、DeferredResult、ListenableFuture 等），
	 * 则只会选择实现了 {@link AsyncHandlerMethodReturnValueHandler} 接口的处理器，
	 * 确保异步结果能被正确处理。
	 *
	 * @param value      控制器方法返回的实际值（可能为 null）
	 * @param returnType 返回值类型信息，包含类型、泛型、注解等元数据
	 * @return 支持该返回类型的处理器，如果没有找到则返回 {@code null}
	 */
	@Nullable
	private HandlerMethodReturnValueHandler selectHandler(@Nullable Object value, MethodParameter returnType) {
		// ==================== 1. 判断是否为异步返回值 ====================
		// 检查返回值是否是异步类型（Callable、DeferredResult、CompletionStage 等）
		boolean isAsyncValue = isAsyncReturnValue(value, returnType);

		// ==================== 2. 遍历所有已注册的返回值处理器 ====================
		for (HandlerMethodReturnValueHandler handler : this.returnValueHandlers) {
			// 如果当前返回值是异步类型，但当前处理器不是异步处理器，则跳过
			// 这样可以确保异步返回值只由支持异步的处理器处理
			if (isAsyncValue && !(handler instanceof AsyncHandlerMethodReturnValueHandler)) {
				continue;  // 跳过非异步处理器
			}

			// 检查当前处理器是否支持该返回类型
			if (handler.supportsReturnType(returnType)) {
				return handler;  // 返回第一个匹配的处理器
			}
		}

		// 没有找到任何合适的处理器
		return null;
	}

	private boolean isAsyncReturnValue(@Nullable Object value, MethodParameter returnType) {
		for (HandlerMethodReturnValueHandler handler : this.returnValueHandlers) {
			if (handler instanceof AsyncHandlerMethodReturnValueHandler &&
					((AsyncHandlerMethodReturnValueHandler) handler).isAsyncReturnValue(value, returnType)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Add the given {@link HandlerMethodReturnValueHandler}.
	 */
	public HandlerMethodReturnValueHandlerComposite addHandler(HandlerMethodReturnValueHandler handler) {
		this.returnValueHandlers.add(handler);
		return this;
	}

	/**
	 * Add the given {@link HandlerMethodReturnValueHandler HandlerMethodReturnValueHandlers}.
	 */
	public HandlerMethodReturnValueHandlerComposite addHandlers(
			@Nullable List<? extends HandlerMethodReturnValueHandler> handlers) {

		if (handlers != null) {
			this.returnValueHandlers.addAll(handlers);
		}
		return this;
	}

}
