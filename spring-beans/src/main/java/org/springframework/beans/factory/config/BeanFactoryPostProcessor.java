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

/**
 * Bean工厂后置处理器接口 - Spring容器扩展机制的核心接口之一
 *
 * 功能：允许在Spring容器标准初始化完成后、但任何bean实例化之前，
 * 修改应用程序上下文内部的bean工厂
 *
 * 执行时机：
 * 1. 所有BeanDefinition已经加载完成
 * 2. 所有Bean定义已经注册到BeanFactory中
 * 3. 还没有任何Bean实例被创建（单例Bean还未实例化）
 *
 * 主要用途：
 * - 修改BeanDefinition的属性值
 * - 添加新的BeanDefinition
 * - 替换Bean定义中的占位符（如${...}）
 * - 注册自定义的作用域
 * - 修改Bean的元数据（如@Configuration类的处理）
 *
 * @FunctionalInterface 表示这是一个函数式接口，可以使用Lambda表达式
 */
@FunctionalInterface
public interface BeanFactoryPostProcessor {

	/**
	 * 在标准初始化之后修改应用程序上下文内部的bean工厂
	 *
	 * 此时：
	 * - 所有Bean定义已经加载完成（BeanDefinition对象已存在）
	 * - 但还没有任何Bean实例被创建（单例Bean还未初始化）
	 * - 这允许覆盖或添加属性，甚至可以影响到那些即将被提前初始化的bean
	 *
	 * 注意：这个方法在Spring容器启动过程中只执行一次
	 *
	 * @param beanFactory 应用程序上下文使用的bean工厂（ConfigurableListableBeanFactory类型）
	 * @throws org.springframework.beans.BeansException 处理过程中的异常
	 */
	void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) throws BeansException;

}
