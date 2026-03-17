/*
 * Copyright 2024-2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 */
package com.alibaba.assistant.agent.start.migration;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.scheduling.annotation.SchedulingConfigurer;

import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MigrationSchedulingConfigurationTest {
    @Test
    void shouldEnableSchedulingBeansUnderMigrationProfile() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().setActiveProfiles("migration");
            context.register(MigrationSchedulingConfiguration.class);
            context.refresh();
            assertNotNull(context.getBean(TaskScheduler.class));
            assertNotNull(context.getBean("migrationExecutionExecutor"));
            assertNotNull(context.getBean(ScheduledAnnotationBeanPostProcessor.class));
            assertNotNull(context.getBean(SchedulingConfigurer.class));
            assertTrue(context.containsBean("migrationTaskScheduler"));
            assertTrue(context.containsBean("migrationExecutionExecutor"));
        }
    }
}
