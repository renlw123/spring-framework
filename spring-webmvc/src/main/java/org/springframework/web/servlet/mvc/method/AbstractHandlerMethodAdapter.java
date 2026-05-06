/*
 * Copyright 2002-2021 the original author or authors.
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

package org.springframework.web.servlet.mvc.method;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.springframework.core.Ordered;
import org.springframework.lang.Nullable;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerAdapter;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.support.WebContentGenerator;

/**
 * Abstract base class for {@link HandlerAdapter} implementations that support
 * handlers of type {@link HandlerMethod}.
 *
 * @author Arjen Poutsma
 * @since 3.1
 */
public abstract class AbstractHandlerMethodAdapter extends WebContentGenerator implements HandlerAdapter, Ordered {

	private int order = Ordered.LOWEST_PRECEDENCE;


	public AbstractHandlerMethodAdapter() {
		// no restriction of HTTP methods by default
		super(false);
	}


	/**
	 * Specify the order value for this HandlerAdapter bean.
	 * <p>The default value is {@code Ordered.LOWEST_PRECEDENCE}, meaning non-ordered.
	 * @see org.springframework.core.Ordered#getOrder()
	 */
	public void setOrder(int order) {
		this.order = order;
	}

	@Override
	public int getOrder() {
		return this.order;
	}


	/**
	 *
	 * @Controller
	 * @RequestMapping("/api/users") // 类级别路径
	 * public class UserController {
	 *
	 *     do someSing
	 * }
	 *
	 * 这个实现要求处理器必须是 HandlerMethod 类型
	 * @param handler 要检查的处理器实例
	 * @return 该适配器是否能适配给定的处理器
	 */
	@Override
	public final boolean supports(Object handler) {
		// 1. 首先必须是 HandlerMethod 类型（即 @RequestMapping 注解的方法）
		// 2. 然后调用 supportsInternal 进行额外的内部校验
		return (handler instanceof HandlerMethod && supportsInternal((HandlerMethod) handler));
	}

	/**
	 * Given a handler method, return whether this adapter can support it.
	 * @param handlerMethod the handler method to check
	 * @return whether this adapter can adapt the given method
	 */
	protected abstract boolean supportsInternal(HandlerMethod handlerMethod);

	/**
	 * 此实现要求处理器必须是一个 {@link HandlerMethod} 对象。
	 *
	 * HandlerMethod 封装了控制器中处理请求的具体方法，包括：
	 * - 方法本身（Method 对象）
	 * - 所属的 Bean 实例
	 * - 方法参数、返回值等信息
	 *
	 * @param request  当前 HTTP 请求对象
	 * @param response 当前 HTTP 响应对象
	 * @param handler  处理器对象，在此实现中必须是 HandlerMethod 类型
	 * @return ModelAndView 对象，如果请求已被直接处理则返回 null
	 * @throws Exception 处理过程中可能抛出的异常
	 */
	@Override
	@Nullable
	public final ModelAndView handle(HttpServletRequest request, HttpServletResponse response, Object handler)
			throws Exception {

		// 将 handler 强制转换为 HandlerMethod 类型，然后调用内部处理方法
		// handleInternal 是抽象方法，由具体的子类（如 RequestMappingHandlerAdapter）实现
		return handleInternal(request, response, (HandlerMethod) handler);
	}

	/**
	 * Use the given handler method to handle the request.
	 * @param request current HTTP request
	 * @param response current HTTP response
	 * @param handlerMethod handler method to use. This object must have previously been passed to the
	 * {@link #supportsInternal(HandlerMethod)} this interface, which must have returned {@code true}.
	 * @return a ModelAndView object with the name of the view and the required model data,
	 * or {@code null} if the request has been handled directly
	 * @throws Exception in case of errors
	 */
	@Nullable
	protected abstract ModelAndView handleInternal(HttpServletRequest request,
			HttpServletResponse response, HandlerMethod handlerMethod) throws Exception;

	/**
	 * This implementation expects the handler to be an {@link HandlerMethod}.
	 */
	@Override
	@SuppressWarnings("deprecation")
	public final long getLastModified(HttpServletRequest request, Object handler) {
		return getLastModifiedInternal(request, (HandlerMethod) handler);
	}

	/**
	 * Same contract as for {@link javax.servlet.http.HttpServlet#getLastModified(HttpServletRequest)}.
	 * @param request current HTTP request
	 * @param handlerMethod handler method to use
	 * @return the lastModified value for the given handler
	 * @deprecated as of 5.3.9 along with
	 * {@link org.springframework.web.servlet.mvc.LastModified}.
	 */
	@Deprecated
	protected abstract long getLastModifiedInternal(HttpServletRequest request, HandlerMethod handlerMethod);

}
