/*
 * 版权所有 2002-2020 原作者
 *
 * 根据 Apache 许可证 2.0 版（"许可证"）授权；
 * 除非遵守许可证，否则您不得使用此文件。
 * 您可以在以下网址获取许可证副本：
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * 除非适用法律要求或书面同意，否则按"原样"分发的软件
 * 不附带任何明示或暗示的担保或条件。
 * 请参阅许可证了解具体语言的权限和限制。
 */

package org.springframework.web;

import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.ServiceLoader;
import java.util.Set;

import javax.servlet.ServletContainerInitializer;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.annotation.HandlesTypes;

import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import org.springframework.lang.Nullable;
import org.springframework.util.ReflectionUtils;

/**
 *
 *
 * Servlet 3.0+ 容器启动
 *          │
 *          ▼
 * ┌─────────────────────────────────────────────────────────────┐
 * │  容器通过 JAR Services API 扫描                               │
 * │  META-INF/services/javax.servlet.ServletContainerInitializer│
 * │  发现 spring-web 模块中的 SpringServletContainerInitializer │
 * └─────────────────────────────────────────────────────────────┘
 *          │
 *          ▼
 * ┌─────────────────────────────────────────────────────────────┐
 * │  容器调用 onStartup() 方法                                    │
 * │  参数:                                                       │
 * │  - webAppInitializerClasses: 所有实现了 WebApplicationInitializer│
 * │    的类（通过 @HandlesTypes 扫描）                           │
 * │  - servletContext: Servlet 上下文                           │
 * └─────────────────────────────────────────────────────────────┘
 *          │
 *          ▼
 * ┌─────────────────────────────────────────────────────────────┐
 * │  实例化所有 WebApplicationInitializer                        │
 * │  • 过滤掉接口和抽象类                                         │
 * │  • 通过反射创建实例                                           │
 * │  • 按 @Order 排序                                           │
 * └─────────────────────────────────────────────────────────────┘
 *          │
 *          ▼
 * ┌─────────────────────────────────────────────────────────────┐
 * │  依次调用每个 initializer.onStartup(servletContext)         │
 * │  • 注册 DispatcherServlet                                    │
 * │  • 注册 ContextLoaderListener                                │
 * │  • 注册 Filter、Listener 等                                  │
 * └─────────────────────────────────────────────────────────────┘
 *
 * spi => spring-web/src/main/resources/META-INF/services/javax.servlet.ServletContainerInitializer
 *
 *
 *
 * Servlet 3.0 的 {@link ServletContainerInitializer} 实现，旨在支持使用 Spring 的
 * {@link WebApplicationInitializer} SPI 进行基于代码的 Servlet 容器配置，
 * 以替代（或与传统 {@code web.xml} 方式结合使用）。
 *
 * <h2>运作机制</h2>
 * 当 {@code spring-web} 模块 JAR 存在于类路径中时，任何符合 Servlet 3.0 规范的容器
 * 都会在容器启动期间加载并实例化此类，并调用其 {@link #onStartup} 方法。
 * 这是通过 JAR Services API 的 {@link ServiceLoader#load(Class)} 方法实现的，
 * 该方法会检测 {@code spring-web} 模块的
 * {@code META-INF/services/javax.servlet.ServletContainerInitializer} 服务提供者配置文件。
 *
 * <h3>与 {@code web.xml} 配合使用</h3>
 * Web 应用程序可以通过以下方式限制 Servlet 容器在启动时执行的类路径扫描量：
 * <ul>
 *   <li>使用 {@code web.xml} 中的 {@code metadata-complete} 属性，控制 Servlet 注解的扫描</li>
 *   <li>使用 {@code web.xml} 中的 {@code <absolute-ordering>} 元素，控制哪些 Web 片段（即 JAR）允许执行
 *       {@code ServletContainerInitializer} 扫描</li>
 * </ul>
 * 使用此功能时，可以通过将 "spring_web" 添加到 {@code web.xml} 中的命名 Web 片段列表来启用
 * {@link SpringServletContainerInitializer}。
 *
 * <h2>与 Spring 的 {@code WebApplicationInitializer} 的关系</h2>
 * Spring 的 {@code WebApplicationInitializer} SPI 只包含一个方法：
 * {@link WebApplicationInitializer#onStartup(ServletContext)}。
 * 方法签名刻意与 {@link ServletContainerInitializer#onStartup(Set, ServletContext)} 相似：
 * 简而言之，{@code SpringServletContainerInitializer} 负责实例化用户定义的
 * {@code WebApplicationInitializer} 实现，并将 {@code ServletContext} 委托给它们。
 * 然后，每个 {@code WebApplicationInitializer} 负责执行初始化 {@code ServletContext}
 * 的实际工作。
 *
 * <h2>通用说明</h2>
 * 通常，此类应被视为对更重要和面向用户的 {@code WebApplicationInitializer} SPI 的
 * <em>支持性基础设施</em>。利用此容器初始化器也是完全<em>可选的</em>：
 * 虽然此初始化器在所有 Servlet 3.0+ 运行时中都会被加载和调用，但用户仍可选择是否
 * 将 {@code WebApplicationInitializer} 实现放在类路径上。如果没有检测到
 * {@code WebApplicationInitializer} 类型，此容器初始化器将不会产生任何效果。
 *
 * <p>注意，使用此容器初始化器和 {@code WebApplicationInitializer} 并不与 Spring MVC
 * "绑定"，除了这些类型是在 {@code spring-web} 模块 JAR 中分发的事实之外。
 * 相反，它们可以被视为通用的，能够方便地促进基于代码的 {@code ServletContext} 配置。
 * 换句话说，任何 Servlet、Listener 或 Filter 都可以在 {@code WebApplicationInitializer}
 * 中注册，而不仅仅是 Spring MVC 特定的组件。
 *
 * <p>此类既不是为扩展而设计，也不打算被扩展。它应被视为内部类型，
 * 而 {@code WebApplicationInitializer} 是对外的 SPI。
 *
 * <h2>另请参阅</h2>
 * 有关示例和详细使用建议，请参阅 {@link WebApplicationInitializer} Javadoc。
 *
 * @author Chris Beams
 * @author Juergen Hoeller
 * @author Rossen Stoyanchev
 * @since 3.1
 * @see #onStartup(Set, ServletContext)
 * @see WebApplicationInitializer
 */
@HandlesTypes(WebApplicationInitializer.class)
public class SpringServletContainerInitializer implements ServletContainerInitializer {

	/**
	 * 将 {@code ServletContext} 委托给应用程序类路径上存在的任何
	 * {@link WebApplicationInitializer} 实现。
	 *
	 * <p>由于此类声明了 {@code @HandlesTypes(WebApplicationInitializer.class)}，
	 * Servlet 3.0+ 容器会自动扫描类路径中 Spring 的 {@code WebApplicationInitializer}
	 * 接口的实现，并将所有此类类型提供给此方法的 {@code webAppInitializerClasses} 参数。
	 *
	 * <p>如果在类路径上没有找到 {@code WebApplicationInitializer} 实现，
	 * 此方法实际上是一个空操作。将发出 INFO 级别的日志消息，通知用户
	 * {@code ServletContainerInitializer} 确实已被调用，但没有找到任何
	 * {@code WebApplicationInitializer} 实现。
	 *
	 * <p>假设检测到一个或多个 {@code WebApplicationInitializer} 类型，
	 * 它们将被实例化（并且如果存在 @{@link org.springframework.core.annotation.Order @Order}
	 * 注解或实现了 {@link org.springframework.core.Ordered Ordered} 接口，则会进行<em>排序</em>）。
	 * 然后，将在每个实例上调用 {@link WebApplicationInitializer#onStartup(ServletContext)}
	 * 方法，委托 {@code ServletContext}，以便每个实例可以注册和配置 Servlet（如 Spring 的
	 * {@code DispatcherServlet}）、监听器（如 Spring 的 {@code ContextLoaderListener}）
	 * 或任何其他 Servlet API 组件（如 Filter）。
	 *
	 * @param webAppInitializerClasses 在应用程序类路径上找到的所有
	 *                                 {@link WebApplicationInitializer} 实现类
	 * @param servletContext           要初始化的 Servlet 上下文
	 * @throws ServletException 如果初始化过程中发生错误
	 * @see WebApplicationInitializer#onStartup(ServletContext)
	 * @see AnnotationAwareOrderComparator
	 */
	@Override
	public void onStartup(@Nullable Set<Class<?>> webAppInitializerClasses, ServletContext servletContext)
			throws ServletException {

		// ========== 1. 初始化存储列表 ==========
		List<WebApplicationInitializer> initializers = Collections.emptyList();

		// ========== 2. 遍历容器扫描到的所有 WebApplicationInitializer 类 ==========
		if (webAppInitializerClasses != null) {
			initializers = new ArrayList<>(webAppInitializerClasses.size());

			for (Class<?> waiClass : webAppInitializerClasses) {
				// 防御性检查：某些 Servlet 容器可能会提供无效的类，
				// 无论 @HandlesTypes 指定了什么...
				// ★ 过滤条件：
				//   - 不是接口
				//   - 不是抽象类
				//   - 是 WebApplicationInitializer 的派生类
				if (!waiClass.isInterface() &&
						!Modifier.isAbstract(waiClass.getModifiers()) &&
						WebApplicationInitializer.class.isAssignableFrom(waiClass)) {

					try {
						// ★ 通过反射获取可访问的构造函数并实例化
						initializers.add((WebApplicationInitializer)
								ReflectionUtils.accessibleConstructor(waiClass).newInstance());
					} catch (Throwable ex) {
						throw new ServletException("Failed to instantiate WebApplicationInitializer class", ex);
					}
				}
			}
		}

		// ========== 3. 如果没有找到任何实现类，记录日志并返回 ==========
		if (initializers.isEmpty()) {
			servletContext.log("No Spring WebApplicationInitializer types detected on classpath");
			return;
		}

		// ========== 4. 排序并依次执行 ==========
		servletContext.log(initializers.size() + " Spring WebApplicationInitializers detected on classpath");

		// ★ 排序（支持 @Order 注解和 Ordered 接口）
		AnnotationAwareOrderComparator.sort(initializers);

		// ★ 依次调用每个初始化器的 onStartup 方法
		for (WebApplicationInitializer initializer : initializers) {
			initializer.onStartup(servletContext);
		}
	}
}