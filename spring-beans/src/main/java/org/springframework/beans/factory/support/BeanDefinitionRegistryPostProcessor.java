/*
 * Copyright 2002-2010 the original author or authors.
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

import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;

/**
 * Bean定义注册表后置处理器接口 - 标准BeanFactoryPostProcessor的扩展
 *
 * 功能：允许在常规BeanFactoryPostProcessor检测开始之前，注册更多的Bean定义
 *
 * 核心特点：
 * 1. 继承自BeanFactoryPostProcessor，拥有其所有能力
 * 2. 在BeanDefinition加载完成后、BeanFactoryPostProcessor执行前触发
 * 3. 主要用于动态注册额外的BeanDefinition
 * 4. 注册的BeanDefinition可以包含BeanFactoryPostProcessor实例
 *
 * 执行顺序（Spring容器启动流程）：
 * 1. 加载配置，解析Bean定义
 * 2. ★ BeanDefinitionRegistryPostProcessor.postProcessBeanDefinitionRegistry() ★
 *    - 可以在此动态注册新的BeanDefinition
 * 3. ★ BeanDefinitionRegistryPostProcessor.postProcessBeanFactory() ★
 *    - 继承自BeanFactoryPostProcessor的方法
 * 4. 其他普通BeanFactoryPostProcessor执行
 * 5. Bean实例化
 *
 * 典型应用场景：
 * - MyBatis的@MapperScan：动态注册Mapper接口的BeanDefinition
 * - Spring的@Configuration类处理：解析@Bean方法并注册
 * - 条件化注册Bean：根据环境或配置动态决定注册哪些Bean
 * - 扫描注解并自动注册：如@ComponentScan的实现
 * - 注册后置处理器本身
 *
 * @author Juergen Hoeller
 * @since 3.0.1
 * @see org.springframework.context.annotation.ConfigurationClassPostProcessor
 */
public interface BeanDefinitionRegistryPostProcessor extends BeanFactoryPostProcessor {

	/**
	 * 在标准初始化之后修改应用程序上下文内部的Bean定义注册表
	 *
	 * 执行时机：
	 * - 所有常规Bean定义已经加载完成
	 * - 但没有任何Bean实例被创建
	 * - 在postProcessBeanFactory方法之前调用
	 *
	 * 关键特性：
	 * 1. 可以添加额外的BeanDefinition到注册表
	 * 2. 添加的BeanDefinition可以包含BeanFactoryPostProcessor类型
	 * 3. 这些新注册的后置处理器会在当前阶段之后被执行
	 * 4. 可以修改或覆盖已存在的BeanDefinition
	 *
	 * 注意：
	 * - 参数是BeanDefinitionRegistry，提供了注册、移除、查询BeanDefinition的方法
	 * - 不是所有BeanFactory都实现了BeanDefinitionRegistry
	 * - 如果底层BeanFactory不支持注册，会抛出异常
	 *
	 * @param registry 应用程序上下文使用的Bean定义注册表
	 * @throws org.springframework.beans.BeansException 如果发生错误
	 */
	void postProcessBeanDefinitionRegistry(BeanDefinitionRegistry registry) throws BeansException;

}
