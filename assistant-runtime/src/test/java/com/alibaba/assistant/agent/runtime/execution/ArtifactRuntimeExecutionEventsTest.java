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

import com.alibaba.assistant.agent.execution.flow.DAGFlowExecutor;
import com.alibaba.assistant.agent.execution.flow.FlowDefinition;
import com.alibaba.assistant.agent.execution.flow.FlowContext;
import com.alibaba.assistant.agent.execution.flow.FlowExecutionResult;
import com.alibaba.assistant.agent.execution.model.StepResult;
import com.alibaba.assistant.agent.execution.model.StepStatus;
import com.alibaba.assistant.agent.runtime.compiler.RuntimeArtifact;
import com.alibaba.assistant.agent.runtime.registry.PublishedToolDescriptor;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ArtifactRuntimeExecutionEventsTest {

    @Test
    void shouldIncludeOrderedExecutionEventsInArtifactPayload() {
        DAGFlowExecutor dagFlowExecutor = mock(DAGFlowExecutor.class);
        ArtifactRuntimeExecutor executor = new ArtifactRuntimeExecutor(dagFlowExecutor);
        RuntimeArtifact artifact = runtimeArtifact("oa.leave.apply");
        PublishedToolDescriptor descriptor = PublishedToolDescriptor.forArtifact(
                "artifact-catalog",
                "workflow:oa.leave.apply",
                "请假申请",
                null,
                null,
                false,
                "gougu_oa",
                artifact);

        FlowExecutionResult flowExecutionResult = new FlowExecutionResult();
        flowExecutionResult.setSuccess(true);
        LinkedHashMap<String, StepStatus> stepStatuses = new LinkedHashMap<>();
        stepStatuses.put("create_leave", StepStatus.COMPLETED);
        stepStatuses.put("submit_approval", StepStatus.COMPLETED);
        flowExecutionResult.setStepStatuses(stepStatuses);
        LinkedHashMap<String, StepResult> stepResults = new LinkedHashMap<>();
        stepResults.put("create_leave", StepResult.success(Map.of("leaveId", "L-1")));
        stepResults.put("submit_approval", StepResult.success(Map.of("message", "ok")));
        flowExecutionResult.setStepResults(stepResults);
        flowExecutionResult.setFinalOutputs(Map.of("leaveId", "L-1"));
        when(dagFlowExecutor.execute(same(artifact.getFlowDefinition()), any(FlowContext.class))).thenReturn(flowExecutionResult);

        Map<String, Object> payload = executor.execute(descriptor, Map.of("reason", "事假"), null);

        assertNotNull(payload.get("runId"));
        assertTrue(payload.get("executionEvents") instanceof List<?>);
        List<?> rawEvents = (List<?>) payload.get("executionEvents");
        assertEquals(6, rawEvents.size());

        ExecutionEvent runStarted = (ExecutionEvent) rawEvents.get(0);
        ExecutionEvent createLeaveStarted = (ExecutionEvent) rawEvents.get(1);
        ExecutionEvent createLeaveCompleted = (ExecutionEvent) rawEvents.get(2);
        ExecutionEvent runCompleted = (ExecutionEvent) rawEvents.get(5);

        assertEquals(ExecutionEventType.RUN_STARTED, runStarted.eventType());
        assertEquals(ExecutionLifecycleStatus.RUNNING, runStarted.lifecycleStatus());
        assertEquals("create_leave", createLeaveStarted.stepId());
        assertEquals(ExecutionEventType.STEP_STARTED, createLeaveStarted.eventType());
        assertEquals(ExecutionEventType.STEP_COMPLETED, createLeaveCompleted.eventType());
        assertEquals("submit_approval", ((ExecutionEvent) rawEvents.get(3)).stepId());
        assertEquals(ExecutionEventType.RUN_COMPLETED, runCompleted.eventType());
        assertEquals(ExecutionLifecycleStatus.COMPLETED, runCompleted.lifecycleStatus());
    }

    @Test
    void shouldEmitRunFailedEventWhenArtifactExecutionFails() {
        DAGFlowExecutor dagFlowExecutor = mock(DAGFlowExecutor.class);
        ArtifactRuntimeExecutor executor = new ArtifactRuntimeExecutor(dagFlowExecutor);
        RuntimeArtifact artifact = runtimeArtifact("oa.leave.apply");
        PublishedToolDescriptor descriptor = PublishedToolDescriptor.forArtifact(
                "artifact-catalog",
                "workflow:oa.leave.apply",
                "请假申请",
                null,
                null,
                false,
                "gougu_oa",
                artifact);

        FlowExecutionResult flowExecutionResult = new FlowExecutionResult();
        flowExecutionResult.setSuccess(false);
        flowExecutionResult.setErrorMessage("submit failed");
        LinkedHashMap<String, StepStatus> stepStatuses = new LinkedHashMap<>();
        stepStatuses.put("create_leave", StepStatus.COMPLETED);
        stepStatuses.put("submit_approval", StepStatus.FAILED);
        flowExecutionResult.setStepStatuses(stepStatuses);
        LinkedHashMap<String, StepResult> stepResults = new LinkedHashMap<>();
        stepResults.put("create_leave", StepResult.success(Map.of("leaveId", "L-1")));
        stepResults.put("submit_approval", StepResult.failure("submit failed"));
        flowExecutionResult.setStepResults(stepResults);
        when(dagFlowExecutor.execute(same(artifact.getFlowDefinition()), any(FlowContext.class))).thenReturn(flowExecutionResult);

        Map<String, Object> payload = executor.execute(descriptor, Map.of("reason", "事假"), null);

        assertFalse(Boolean.TRUE.equals(payload.get("success")));
        List<?> rawEvents = (List<?>) payload.get("executionEvents");
        ExecutionEvent runFailed = (ExecutionEvent) rawEvents.get(rawEvents.size() - 1);
        assertEquals(ExecutionEventType.RUN_FAILED, runFailed.eventType());
        assertEquals(ExecutionLifecycleStatus.FAILED, runFailed.lifecycleStatus());
        assertEquals("submit failed", runFailed.payload().get("error"));
    }

    private RuntimeArtifact runtimeArtifact(String artifactCode) {
        FlowDefinition flowDefinition = new FlowDefinition();
        flowDefinition.setVersion("2.0");
        flowDefinition.setEntry(List.of("create_leave"));
        flowDefinition.setTerminal(List.of("submit_approval"));
        return new RuntimeArtifact(
                1L,
                artifactCode,
                RuntimeArtifact.ArtifactType.WORKFLOW,
                "请假申请",
                1,
                null,
                null,
                null,
                null,
                null,
                flowDefinition,
                Map.of(),
                Map.of());
    }
}


