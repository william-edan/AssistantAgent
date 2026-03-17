/*
 * Copyright 2024-2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 */
package com.alibaba.assistant.agent.execution.flow;

import com.alibaba.assistant.agent.execution.batch.BatchAggregationPolicy;
import com.alibaba.assistant.agent.execution.batch.BatchItemSelector;
import com.alibaba.assistant.agent.execution.batch.BatchStepExecutor;
import com.alibaba.assistant.agent.execution.model.JoinType;
import com.alibaba.assistant.agent.execution.model.StepConfig;
import com.alibaba.assistant.agent.execution.model.StepDefinition;
import com.alibaba.assistant.agent.execution.model.StepResult;
import com.alibaba.assistant.agent.execution.model.StepStatus;
import com.alibaba.assistant.agent.execution.model.StepType;
import com.alibaba.assistant.agent.execution.step.HttpStepExecutor;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class DAGFlowExecutorBatchStepTest {
    @Test void shouldExecuteBatchStepInsideCanonicalExecutionPlan() {
        BatchItemSelector selector = mock(BatchItemSelector.class);
        BatchStepExecutor batch = new BatchStepExecutor(selector, new BatchAggregationPolicy());
        DAGFlowExecutor executor = new DAGFlowExecutor(mock(HttpStepExecutor.class), batch);
        FlowContext context = new FlowContext(Map.of("message", "remind"));
        when(selector.selectItems(eq("office1.pending_approvals_query"), eq(Map.of("message", "remind")), eq(context)))
                .thenReturn(List.of(Map.of("approvalId", "A-1", "status", "PENDING")));
        when(selector.executeAction(eq("office1.send_reminder"), eq(Map.of("approvalId", "A-1", "status", "PENDING", "message", "remind")), eq(context)))
                .thenReturn(StepResult.success(Map.of("reminderId", "R-1")));
        FlowExecutionResult result = executor.execute(flow(), context);
        assertTrue(result.isSuccess());
        assertEquals(StepStatus.COMPLETED, result.getStepStatuses().get("approval_batch"));
        assertEquals(1, ((Number) result.getFinalOutputs().get("selectedItems")).intValue());
    }
    private FlowDefinition flow() {
        StepConfig config = new StepConfig();
        config.setSelectorToolCode("office1.pending_approvals_query");
        config.setActionToolCode("office1.send_reminder");
        config.setFilterExpression("$.status == 'PENDING'");
        config.setConcurrency(2);
        config.setInputMapping(Map.of("message", "${message}"));
        StepDefinition step = new StepDefinition();
        step.setStepId("approval_batch");
        step.setName("approval_batch");
        step.setType(StepType.BATCH);
        step.setJoinType(JoinType.ALL);
        step.setConfig(config);
        FlowDefinition flow = new FlowDefinition();
        flow.setVersion("2.0");
        flow.setSteps(Map.of("approval_batch", step));
        flow.setEntry(List.of("approval_batch"));
        flow.setTerminal(List.of("approval_batch"));
        return flow;
    }
}
