/*
 * Copyright 2002-2019 the original author or authors.
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

package org.springframework.beans.factory.config;

import org.springframework.beans.BeansException;
import org.springframework.lang.Nullable;

/**
 * Bean后置处理器接口 - Spring框架中最重要的扩展接口之一
 *
 * 功能：允许在Bean实例化完成后、初始化前后对Bean实例进行自定义修改
 *
 * 核心特点：
 * 1. 操作对象是Bean实例（已创建的对象），而不是BeanDefinition
 * 2. 在Spring容器管理Bean的生命周期中提供两个扩展点
 * 3. 可以对Bean进行包装、代理、检查、修改等操作
 * 4. 所有BeanPostProcessor都会被应用到容器中的每个Bean实例上
 *
 * 典型应用场景：
 * - 代理生成：@Transactional、@Async等注解的代理实现
 * - 属性注入：@Autowired、@Value等注解的处理
 * - 方法拦截：添加AOP切面逻辑
 * - 初始化检查：验证Bean的合法性
 * - 对象增强：添加额外的功能或属性
 *
 * 执行时机（Bean生命周期）：
 * 1. 实例化Bean（构造方法）
 * 2. 填充属性（依赖注入）
 * 3. ★ postProcessBeforeInitialization（初始化前）★
 * 4. 初始化（InitializingBean.afterPropertiesSet + init-method）
 * 5. ★ postProcessAfterInitialization（初始化后）★
 * 6. Bean就绪，可以被使用
 * 7. 销毁（DisposableBean.destroy + destroy-method）
 *
 * @author Juergen Hoeller
 * @author Sam Brannen
 * @since 10.10.2003
 * @see InstantiationAwareBeanPostProcessor  # 扩展：在实例化前后处理
 * @see DestructionAwareBeanPostProcessor    # 扩展：在销毁时处理
 * @see ConfigurableBeanFactory#addBeanPostProcessor  # 编程式注册
 * @see BeanFactoryPostProcessor  # 区别：处理BeanDefinition，而非实例
 */
public interface BeanPostProcessor {

	/**
	 * 在Bean初始化回调之前应用此BeanPostProcessor
	 *
	 * 执行时机：
	 * - 属性填充（依赖注入）已完成
	 * - 初始化方法（afterPropertiesSet、init-method）尚未调用
	 *
	 * 可以做：
	 * - 修改Bean的属性值
	 * - 检查Bean是否满足某种规范
	 * - 返回代理对象（但通常在after中做）
	 * - 返回null会阻止后续后置处理器执行
	 *
	 * 注意：如果返回null，后续的BeanPostProcessor将不会被调用
	 *
	 * @param bean 新的bean实例（已经填充了属性值）
	 * @param beanName bean的名称
	 * @return 要使用的bean实例（原始实例或包装后的实例）
	 * @throws org.springframework.beans.BeansException 如果发生错误
	 * @see org.springframework.beans.factory.InitializingBean#afterPropertiesSet
	 */
	@Nullable
	default Object postProcessBeforeInitialization(Object bean, String beanName) throws BeansException {
		return bean;  // 默认实现：直接返回原bean，不做任何处理
	}

	/**
	 * 在Bean初始化回调之后应用此BeanPostProcessor
	 *
	 * 执行时机：
	 * - 初始化方法（afterPropertiesSet、init-method）已完成
	 * - Bean已经完全初始化，即将被使用
	 *
	 * 典型用途：
	 * - 生成代理对象（如@Transactional、@Async）
	 * - 返回包装对象增强功能
	 * - 注册监控或监听器
	 *
	 * 特殊说明：
	 * 1. 对于FactoryBean，这个回调会调用两次：
	 *    - 第一次：FactoryBean实例本身
	 *    - 第二次：FactoryBean创建的Bean实例
	 *    可以通过 instanceof 判断来区分处理
	 *
	 * 2. 如果InstantiationAwareBeanPostProcessor的postProcessBeforeInstantiation
	 *    返回了非null对象（短路处理），此方法仍然会被调用
	 *
	 * @param bean 新的bean实例
	 * @param beanName bean的名称
	 * @return 要使用的bean实例（原始实例或包装后的实例）
	 * @throws org.springframework.beans.BeansException 如果发生错误
	 * @see org.springframework.beans.factory.InitializingBean#afterPropertiesSet
	 * @see org.springframework.beans.factory.FactoryBean
	 */
	@Nullable
	default Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
		return bean;  // 默认实现：直接返回原bean
	}

}
