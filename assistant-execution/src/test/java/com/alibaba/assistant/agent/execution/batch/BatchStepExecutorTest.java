/*
 * Copyright 2024-2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 */
package com.alibaba.assistant.agent.execution.batch;

import com.alibaba.assistant.agent.execution.flow.FlowContext;
import com.alibaba.assistant.agent.execution.model.StepConfig;
import com.alibaba.assistant.agent.execution.model.StepDefinition;
import com.alibaba.assistant.agent.execution.model.StepResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BatchStepExecutorTest {

    @Test
    void shouldExecuteQueryFilterActionAggregateDeterministically() {
        BatchItemSelector selector = mock(BatchItemSelector.class);
        BatchStepExecutor executor = new BatchStepExecutor(selector, new BatchAggregationPolicy());
        FlowContext context = new FlowContext(Map.of("message", "remind"));

        when(selector.selectItems(eq("office1.pending_approvals_query"), eq(Map.of("message", "remind")), eq(context)))
                .thenReturn(List.of(
                        Map.of("approvalId", "A-1", "status", "PENDING"),
                        Map.of("approvalId", "A-2", "status", "APPROVED")));
        when(selector.executeAction(
                eq("office1.send_reminder"),
                eq(Map.of("approvalId", "A-1", "status", "PENDING", "message", "remind")),
                eq(context)))
                .thenReturn(StepResult.success(Map.of("reminderId", "R-1")));

        StepResult result = executor.execute(step(), context);

        assertTrue(result.isSuccess());
        assertEquals(2, ((Number) result.getOutputs().get("totalItems")).intValue());
        assertEquals(1, ((Number) result.getOutputs().get("selectedItems")).intValue());
        assertEquals(1, ((Number) result.getOutputs().get("processedItems")).intValue());
        assertEquals(1, ((Number) result.getOutputs().get("succeededItems")).intValue());
        assertEquals(List.of("A-1"), result.getOutputs().get("processedItemIds"));
        assertEquals(100, ((Number) ((Map<?, ?>) result.getOutputs().get("batchProgress")).get("percent")).intValue());
    }

    private StepDefinition step() {
        StepConfig config = new StepConfig();
        config.setSelectorToolCode("office1.pending_approvals_query");
        config.setActionToolCode("office1.send_reminder");
        config.setFilterExpression("$.status == 'PENDING'");
        config.setConcurrency(2);
        config.setInputMapping(Map.of("message", "${message}"));

        StepDefinition step = new StepDefinition();
        step.setStepId("approval_batch");
        step.setName("approval_batch");
        step.setConfig(config);
        return step;
    }
}
