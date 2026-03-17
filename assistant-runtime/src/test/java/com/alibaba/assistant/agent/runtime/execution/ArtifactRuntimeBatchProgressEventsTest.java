/*
 * Copyright 2024-2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 */
package com.alibaba.assistant.agent.runtime.execution;

import com.alibaba.assistant.agent.execution.flow.DAGFlowExecutor;
import com.alibaba.assistant.agent.execution.flow.FlowContext;
import com.alibaba.assistant.agent.execution.flow.FlowDefinition;
import com.alibaba.assistant.agent.execution.flow.FlowExecutionResult;
import com.alibaba.assistant.agent.execution.model.StepDefinition;
import com.alibaba.assistant.agent.execution.model.StepResult;
import com.alibaba.assistant.agent.execution.model.StepStatus;
import com.alibaba.assistant.agent.runtime.compiler.RuntimeArtifact;
import com.alibaba.assistant.agent.runtime.registry.PublishedToolDescriptor;
import org.junit.jupiter.api.Test;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.*;

class ArtifactRuntimeBatchProgressEventsTest {
    @Test void shouldProjectBatchProgressThroughExistingExecutionEvents() {
        DAGFlowExecutor flowExecutor = mock(DAGFlowExecutor.class);
        ArtifactRuntimeExecutor executor = new ArtifactRuntimeExecutor(flowExecutor);
        RuntimeArtifact artifact = artifact("office1.approval_cleanup");
        PublishedToolDescriptor descriptor = PublishedToolDescriptor.forArtifact("tool-meta-catalog", "workflow:office1.approval_cleanup", "approval cleanup", null, null, false, "office1", artifact);
        when(flowExecutor.execute(same(artifact.getFlowDefinition()), any(FlowContext.class))).thenAnswer(invocation -> {
            FlowContext context = invocation.getArgument(1);
            StepDefinition step = new StepDefinition();
            step.setStepId("approval_batch");
            step.setName("approval_batch");
            for (var listener : context.getExecutionListeners()) {
                listener.onStepStarted(step, context);
                listener.onStepProgress(step, Map.of("batchProgress", Map.of("processedItems", 1, "selectedItems", 2, "percent", 50)), context);
                listener.onStepCompleted(step, StepResult.success(Map.of("selectedItems", 2)), context);
            }
            FlowExecutionResult result = new FlowExecutionResult();
            result.setSuccess(true);
            result.setStepStatuses(new LinkedHashMap<>(Map.of("approval_batch", StepStatus.COMPLETED)));
            result.setStepResults(new LinkedHashMap<>(Map.of("approval_batch", StepResult.success(Map.of("selectedItems", 2)))));
            result.setFinalOutputs(Map.of("selectedItems", 2));
            return result;
        });
        Map<String, Object> payload = executor.execute(descriptor, Map.of("message", "remind"), null);
        List<?> rawEvents = (List<?>) payload.get("executionEvents");
        ExecutionEvent event = rawEvents.stream().map(ExecutionEvent.class::cast).filter(it -> it.eventType() == ExecutionEventType.STEP_PROGRESS).findFirst().orElseThrow();
        assertEquals("approval_batch", event.stepId());
        assertEquals(50, ((Number) ((Map<?, ?>) event.payload().get("batchProgress")).get("percent")).intValue());
    }
    private RuntimeArtifact artifact(String code) {
        FlowDefinition flow = new FlowDefinition();
        flow.setVersion("2.0");
        flow.setEntry(List.of("approval_batch"));
        flow.setTerminal(List.of("approval_batch"));
        return new RuntimeArtifact(1L, code, RuntimeArtifact.ArtifactType.WORKFLOW, "approval cleanup", 1, null, null, null, null, null, flow, Map.of(), Map.of());
    }
}
