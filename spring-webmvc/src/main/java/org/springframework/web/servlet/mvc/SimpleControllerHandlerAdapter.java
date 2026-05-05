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

package org.springframework.web.servlet.mvc;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.springframework.lang.Nullable;
import org.springframework.web.servlet.HandlerAdapter;
import org.springframework.web.servlet.ModelAndView;

/**
 * Adapter to use the plain {@link Controller} workflow interface with
 * the generic {@link org.springframework.web.servlet.DispatcherServlet}.
 * Supports handlers that implement the {@link LastModified} interface.
 *
 * <p>This is an SPI class, not used directly by application code.
 *
 * @author Rod Johnson
 * @author Juergen Hoeller
 * @see org.springframework.web.servlet.DispatcherServlet
 * @see Controller
 * @see HttpRequestHandlerAdapter
 */
public class SimpleControllerHandlerAdapter implements HandlerAdapter {

	/**
	 * public class MyTraditionalController implements Controller {
	 *
	 *     @Override
	 *     public ModelAndView handleRequest(HttpServletRequest request,
	 *                                       HttpServletResponse response) throws Exception {
	 *
	 *         // 1. 获取请求参数
	 *         String name = request.getParameter("name");
	 *
	 *         // 2. 处理业务逻辑
	 *         String message = "Hello, " + (name != null ? name : "Guest");
	 *
	 *         // 3. 创建 ModelAndView 对象
	 *         ModelAndView mav = new ModelAndView();
	 *
	 *         // 4. 添加数据到模型
	 *         mav.addObject("message", message);
	 *         mav.addObject("timestamp", System.currentTimeMillis());
	 *
	 *         // 5. 设置视图名称（JSP 路径）
	 *         mav.setViewName("hello");  // 会解析为 /WEB-INF/views/hello.jsp
	 *
	 *         return mav;
	 *     }
	 * }
	 */
	@Override
	public boolean supports(Object handler) {
		// 只要处理器是 Controller 接口的实例，就返回 true
		return (handler instanceof Controller);
	}

	@Override
	@Nullable
	public ModelAndView handle(HttpServletRequest request, HttpServletResponse response, Object handler)
			throws Exception {

		return ((Controller) handler).handleRequest(request, response);
	}

	@Override
	@SuppressWarnings("deprecation")
	public long getLastModified(HttpServletRequest request, Object handler) {
		if (handler instanceof LastModified) {
			return ((LastModified) handler).getLastModified(request);
		}
		return -1L;
	}

}
