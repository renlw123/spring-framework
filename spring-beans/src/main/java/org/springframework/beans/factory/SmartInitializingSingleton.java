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

package org.springframework.beans.factory;

/**
 * Callback interface triggered at the end of the singleton pre-instantiation phase
 * during {@link BeanFactory} bootstrap. This interface can be implemented by
 * singleton beans in order to perform some initialization after the regular
 * singleton instantiation algorithm, avoiding side effects with accidental early
 * initialization (e.g. from {@link ListableBeanFactory#getBeansOfType} calls).
 * In that sense, it is an alternative to {@link InitializingBean} which gets
 * triggered right at the end of a bean's local construction phase.
 *
 * <p>This callback variant is somewhat similar to
 * {@link org.springframework.context.event.ContextRefreshedEvent} but doesn't
 * require an implementation of {@link org.springframework.context.ApplicationListener},
 * with no need to filter context references across a context hierarchy etc.
 * It also implies a more minimal dependency on just the {@code beans} package
 * and is being honored by standalone {@link ListableBeanFactory} implementations,
 * not just in an {@link org.springframework.context.ApplicationContext} environment.
 *
 * <p><b>NOTE:</b> If you intend to start/manage asynchronous tasks, preferably
 * implement {@link org.springframework.context.Lifecycle} instead which offers
 * a richer model for runtime management and allows for phased startup/shutdown.
 *
 * @author Juergen Hoeller
 * @since 4.1
 * @see org.springframework.beans.factory.config.ConfigurableListableBeanFactory#preInstantiateSingletons()
 */
public interface SmartInitializingSingleton {

	/**
	 * 在单例预实例化阶段的最后时刻被调用。
	 *
	 * <p><b>执行时机：</b>
	 * 在 Spring 容器完成所有单例 Bean 的创建之后，立即执行。
	 * 这是 Spring 容器启动过程中非常靠后的一个扩展点。
	 *
	 * <p><b>核心保证：</b>
	 * <ul>
	 *   <li>所有常规单例 Bean 都已经完成实例化、属性注入、初始化</li>
	 *   <li>此时调用 {@link ListableBeanFactory#getBeansOfType} 不会触发意外的副作用</li>
	 * </ul>
	 *
	 * <p><b>典型应用场景：</b>
	 * <ul>
	 *   <li>初始化和预热缓存系统</li>
	 *   <li>启动后台任务/定时任务（如 @Scheduled 的初始化）</li>
	 *   <li>在所有 Bean 就绪后执行某些全局初始化逻辑</li>
	 *   <li>扫描并注册处理器（如 Spring 的 EventListenerMethodProcessor）</li>
	 * </ul>
	 *
	 * <p><b>⚠️ 注意事项：</b>
	 * <ul>
	 *   <li><b>不会触发</b>：懒加载的单例 Bean（在容器启动后通过 getBean() 创建的）</li>
	 *   <li><b>不会触发</b>：原型作用域的 Bean</li>
	 *   <li><b>谨慎使用</b>：只用于那些确实需要在启动时执行的业务逻辑</li>
	 * </ul>
	 *
	 * <p><b>Spring 内部使用示例：</b>
	 * <pre>
	 * // EventListenerMethodProcessor 实现此接口
	 * public class EventListenerMethodProcessor implements SmartInitializingSingleton {
	 *     @Override
	 *     public void afterSingletonsInstantiated() {
	 *         // 在所有 Bean 创建完成后，扫描并注册 @EventListener 方法
	 *         for (String beanName : beanFactory.getBeanNamesForType(Object.class)) {
	 *             processBean(beanName, beanFactory.getBean(beanName));
	 *         }
	 *     }
	 * }
	 * </pre>
	 *
	 * @see SmartInitializingSingleton
	 * @see ListableBeanFactory#getBeansOfType
	 */
	void afterSingletonsInstantiated();

}
