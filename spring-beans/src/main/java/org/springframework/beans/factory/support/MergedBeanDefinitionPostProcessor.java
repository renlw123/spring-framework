/*
 * Copyright 2002-2018 the original author or authors.
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

package org.springframework.beans.factory.support;

import org.springframework.beans.factory.config.BeanPostProcessor;

/**
 * Post-processor callback interface for <i>merged</i> bean definitions at runtime.
 * {@link BeanPostProcessor} implementations may implement this sub-interface in order
 * to post-process the merged bean definition (a processed copy of the original bean
 * definition) that the Spring {@code BeanFactory} uses to create a bean instance.
 *
 * <p>The {@link #postProcessMergedBeanDefinition} method may for example introspect
 * the bean definition in order to prepare some cached metadata before post-processing
 * actual instances of a bean. It is also allowed to modify the bean definition but
 * <i>only</i> for definition properties which are actually intended for concurrent
 * modification. Essentially, this only applies to operations defined on the
 * {@link RootBeanDefinition} itself but not to the properties of its base classes.
 *
 * @author Juergen Hoeller
 * @since 2.5
 * @see org.springframework.beans.factory.config.ConfigurableBeanFactory#getMergedBeanDefinition
 */
public interface MergedBeanDefinitionPostProcessor extends BeanPostProcessor {

	/**
	 * 对指定 bean 的已合并 bean 定义进行后置处理。
	 *
	 * <p><b>调用时机：</b>
	 * 在 Bean 实例化之前，Spring 完成 BeanDefinition 合并后立即调用。
	 * 具体位置在 {@link AbstractAutowireCapableBeanFactory#doCreateBean} 方法中，
	 * 调用栈：doCreateBean → applyMergedBeanDefinitionPostProcessors → 本方法
	 *
	 * <p><b>核心作用：</b>
	 * 允许在 Bean 实例创建之前，检查和修改 BeanDefinition 中的元数据。
	 * 这是 Spring 内部实现注解驱动的核心扩展点之一。
	 *
	 * <p><b>典型应用场景：</b>
	 * <ul>
	 *   <li><b>@Autowired 解析</b>：AutowiredAnnotationBeanPostProcessor 通过此方法
	 *       扫描并缓存需要注入的字段和方法元数据</li>
	 *   <li><b>@Resource 解析</b>：CommonAnnotationBeanPostProcessor 在此解析
	 *       @Resource、@PostConstruct、@PreDestroy 等注解</li>
	 *   <li><b>@Scheduled 解析</b>：ScheduledAnnotationBeanPostProcessor 在此
	 *       扫描并注册定时任务信息</li>
	 *   <li><b>@EventListener 解析</b>：EventListenerMethodProcessor 在此
	 *       解析事件监听器方法</li>
	 * </ul>
	 *
	 * <p><b>重要特性：</b>
	 * <ul>
	 *   <li>此时 Bean 实例<b>尚未创建</b>，只能操作 BeanDefinition 元数据，不能操作实例</li>
	 *   <li>同一个 Bean 定义<b>只会被处理一次</b>（在第一次实例化时）</li>
	 *   <li>此方法<b>不能短路</b>后续的 Bean 创建流程（返回值类型为 void）</li>
	 *   <li>处理器可以在此方法中缓存元数据到 BeanDefinition 的 attributes 中，
	 *       供后续生命周期阶段（如依赖注入）使用</li>
	 * </ul>
	 *
	 * <p><b>执行顺序（在完整的 Bean 生命周期中）：</b>
	 * <pre>
	 * 1. InstantiationAwareBeanPostProcessor.postProcessBeforeInstantiation
	 *    （最早，可以返回代理对象短路创建流程）
	 *
	 * 2. <b>MergedBeanDefinitionPostProcessor.postProcessMergedBeanDefinition</b>
	 *    （当前位置，处理 BeanDefinition 元数据）
	 *
	 * 3. 构造函数实例化
	 *
	 * 4. InstantiationAwareBeanPostProcessor.postProcessAfterInstantiation
	 *
	 * 5. InstantiationAwareBeanPostProcessor.postProcessProperties
	 *    （或过时的 postProcessPropertyValues）
	 *
	 * 6. BeanPostProcessor.postProcessBeforeInitialization
	 *
	 * 7. @PostConstruct / afterPropertiesSet / init-method
	 *
	 * 8. BeanPostProcessor.postProcessAfterInitialization
	 * </pre>
	 *
	 * <p><b>代码示例：</b>
	 * <pre>{@code
	 * public class CustomMergedBeanDefinitionPostProcessor implements MergedBeanDefinitionPostProcessor {
	 *
	 *     private final Map<String, List<InjectedElement>> injectionMetadataCache = new ConcurrentHashMap<>();
	 *
	 *     @Override
	 *     public void postProcessMergedBeanDefinition(RootBeanDefinition beanDefinition,
	 *                                                   Class<?> beanType,
	 *                                                   String beanName) {
	 *         // 1. 扫描目标类型上的自定义注解
	 *         List<MyInjectedElement> elements = findInjectionElements(beanType);
	 *
	 *         if (!elements.isEmpty()) {
	 *             // 2. 将元数据缓存到 BeanDefinition 中，供依赖注入时使用
	 *             beanDefinition.setAttribute("my.injection.elements", elements);
	 *         }
	 *     }
	 *
	 *     private List<MyInjectedElement> findInjectionElements(Class<?> clazz) {
	 *         // 扫描字段、方法的逻辑...
	 *         return new ArrayList<>();
	 *     }
	 * }
	 * }</pre>
	 *
	 * @param beanDefinition 合并后的 bean 定义（RootBeanDefinition 类型），
	 *                       已经包含了从父定义继承的所有属性
	 * @param beanType 实际管理的 bean 实例类型（通常是 Class 对象，但在某些代理场景可能为 null）
	 * @param beanName bean 的名称
	 * @see AbstractAutowireCapableBeanFactory#applyMergedBeanDefinitionPostProcessors
	 * @see AutowiredAnnotationBeanPostProcessor#postProcessMergedBeanDefinition
	 * @see CommonAnnotationBeanPostProcessor#postProcessMergedBeanDefinition
	 */
	void postProcessMergedBeanDefinition(RootBeanDefinition beanDefinition, Class<?> beanType, String beanName);

	/**
	 * A notification that the bean definition for the specified name has been reset,
	 * and that this post-processor should clear any metadata for the affected bean.
	 * <p>The default implementation is empty.
	 * @param beanName the name of the bean
	 * @since 5.1
	 * @see DefaultListableBeanFactory#resetBeanDefinition
	 */
	default void resetBeanDefinition(String beanName) {
	}

}
