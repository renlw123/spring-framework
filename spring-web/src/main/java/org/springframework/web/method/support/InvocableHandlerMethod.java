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

package org.springframework.web.method.support;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;

import org.springframework.context.MessageSource;
import org.springframework.core.CoroutinesUtils;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.KotlinDetector;
import org.springframework.core.MethodParameter;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.lang.Nullable;
import org.springframework.util.ObjectUtils;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.support.SessionStatus;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.HandlerMethod;

/**
 * Extension of {@link HandlerMethod} that invokes the underlying method with
 * argument values resolved from the current HTTP request through a list of
 * {@link HandlerMethodArgumentResolver}.
 *
 * @author Rossen Stoyanchev
 * @author Juergen Hoeller
 * @author Sebastien Deleuze
 * @since 3.1
 */
public class InvocableHandlerMethod extends HandlerMethod {

	private static final Object[] EMPTY_ARGS = new Object[0];


	private HandlerMethodArgumentResolverComposite resolvers = new HandlerMethodArgumentResolverComposite();

	private ParameterNameDiscoverer parameterNameDiscoverer = new DefaultParameterNameDiscoverer();

	@Nullable
	private WebDataBinderFactory dataBinderFactory;


	/**
	 * Create an instance from a {@code HandlerMethod}.
	 */
	public InvocableHandlerMethod(HandlerMethod handlerMethod) {
		super(handlerMethod);
	}

	/**
	 * Create an instance from a bean instance and a method.
	 */
	public InvocableHandlerMethod(Object bean, Method method) {
		super(bean, method);
	}

	/**
	 * Variant of {@link #InvocableHandlerMethod(Object, Method)} that
	 * also accepts a {@link MessageSource}, for use in subclasses.
	 * @since 5.3.10
	 */
	protected InvocableHandlerMethod(Object bean, Method method, @Nullable MessageSource messageSource) {
		super(bean, method, messageSource);
	}

	/**
	 * Construct a new handler method with the given bean instance, method name and parameters.
	 * @param bean the object bean
	 * @param methodName the method name
	 * @param parameterTypes the method parameter types
	 * @throws NoSuchMethodException when the method cannot be found
	 */
	public InvocableHandlerMethod(Object bean, String methodName, Class<?>... parameterTypes)
			throws NoSuchMethodException {

		super(bean, methodName, parameterTypes);
	}


	/**
	 * Set {@link HandlerMethodArgumentResolver HandlerMethodArgumentResolvers}
	 * to use for resolving method argument values.
	 */
	public void setHandlerMethodArgumentResolvers(HandlerMethodArgumentResolverComposite argumentResolvers) {
		this.resolvers = argumentResolvers;
	}

	/**
	 * Set the ParameterNameDiscoverer for resolving parameter names when needed
	 * (e.g. default request attribute name).
	 * <p>Default is a {@link org.springframework.core.DefaultParameterNameDiscoverer}.
	 */
	public void setParameterNameDiscoverer(ParameterNameDiscoverer parameterNameDiscoverer) {
		this.parameterNameDiscoverer = parameterNameDiscoverer;
	}

	/**
	 * Set the {@link WebDataBinderFactory} to be passed to argument resolvers allowing them
	 * to create a {@link WebDataBinder} for data binding and type conversion purposes.
	 */
	public void setDataBinderFactory(WebDataBinderFactory dataBinderFactory) {
		this.dataBinderFactory = dataBinderFactory;
	}


	/**
	 * 在给定请求的上下文中解析方法参数值后，调用该方法。
	 *
	 * <p>参数值通常通过 {@link HandlerMethodArgumentResolver HandlerMethodArgumentResolvers} 来解析。
	 * 然而，{@code providedArgs} 参数可以直接提供要使用的参数值，即无需进行参数解析。
	 * 提供的参数值示例包括 {@link WebDataBinder}、{@link SessionStatus} 或抛出的异常实例。
	 * 提供的参数值会在参数解析器之前进行检查。
	 *
	 * <p>委托给 {@link #getMethodArgumentValues} 获取解析后的参数，
	 * 然后使用这些参数调用 {@link #doInvoke}。
	 *
	 * @param request      当前请求对象，封装了请求和响应
	 * @param mavContainer 当前请求的 ModelAndView 容器（可能为 null）
	 * @param providedArgs "已提供的"参数，按类型匹配，不进行解析
	 * @return 被调用方法返回的原始值
	 * @throws Exception 如果找不到合适的参数解析器，或者方法抛出异常时抛出
	 * @see #getMethodArgumentValues
	 * @see #doInvoke
	 */
	@Nullable
	public Object invokeForRequest(NativeWebRequest request, @Nullable ModelAndViewContainer mavContainer,
								   Object... providedArgs) throws Exception {

		// ==================== 1. 解析方法参数 ====================
		// 获取方法参数值数组：
		// - 优先使用 providedArgs 中匹配类型的参数
		// - 其次通过 HandlerMethodArgumentResolver 逐个解析
		Object[] args = getMethodArgumentValues(request, mavContainer, providedArgs);

		// ==================== 2. 调试日志 ====================
		if (logger.isTraceEnabled()) {
			logger.trace("Arguments: " + Arrays.toString(args));
		}

		// ==================== 3. 通过反射调用目标方法 ====================
		// 使用解析好的参数数组调用控制器方法
		return doInvoke(args);
	}

	/**
	 * 获取当前请求的方法参数值，首先检查提供的参数值，
	 * 如果未找到则回退到配置的参数解析器。
	 *
	 * <p>生成的参数数组将传递给 {@link #doInvoke} 方法用于反射调用。
	 *
	 * @since 5.1.2
	 * @param request      当前请求对象，封装了请求和响应
	 * @param mavContainer 当前请求的 ModelAndView 容器（可能为 null）
	 * @param providedArgs "已提供的"参数，按类型匹配，优先使用
	 * @return 解析后的方法参数值数组
	 * @throws Exception 如果找不到合适的参数解析器，或参数解析过程中抛出异常
	 */
	protected Object[] getMethodArgumentValues(NativeWebRequest request, @Nullable ModelAndViewContainer mavContainer,
											   Object... providedArgs) throws Exception {

		// ==================== 1. 获取方法的所有参数定义 ====================
		MethodParameter[] parameters = getMethodParameters();

		// 如果没有参数，直接返回空数组
		if (ObjectUtils.isEmpty(parameters)) {
			return EMPTY_ARGS;
		}

		// ==================== 2. 创建参数值数组并逐个解析 ====================
		Object[] args = new Object[parameters.length];

		for (int i = 0; i < parameters.length; i++) {
			MethodParameter parameter = parameters[i];

			// 初始化参数名称发现器（用于获取参数名，如 @RequestParam 未指定名称时）
			parameter.initParameterNameDiscovery(this.parameterNameDiscoverer);

			// 步骤1: 在 providedArgs 中按类型查找匹配的参数
			args[i] = findProvidedArgument(parameter, providedArgs);
			if (args[i] != null) {
				continue;  // 找到直接提供的参数，跳过后续解析
			}

			// 步骤2: 检查是否有支持该参数的解析器
			if (!this.resolvers.supportsParameter(parameter)) {
				// 没有找到任何支持该参数类型的解析器，抛出异常
				throw new IllegalStateException(formatArgumentError(parameter, "No suitable resolver"));
			}

			// 步骤3: 使用参数解析器解析参数值
			try {
				args[i] = this.resolvers.resolveArgument(parameter, mavContainer, request, this.dataBinderFactory);
			}
			catch (Exception ex) {
				// 保留堆栈跟踪供后续使用，异常可能实际上可以解析和处理（例如通过 @ExceptionHandler）
				if (logger.isDebugEnabled()) {
					String exMsg = ex.getMessage();
					if (exMsg != null && !exMsg.contains(parameter.getExecutable().toGenericString())) {
						logger.debug(formatArgumentError(parameter, exMsg));
					}
				}
				throw ex;  // 重新抛出异常
			}
		}

		return args;
	}

	/**
	 * 使用给定的参数值调用处理器方法。
	 *
	 * <p>此方法是 Spring MVC 中反射调用控制器方法的核心入口，负责：
	 * <ul>
	 *   <li>处理 Kotlin 挂起函数的特殊调用</li>
	 *   <li>通过反射调用实际的控制器方法</li>
	 *   <li>处理参数非法异常（IllegalArgumentException）</li>
	 *   <li>解包调用目标异常（InvocationTargetException）</li>
	 * </ul>
	 *
	 * @param args 解析好的方法参数值数组，与目标方法参数一一对应
	 * @return 方法执行返回的结果，可能是 ModelAndView、数据对象、或 null
	 * @throws Exception 方法执行过程中抛出的异常（经过解包处理）
	 */
	@Nullable
	protected Object doInvoke(Object... args) throws Exception {
		// 获取桥接方法（Bridge Method）或实际方法
		// 桥接方法是编译器为泛型方法生成的方法，需要获取原始方法以便正确调用
		Method method = getBridgedMethod();

		try {
			// ==================== 1. Kotlin 挂起函数处理 ====================
			// 检查是否是 Kotlin 的挂起函数（suspend 函数）
			if (KotlinDetector.isSuspendingFunction(method)) {
				// 使用 CoroutinesUtils 工具类调用 Kotlin 协程挂起函数
				// 挂起函数的调用需要特殊的协程上下文和 Continuation 参数
				return CoroutinesUtils.invokeSuspendingFunction(method, getBean(), args);
			}

			// ==================== 2. 普通 Java 方法调用 ====================
			// 通过标准反射 API 调用目标方法
			// method.invoke(target, args) 是 Java 反射的核心调用方法
			return method.invoke(getBean(), args);
		}
		catch (IllegalArgumentException ex) {
			// ==================== 3. 参数非法异常处理 ====================
			// 当传入的参数类型或数量与方法签名不匹配时抛出此异常
			// 验证目标 Bean 是否与方法兼容（防止代理对象和方法不匹配）
			assertTargetBean(method, getBean(), args);

			// 构建错误信息
			String text = (ex.getMessage() != null ? ex.getMessage() : "Illegal argument");
			// 包装成 IllegalStateException 并重新抛出
			throw new IllegalStateException(formatInvokeError(text, args), ex);
		}
		catch (InvocationTargetException ex) {
			// ==================== 4. 调用目标异常解包处理 ====================
			// InvocationTargetException 是反射调用时，目标方法内部抛出的异常的包装器
			// 我们需要将其解包，取出真正的业务异常，以便 HandlerExceptionResolver 能正确处理

			// 获取被包装的真正异常
			Throwable targetException = ex.getTargetException();

			// 解包并根据异常类型重新抛出
			if (targetException instanceof RuntimeException) {
				// 运行时异常：直接抛出
				throw (RuntimeException) targetException;
			}
			else if (targetException instanceof Error) {
				// 错误（如 OutOfMemoryError）：直接抛出
				throw (Error) targetException;
			}
			else if (targetException instanceof Exception) {
				// 普通受检异常：直接抛出
				throw (Exception) targetException;
			}
			else {
				// 理论上不会走到这里（Throwable 只有 Exception、Error、其他）
				// 兜底处理：包装成 IllegalStateException 并抛出
				throw new IllegalStateException(formatInvokeError("Invocation failure", args), targetException);
			}
		}
	}

}
