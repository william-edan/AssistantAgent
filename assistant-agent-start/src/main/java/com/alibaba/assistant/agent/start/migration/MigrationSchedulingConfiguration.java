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

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

import java.util.concurrent.Executor;

@Configuration
@Profile("migration")
@EnableScheduling
@ConditionalOnProperty(prefix = "assistant.runtime.proactive", name = "enabled", havingValue = "true", matchIfMissing = true)
public class MigrationSchedulingConfiguration implements SchedulingConfigurer {

    @Value("${assistant.runtime.proactive.scheduler-pool-size:4}")
    private int schedulerPoolSize;

    @Bean(name = "migrationTaskScheduler")
    public ThreadPoolTaskScheduler migrationTaskScheduler(
            @Value("${assistant.runtime.proactive.scheduler-pool-size:4}") int poolSize) {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(Math.max(poolSize, 2));
        scheduler.setThreadNamePrefix("migration-scheduler-");
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.initialize();
        return scheduler;
    }

    @Bean(name = "migrationExecutionExecutor")
    public Executor migrationExecutionExecutor(TaskScheduler migrationTaskScheduler) {
        return command -> ((Executor) migrationTaskScheduler).execute(command);
    }

    @Override
    public void configureTasks(ScheduledTaskRegistrar taskRegistrar) {
        taskRegistrar.setTaskScheduler(migrationTaskScheduler(schedulerPoolSize));
    }
}
