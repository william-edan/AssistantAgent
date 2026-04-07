/*
 * Copyright 2024-2025 the original author or authors.
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
package com.alibaba.assistant.agent.runtime.execution;

import com.alibaba.assistant.agent.execution.batch.BatchAggregationPolicy;
import com.alibaba.assistant.agent.execution.batch.BatchItemSelector;
import com.alibaba.assistant.agent.execution.batch.BatchStepExecutor;
import com.alibaba.assistant.agent.runtime.execution.batch.ToolExecutorBatchItemSelector;
import com.alibaba.assistant.agent.runtime.planner.ToolExecutor;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * Migration-profile batch execution wiring.
 */
@Configuration
@Profile("migration")
public class BatchExecutionConfiguration {

    @Bean
    public BatchAggregationPolicy batchAggregationPolicy() {
        return new BatchAggregationPolicy();
    }

    @Bean
    public BatchItemSelector batchItemSelector(ObjectProvider<ToolExecutor> toolExecutorProvider, ObjectMapper objectMapper) {
        return new ToolExecutorBatchItemSelector(toolExecutorProvider, objectMapper);
    }

    @Bean
    public BatchStepExecutor batchStepExecutor(BatchItemSelector batchItemSelector, BatchAggregationPolicy batchAggregationPolicy) {
        return new BatchStepExecutor(batchItemSelector, batchAggregationPolicy);
    }
}
