/*
 * Copyright 2002-2017 the original author or authors.
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

package org.springframework.aop.aspectj.annotation;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import org.springframework.aop.Advisor;
import org.springframework.aop.aspectj.autoproxy.AspectJAwareAdvisorAutoProxyCreator;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.lang.Nullable;
import org.springframework.util.Assert;

/**
 * {@link AspectJAwareAdvisorAutoProxyCreator} subclass that processes all AspectJ
 * annotation aspects in the current application context, as well as Spring Advisors.
 *
 * <p>Any AspectJ annotated classes will automatically be recognized, and their
 * advice applied if Spring AOP's proxy-based model is capable of applying it.
 * This covers method execution joinpoints.
 *
 * <p>If the &lt;aop:include&gt; element is used, only @AspectJ beans with names matched by
 * an include pattern will be considered as defining aspects to use for Spring auto-proxying.
 *
 * <p>Processing of Spring Advisors follows the rules established in
 * {@link org.springframework.aop.framework.autoproxy.AbstractAdvisorAutoProxyCreator}.
 *
 * @author Rod Johnson
 * @author Juergen Hoeller
 * @since 2.0
 * @see org.springframework.aop.aspectj.annotation.AspectJAdvisorFactory
 */
@SuppressWarnings("serial")
public class AnnotationAwareAspectJAutoProxyCreator extends AspectJAwareAdvisorAutoProxyCreator {

	@Nullable
	private List<Pattern> includePatterns;

	@Nullable
	private AspectJAdvisorFactory aspectJAdvisorFactory;

	@Nullable
	private BeanFactoryAspectJAdvisorsBuilder aspectJAdvisorsBuilder;


	/**
	 * Set a list of regex patterns, matching eligible @AspectJ bean names.
	 * <p>Default is to consider all @AspectJ beans as eligible.
	 */
	public void setIncludePatterns(List<String> patterns) {
		this.includePatterns = new ArrayList<>(patterns.size());
		for (String patternText : patterns) {
			this.includePatterns.add(Pattern.compile(patternText));
		}
	}

	public void setAspectJAdvisorFactory(AspectJAdvisorFactory aspectJAdvisorFactory) {
		Assert.notNull(aspectJAdvisorFactory, "AspectJAdvisorFactory must not be null");
		this.aspectJAdvisorFactory = aspectJAdvisorFactory;
	}

	/**
	 * 初始化 BeanFactory，设置 AspectJ 相关的组件。
	 *
	 * <p>该方法在代理创建器被创建后调用，用于初始化：
	 * <ul>
	 *   <li>aspectJAdvisorFactory：切面通知工厂，负责解析 @AspectJ 注解的方法</li>
	 *   <li>aspectJAdvisorsBuilder：切面通知构建器，负责从 BeanFactory 中提取所有切面</li>
	 * </ul>
	 *
	 * AbstractApplicationContext.refresh()
	 *     │
	 *     ├── 1. invokeBeanFactoryPostProcessors()    ← 处理 BeanFactoryPostProcessor
	 *     │
	 *     ├── 2. registerBeanPostProcessors()         ← ★ 这里注册 BeanPostProcessor
	 *     │       │
	 *     │       └── PostProcessorRegistrationDelegate.registerBeanPostProcessors()
	 *     │               │
	 *     │               ├── 创建所有 BeanPostProcessor 的实例
	 *     │               │
	 *     │               └── 对于每个 PriorityOrdered BeanPostProcessor：
	 *     │                     │
	 *     │                     └── beanFactory.addBeanPostProcessor(processor)
	 *     │                                 │
	 *     │                                 └── AbstractAutoProxyCreator.setBeanFactory()
	 *     │                                           │
	 *     │                                           └── ★ initBeanFactory() 被调用
	 *     │
	 *     ├── 3. finishBeanFactoryInitialization()    ← 实例化所有单例 Bean
	 *     │
	 *     └── 4. finishRefresh()
	 *
	 * @param beanFactory Spring 的 BeanFactory，用于获取和管理 Bean
	 */
	@Override
	protected void initBeanFactory(ConfigurableListableBeanFactory beanFactory) {// 在初始化期间initializeBean准备好了要用的基本对象ReflectiveAspectJAdvisorFactory
																				 // + BeanFactoryAspectJAdvisorsBuilderAdapter
		// 调用父类初始化方法（AbstractAutoProxyCreator）
		super.initBeanFactory(beanFactory);

		// 如果切面通知工厂尚未创建，则创建一个新的
		// ReflectiveAspectJAdvisorFactory 通过反射解析 @AspectJ 注解
		if (this.aspectJAdvisorFactory == null) {
			this.aspectJAdvisorFactory = new ReflectiveAspectJAdvisorFactory(beanFactory);
		}

		// 创建切面通知构建器适配器
		// 该构建器负责扫描容器中所有带有 @Aspect 注解的 Bean，
		// 并将其中的 @Before、@After、@Around 等方法解析为 Advisor 对象
		this.aspectJAdvisorsBuilder =
				new BeanFactoryAspectJAdvisorsBuilderAdapter(beanFactory, this.aspectJAdvisorFactory);
	}


	/**
	 * 查找所有候选的切面通知（Advisor）。
	 *
	 * <p>该方法会收集两类通知：
	 * <ol>
	 *   <li>Spring 原生 Advisor：通过父类的查找规则找到的普通 Advisor</li>
	 *   <li>AspectJ 切面通知：将所有 @AspectJ 切面类中的通知方法解析为 Advisor</li>
	 * </ol>
	 *
	 * @return 所有候选通知的列表（包括 Spring Advisor 和 AspectJ 通知）
	 */
	@Override
	protected List<Advisor> findCandidateAdvisors() {
		// ========== 第一部分：查找 Spring 原生 Advisor ==========
		// 调用父类方法，获取通过其他方式注册的 Advisor
		// 例如：实现 Advisor 接口的 Bean、通过 XML 配置的切面等
		List<Advisor> advisors = super.findCandidateAdvisors();

		// ========== 第二部分：构建 AspectJ 切面通知 ==========
		// 如果切面通知构建器存在，则解析所有 @AspectJ 切面类
		if (this.aspectJAdvisorsBuilder != null) {
			// 将 @AspectJ 切面中的方法转换为 Advisor 并添加到列表中
			// 例如：@Before、@After、@Around、@AfterReturning、@AfterThrowing
			advisors.addAll(this.aspectJAdvisorsBuilder.buildAspectJAdvisors());
		}

		return advisors;
	}

	/**
	 * 判断一个类是否为基础设施类（不应被代理）。
	 *
	 * <p>基础设施类包括：
	 * <ul>
	 *   <li>Spring 内部的基础设施类（如 BeanPostProcessor、AOP 相关类）</li>
	 *   <li>{@link Aspect} 切面类本身</li>
	 * </ul>
	 *
	 * <p><b>为什么切面类不应被代理？</b>
	 * <pre>
	 * 如果切面类被代理，当切面实现了某些接口（如 Ordered）时：
	 * 1. JDK 动态代理会基于接口生成代理对象
	 * 2. 代理对象只包含接口中定义的方法
	 * 3. 切面中的通知方法（@Before 等）不在接口中
	 * 4. 运行时调用通知方法会失败
	 *
	 * 因此，切面类本身不需要被 AOP 增强，也不应该被代理。
	 * </pre>
	 *
	 * @param beanClass 要检查的 Bean 的 Class
	 * @return true 表示是基础设施类，不应被代理；false 表示可能需要被代理
	 */
	@Override
	protected boolean isInfrastructureClass(Class<?> beanClass) {
		// 满足以下任一条件即为基础设施类：
		// 1. 父类判定为基础设施类（如 AopInfrastructureBean 的实现类）
		// 2. 该类是一个切面类（带有 @Aspect 注解）
		return (super.isInfrastructureClass(beanClass) ||
				(this.aspectJAdvisorFactory != null && this.aspectJAdvisorFactory.isAspect(beanClass)));
	}

	/**
	 * Check whether the given aspect bean is eligible for auto-proxying.
	 * <p>If no &lt;aop:include&gt; elements were used then "includePatterns" will be
	 * {@code null} and all beans are included. If "includePatterns" is non-null,
	 * then one of the patterns must match.
	 */
	protected boolean isEligibleAspectBean(String beanName) {
		if (this.includePatterns == null) {
			return true;
		}
		else {
			for (Pattern pattern : this.includePatterns) {
				if (pattern.matcher(beanName).matches()) {
					return true;
				}
			}
			return false;
		}
	}


	/**
	 * Subclass of BeanFactoryAspectJAdvisorsBuilderAdapter that delegates to
	 * surrounding AnnotationAwareAspectJAutoProxyCreator facilities.
	 */
	private class BeanFactoryAspectJAdvisorsBuilderAdapter extends BeanFactoryAspectJAdvisorsBuilder {

		public BeanFactoryAspectJAdvisorsBuilderAdapter(
				ListableBeanFactory beanFactory, AspectJAdvisorFactory advisorFactory) {

			super(beanFactory, advisorFactory);
		}

		@Override
		protected boolean isEligibleBean(String beanName) {
			return AnnotationAwareAspectJAutoProxyCreator.this.isEligibleAspectBean(beanName);
		}
	}

}
