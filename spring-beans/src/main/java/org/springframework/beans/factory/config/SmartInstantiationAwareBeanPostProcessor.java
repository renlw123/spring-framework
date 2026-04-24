/*
 * Copyright 2002-2016 the original author or authors.
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

import java.lang.reflect.Constructor;

import org.springframework.beans.BeansException;
import org.springframework.lang.Nullable;

/**
 * {@link InstantiationAwareBeanPostProcessor} 接口的扩展，
 * 添加了用于预测处理后 Bean 最终类型的回调方法。
 *
 * <p><b>注意：</b> 这是一个特殊用途的接口，主要用于框架内部使用。
 * 通常情况下，应用程序提供的后置处理器应该直接实现普通的 {@link BeanPostProcessor}
 * 接口，或者继承 {@link InstantiationAwareBeanPostProcessorAdapter} 类。
 * 即使是在补丁版本中，也可能向此接口添加新方法。
 *
 * <p>这个接口解决了 Spring 容器在 Bean 实例化前的三个高级问题：
 * <ol>
 *   <li>类型预测 - 在实例化前知道 Bean 的最终类型（如 AOP 代理类型）</li>
 *   <li>构造器选择 - 当 Bean 有多个构造器时，选择合适的构造器实例化</li>
 *   <li>早期引用暴露 - 处理循环依赖时，提前暴露 Bean 的引用（可能是代理对象）</li>
 * </ol>
 *
 * @author Juergen Hoeller
 * @since 2.0.3
 * @see InstantiationAwareBeanPostProcessorAdapter
 */
public interface SmartInstantiationAwareBeanPostProcessor extends InstantiationAwareBeanPostProcessor {

	/**
	 * 预测最终将从该处理器的 {@link #postProcessBeforeInstantiation} 回调中返回的 Bean 类型
	 *
	 * <p>这个方法的主要作用是在 Bean 实例化之前，让 Spring 知道 Bean 的最终类型。
	 * 这在以下场景特别有用：
	 * <ul>
	 *   <li>AOP 代理：虽然原始类是 UserService，但最终返回的是代理对象</li>
	 *   <li>FactoryBean：预测 FactoryBean 创建的对象类型</li>
	 *   <li>动态代理：MyBatis Mapper 接口的动态代理类型预测</li>
	 * </ul>
	 *
	 * <p>调用时机：在 {@link #postProcessBeforeInstantiation} 之前，
	 * 当 Spring 需要知道 Bean 类型进行依赖注入匹配时调用
	 *
	 * <p>默认实现返回 {@code null}，表示无法预测
	 *
	 * @param beanClass Bean 的原始类（未经任何处理的原始类）
	 * @param beanName Bean 的名称
	 * @return Bean 的预测类型，如果无法预测则返回 {@code null}
	 * @throws org.springframework.beans.BeansException 如果发生错误
	 */
	@Nullable
	default Class<?> predictBeanType(Class<?> beanClass, String beanName) throws BeansException {
		return null;
	}

	/**
	 * 确定用于给定 Bean 的候选构造器
	 *
	 * <p>当 Bean 有多个构造器时，Spring 需要知道应该使用哪个构造器来实例化 Bean。
	 * 这个方法允许后置处理器指定候选构造器列表。
	 *
	 * <p>典型使用场景：
	 * <ul>
	 *   <li>{@code @Autowired} 注解处理：返回带 @Autowired 注解的构造器</li>
	 *   <li>多构造器选择：根据环境配置选择不同的构造器</li>
	 *   <li>参数最多的构造器：Spring 默认会选择参数最多的构造器</li>
	 * </ul>
	 *
	 * <p>调用时机：在 {@link #postProcessBeforeInstantiation} 之后，
	 * 在真正创建 Bean 实例之前，用于确定使用哪个构造器
	 *
	 * <p>返回值说明：
	 * <ul>
	 *   <li>返回非空数组：Spring 将使用指定的构造器进行实例化</li>
	 *   <li>返回空数组：表示没有可用的构造器（如接口类型）</li>
	 *   <li>返回 {@code null}：让 Spring 使用默认的构造器解析逻辑</li>
	 * </ul>
	 *
	 * @param beanClass Bean 的原始类（永远不会为 {@code null}）
	 * @param beanName Bean 的名称
	 * @return 候选构造器数组，如果没有指定则返回 {@code null}
	 * @throws org.springframework.beans.BeansException 如果发生错误
	 */
	@Nullable
	default Constructor<?>[] determineCandidateConstructors(Class<?> beanClass, String beanName)
			throws BeansException {

		return null;
	}

	/**
	 * 获取指定 Bean 的早期访问引用，通常用于解决循环引用问题
	 *
	 * <p>这个回调允许后置处理器提前暴露包装对象 - 即在目标 Bean 实例完全初始化之前。
	 * 暴露的对象应该等同于 {@link #postProcessBeforeInitialization} /
	 * {@link #postProcessAfterInitialization} 否则会暴露的对象。
	 *
	 * <p>重要说明：
	 * <ul>
	 *   <li>此方法返回的对象将作为 Bean 引用被使用，除非后置处理器从后续回调中返回了不同的包装器</li>
	 *   <li>如果已经为受影响的 Bean 构建了包装器，它默认将作为最终的 Bean 引用暴露</li>
	 *   <li>主要用于解决循环依赖 + AOP 代理的场景</li>
	 * </ul>
	 *
	 * <p>典型使用场景：
	 * <ul>
	 *   <li>循环依赖 + AOP 代理：提前暴露代理对象而不是原始对象</li>
	 *   <li>避免循环依赖中的代理不一致问题</li>
	 * </ul>
	 *
	 * <p>调用时机：在 Bean 实例化后、属性填充前，当检测到循环依赖时调用
	 *
	 * <p>默认实现按原样返回给定的 {@code bean}
	 *
	 * @param bean 原始的 Bean 实例（已经实例化但可能未完全初始化）
	 * @param beanName Bean 的名称
	 * @return 作为 Bean 引用暴露的对象（默认情况下返回传入的 Bean 实例）
	 * @throws org.springframework.beans.BeansException 如果发生错误
	 */
	default Object getEarlyBeanReference(Object bean, String beanName) throws BeansException {
		return bean;
	}
}
