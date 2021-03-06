/*
 * Copyright (c) 2010-2018. Axon Framework
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.axonframework.spring.eventsourcing;

import org.axonframework.eventhandling.DomainEventMessage;
import org.axonframework.eventsourcing.AbstractAggregateFactory;
import org.axonframework.eventsourcing.AggregateFactory;
import org.axonframework.eventsourcing.IncompatibleAggregateException;
import org.springframework.beans.factory.BeanNameAware;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Required;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;

import static java.lang.String.format;

/**
 * AggregateFactory implementation that uses Spring prototype beans to create new uninitialized instances of
 * Aggregates.
 *
 * @param <T> The type of aggregate generated by this aggregate factory
 * @author Allard Buijze
 * @since 1.2
 */
public class SpringPrototypeAggregateFactory<T> implements AggregateFactory<T>, InitializingBean,
                                                           ApplicationContextAware, BeanNameAware {

    private String prototypeBeanName;
    private ApplicationContext applicationContext;
    private String beanName;
    private Class<T> aggregateType;
    private AggregateFactory<T> delegate;

    @Override
    public T createAggregateRoot(String aggregateIdentifier, DomainEventMessage<?> firstEvent) {
        return delegate.createAggregateRoot(aggregateIdentifier, firstEvent);
    }

    @SuppressWarnings("unchecked")
    @Override
    public Class<T> getAggregateType() {
        if (aggregateType == null) {
            aggregateType = (Class<T>) applicationContext.getType(prototypeBeanName);
        }
        return aggregateType;
    }

    /**
     * Sets the name of the prototype bean this repository serves. Note that the the bean should have the prototype
     * scope and have a constructor that takes a single UUID argument.
     *
     * @param prototypeBeanName the name of the prototype bean this repository serves.
     */
    @Required
    public void setPrototypeBeanName(String prototypeBeanName) {
        this.prototypeBeanName = prototypeBeanName;
    }

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }

    @Override
    public void setBeanName(String beanName) {
        this.beanName = beanName;
    }

    @SuppressWarnings("unchecked")
    @Override
    public void afterPropertiesSet() {
        if (!applicationContext.isPrototype(prototypeBeanName)) {
            throw new IncompatibleAggregateException(
                    format("Cannot initialize repository '%s'. "
                                   + "The bean with name '%s' does not have the 'prototype' scope.",
                           beanName, prototypeBeanName));
        }
        this.delegate = new AbstractAggregateFactory<T>(getAggregateType()) {
            @Override
            protected T doCreateAggregate(String aggregateIdentifier, DomainEventMessage firstEvent) {
                return (T) applicationContext.getBean(prototypeBeanName);
            }

            @Override
            protected T postProcessInstance(T aggregate) {
                applicationContext.getAutowireCapableBeanFactory().configureBean(aggregate, prototypeBeanName);
                return aggregate;
            }
        };
    }
}
