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

package org.springframework.beans.factory;

/**
 * 初始化Bean接口 - Spring Bean生命周期中的初始化回调接口
 *
 * 功能：在BeanFactory设置完所有属性后，需要做出响应的Bean可以实现此接口
 *
 * 核心特点：
 * 1. 在Bean的所有属性被设置完成后调用
 * 2. 用于执行自定义初始化逻辑或验证所有必需属性是否已设置
 * 3. 是Spring Bean生命周期的关键节点之一
 *
 * 使用场景：
 * - 执行自定义初始化操作（如启动线程、建立连接）
 * - 验证依赖是否全部注入（检查必需属性）
 * - 对注入的值进行后处理或计算
 * - 注册到其他组件或监听器
 * - 启动后台任务或定时器
 *
 * 执行时机（完整生命周期）：
 * 1. Bean实例化（构造方法）
 * 2. 属性填充（依赖注入，setter方法）
 * 3. BeanNameAware.setBeanName()（如果实现）
 * 4. BeanClassLoaderAware.setBeanClassLoader()（如果实现）
 * 5. BeanFactoryAware.setBeanFactory()（如果实现）
 * 6. ★ afterPropertiesSet() ★（InitializingBean接口）
 * 7. 自定义init-method（如@PostConstruct或XML配置的init-method）
 * 8. Bean就绪，可以被使用
 *
 * 替代方案：
 * - @PostConstruct注解（推荐，JSR-250标准）
 * - XML配置的init-method属性
 * - @Bean(initMethod = "init")注解
 *
 * 注意：
 * - 如果同时使用多种方式，执行顺序为：
 *   @PostConstruct → afterPropertiesSet() → 自定义init-method
 * - 抛出异常会阻止Bean的创建，导致Bean创建失败
 *
 * @author Rod Johnson
 * @author Juergen Hoeller
 * @see DisposableBean  # 对应的销毁回调接口
 * @see org.springframework.beans.factory.config.BeanDefinition#getPropertyValues()
 * @see org.springframework.beans.factory.support.AbstractBeanDefinition#getInitMethodName()
 */
public interface InitializingBean {

	/**
	 * 在BeanFactory设置完所有Bean属性并满足BeanFactoryAware、ApplicationContextAware等之后调用
	 *
	 * 此时：
	 * - 所有属性值已注入完成
	 * - 各种Aware接口已回调
	 * - Bean还未完全初始化完成（但即将完成）
	 *
	 * 这个方法允许Bean实例：
	 * - 验证整体配置的完整性
	 * - 在所有属性设置完成后执行最终初始化
	 * - 检查必需属性是否都已设置
	 *
	 * 异常处理：
	 * - 抛出异常表示配置错误或初始化失败
	 * - Spring会捕获异常并阻止Bean的创建
	 * - 异常信息会包含在BeanCreationException中
	 *
	 * @throws Exception 如果配置错误（如未设置必需属性）或初始化因任何原因失败
	 */
	void afterPropertiesSet() throws Exception;

}
