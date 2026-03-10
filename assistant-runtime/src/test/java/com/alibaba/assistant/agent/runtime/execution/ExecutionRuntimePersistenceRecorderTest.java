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

import com.alibaba.assistant.agent.controlplane.audit.AuditEvent;
import com.alibaba.assistant.agent.controlplane.audit.AuditEventService;
import com.alibaba.assistant.agent.execution.flow.FlowContext;
import com.alibaba.assistant.agent.execution.flow.FlowDefinition;
import com.alibaba.assistant.agent.execution.flow.FlowExecutionResult;
import com.alibaba.assistant.agent.execution.model.StepResult;
import com.alibaba.assistant.agent.execution.model.StepStatus;
import com.alibaba.assistant.agent.execution.persistence.ExecutionRun;
import com.alibaba.assistant.agent.execution.persistence.ExecutionRunService;
import com.alibaba.assistant.agent.execution.persistence.ExecutionStep;
import com.alibaba.assistant.agent.execution.persistence.ExecutionStepService;
import com.alibaba.assistant.agent.runtime.compiler.RuntimeArtifact;
import com.alibaba.assistant.agent.runtime.registry.PublishedToolDescriptor;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ExecutionRuntimePersistenceRecorderTest {

    @Test
    void shouldPersistExecutionRunStepsAndAuditTrail() {
        ExecutionRunService executionRunService = mock(ExecutionRunService.class);
        ExecutionStepService executionStepService = mock(ExecutionStepService.class);
        AuditEventService auditEventService = mock(AuditEventService.class);
        ExecutionRuntimePersistenceRecorder recorder = new ExecutionRuntimePersistenceRecorder(
                executionRunService,
                executionStepService,
                auditEventService,
                new ObjectMapper());
        when(executionRunService.findLatestByRunId("RUN-1")).thenReturn(Optional.empty());
        when(executionStepService.findByRunIdAndStepId("RUN-1", "create_leave")).thenReturn(Optional.empty());
        when(executionStepService.findByRunIdAndStepId("RUN-1", "submit_approval")).thenReturn(Optional.empty());

        recorder.record(descriptor(), flowContext(), successfulResult(), successfulEvents());

        ArgumentCaptor<ExecutionRun> runCaptor = ArgumentCaptor.forClass(ExecutionRun.class);
        verify(executionRunService).save(runCaptor.capture());
        assertEquals("RUN-1", runCaptor.getValue().getRunId());
        assertEquals("oa.leave.apply", runCaptor.getValue().getArtifactCode());
        assertEquals("COMPLETED", runCaptor.getValue().getStatus());

        ArgumentCaptor<ExecutionStep> stepCaptor = ArgumentCaptor.forClass(ExecutionStep.class);
        verify(executionStepService, times(2)).save(stepCaptor.capture());
        List<ExecutionStep> steps = stepCaptor.getAllValues();
        assertEquals("create_leave", steps.get(0).getStepId());
        assertEquals("oa.default", steps.get(0).getAuthProfileCode());
        assertEquals("submit_approval", steps.get(1).getStepId());
        assertEquals("COMPLETED", steps.get(1).getStatus());

        ArgumentCaptor<AuditEvent> auditCaptor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditEventService, times(6)).save(auditCaptor.capture());
        assertEquals("RUN-1", auditCaptor.getAllValues().get(0).getRunId());
        assertEquals("RUN_STARTED", auditCaptor.getAllValues().get(0).getEventType());
        assertEquals("STEP_COMPLETED", auditCaptor.getAllValues().get(2).getEventType());
    }

    @Test
    void shouldUpdateExistingExecutionRecordsOnFailure() {
        ExecutionRunService executionRunService = mock(ExecutionRunService.class);
        ExecutionStepService executionStepService = mock(ExecutionStepService.class);
        AuditEventService auditEventService = mock(AuditEventService.class);
        ExecutionRuntimePersistenceRecorder recorder = new ExecutionRuntimePersistenceRecorder(
                executionRunService,
                executionStepService,
                auditEventService,
                new ObjectMapper());

        ExecutionRun existingRun = new ExecutionRun();
        existingRun.setId(10L);
        existingRun.setRunId("RUN-1");
        ExecutionStep existingStep = new ExecutionStep();
        existingStep.setId(20L);
        existingStep.setRunId("RUN-1");
        existingStep.setStepId("submit_approval");
        when(executionRunService.findLatestByRunId("RUN-1")).thenReturn(Optional.of(existingRun));
        when(executionStepService.findByRunIdAndStepId("RUN-1", "submit_approval")).thenReturn(Optional.of(existingStep));

        recorder.record(descriptor(), flowContext(), failedResult(), failedEvents());

        ArgumentCaptor<ExecutionRun> runCaptor = ArgumentCaptor.forClass(ExecutionRun.class);
        verify(executionRunService).updateById(runCaptor.capture());
        assertEquals("FAILED", runCaptor.getValue().getStatus());

        ArgumentCaptor<ExecutionStep> stepCaptor = ArgumentCaptor.forClass(ExecutionStep.class);
        verify(executionStepService).updateById(stepCaptor.capture());
        assertEquals("FAILED", stepCaptor.getValue().getStatus());
        assertEquals("submit failed", stepCaptor.getValue().getErrorMessage());
        verify(executionStepService, never()).save(any());
    }

    private PublishedToolDescriptor descriptor() {
        RuntimeArtifact.ActionBinding action = new RuntimeArtifact.ActionBinding(
                1L,
                "oa.leave.create",
                21L,
                null,
                "[\"oa.default\"]",
                "oa.default",
                null,
                null,
                null,
                null,
                "medium",
                null,
                "write",
                null,
                1);
        RuntimeArtifact.StepBinding createLeave = new RuntimeArtifact.StepBinding(
                "create_leave",
                "创建请假记录",
                "HTTP",
                21L,
                null,
                "[\"oa.default\"]",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                1,
                action);
        RuntimeArtifact.StepBinding submitApproval = new RuntimeArtifact.StepBinding(
                "submit_approval",
                "提交审批",
                "HTTP",
                21L,
                null,
                "[\"oa.default\"]",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                2,
                action);
        FlowDefinition flowDefinition = new FlowDefinition();
        flowDefinition.setEntry(List.of("create_leave"));
        flowDefinition.setTerminal(List.of("submit_approval"));
        return PublishedToolDescriptor.forArtifact(
                "artifact-catalog",
                "workflow:oa.leave.apply",
                "请假申请",
                null,
                null,
                false,
                "gougu_oa",
                new RuntimeArtifact(
                        1L,
                        "oa.leave.apply",
                        RuntimeArtifact.ArtifactType.WORKFLOW,
                        "请假申请",
                        1,
                        null,
                        null,
                        null,
                        null,
                        null,
                        flowDefinition,
                        Map.of("oa.leave.create", action),
                        Map.of(
                                "create_leave", createLeave,
                                "submit_approval", submitApproval)));
    }

    private FlowContext flowContext() {
        FlowContext context = new FlowContext(Map.of("reason", "事假"));
        context.setRunId("RUN-1");
        context.setAssistantUid("u1");
        context.setThreadId("T-1");
        context.setSystemCode("gougu_oa");
        return context;
    }

    private FlowExecutionResult successfulResult() {
        FlowExecutionResult result = new FlowExecutionResult();
        result.setSuccess(true);
        LinkedHashMap<String, StepStatus> stepStatuses = new LinkedHashMap<>();
        stepStatuses.put("create_leave", StepStatus.COMPLETED);
        stepStatuses.put("submit_approval", StepStatus.COMPLETED);
        result.setStepStatuses(stepStatuses);
        LinkedHashMap<String, StepResult> stepResults = new LinkedHashMap<>();
        stepResults.put("create_leave", StepResult.success(Map.of("leaveId", "L-1")));
        stepResults.put("submit_approval", StepResult.success(Map.of("message", "ok")));
        result.setStepResults(stepResults);
        result.setFinalOutputs(Map.of("leaveId", "L-1"));
        return result;
    }

    private FlowExecutionResult failedResult() {
        FlowExecutionResult result = new FlowExecutionResult();
        result.setSuccess(false);
        LinkedHashMap<String, StepStatus> stepStatuses = new LinkedHashMap<>();
        stepStatuses.put("submit_approval", StepStatus.FAILED);
        result.setStepStatuses(stepStatuses);
        LinkedHashMap<String, StepResult> stepResults = new LinkedHashMap<>();
        stepResults.put("submit_approval", StepResult.failure("submit failed"));
        result.setStepResults(stepResults);
        result.setErrorMessage("submit failed");
        return result;
    }

    private List<ExecutionEvent> successfulEvents() {
        Instant base = Instant.parse("2026-03-10T10:00:00Z");
        return List.of(
                new ExecutionEvent("RUN-1", "oa.leave.apply", "WORKFLOW", null, 1L,
                        ExecutionEventType.RUN_STARTED, ExecutionLifecycleStatus.RUNNING, base, Map.of("source", "artifact-runtime")),
                new ExecutionEvent("RUN-1", "oa.leave.apply", "WORKFLOW", "create_leave", 2L,
                        ExecutionEventType.STEP_STARTED, ExecutionLifecycleStatus.RUNNING, base.plusSeconds(1), Map.of("stepName", "创建请假记录")),
                new ExecutionEvent("RUN-1", "oa.leave.apply", "WORKFLOW", "create_leave", 3L,
                        ExecutionEventType.STEP_COMPLETED, ExecutionLifecycleStatus.COMPLETED, base.plusSeconds(2), Map.of("stepName", "创建请假记录")),
                new ExecutionEvent("RUN-1", "oa.leave.apply", "WORKFLOW", "submit_approval", 4L,
                        ExecutionEventType.STEP_STARTED, ExecutionLifecycleStatus.RUNNING, base.plusSeconds(3), Map.of("stepName", "提交审批")),
                new ExecutionEvent("RUN-1", "oa.leave.apply", "WORKFLOW", "submit_approval", 5L,
                        ExecutionEventType.STEP_COMPLETED, ExecutionLifecycleStatus.COMPLETED, base.plusSeconds(4), Map.of("stepName", "提交审批")),
                new ExecutionEvent("RUN-1", "oa.leave.apply", "WORKFLOW", null, 6L,
                        ExecutionEventType.RUN_COMPLETED, ExecutionLifecycleStatus.COMPLETED, base.plusSeconds(5), Map.of("finalOutputs", Map.of("leaveId", "L-1"))));
    }

    private List<ExecutionEvent> failedEvents() {
        Instant base = Instant.parse("2026-03-10T10:00:00Z");
        return List.of(
                new ExecutionEvent("RUN-1", "oa.leave.apply", "WORKFLOW", null, 1L,
                        ExecutionEventType.RUN_STARTED, ExecutionLifecycleStatus.RUNNING, base, Map.of("source", "artifact-runtime")),
                new ExecutionEvent("RUN-1", "oa.leave.apply", "WORKFLOW", "submit_approval", 2L,
                        ExecutionEventType.STEP_STARTED, ExecutionLifecycleStatus.RUNNING, base.plusSeconds(1), Map.of("stepName", "提交审批")),
                new ExecutionEvent("RUN-1", "oa.leave.apply", "WORKFLOW", "submit_approval", 3L,
                        ExecutionEventType.STEP_FAILED, ExecutionLifecycleStatus.FAILED, base.plusSeconds(2), Map.of("stepName", "提交审批", "error", "submit failed")),
                new ExecutionEvent("RUN-1", "oa.leave.apply", "WORKFLOW", null, 4L,
                        ExecutionEventType.RUN_FAILED, ExecutionLifecycleStatus.FAILED, base.plusSeconds(3), Map.of("error", "submit failed")));
    }
}

