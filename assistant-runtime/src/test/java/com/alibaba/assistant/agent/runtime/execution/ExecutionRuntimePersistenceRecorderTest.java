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
import com.alibaba.assistant.agent.execution.persistence.ApprovalRequest;
import com.alibaba.assistant.agent.execution.persistence.ApprovalRequestService;
import com.alibaba.assistant.agent.execution.persistence.ExecutionRun;
import com.alibaba.assistant.agent.execution.persistence.ExecutionRunService;
import com.alibaba.assistant.agent.execution.persistence.ExecutionStep;
import com.alibaba.assistant.agent.execution.persistence.ExecutionStepService;
import com.alibaba.assistant.agent.runtime.agent.AssistantStateKeys;
import com.alibaba.assistant.agent.runtime.compiler.RuntimeArtifact;
import com.alibaba.assistant.agent.runtime.context.RuntimeSpaceResolver;
import com.alibaba.assistant.agent.controlplane.space.PlatformSpace;
import com.alibaba.assistant.agent.controlplane.space.PlatformSpaceService;
import com.alibaba.assistant.agent.runtime.registry.PublishedToolDescriptor;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
        ApprovalRequestService approvalRequestService = mock(ApprovalRequestService.class);
        AuditEventService auditEventService = mock(AuditEventService.class);
        ExecutionRuntimePersistenceRecorder recorder = new ExecutionRuntimePersistenceRecorder(
                executionRunService,
                executionStepService,
                approvalRequestService,
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
        ApprovalRequestService approvalRequestService = mock(ApprovalRequestService.class);
        AuditEventService auditEventService = mock(AuditEventService.class);
        ExecutionRuntimePersistenceRecorder recorder = new ExecutionRuntimePersistenceRecorder(
                executionRunService,
                executionStepService,
                approvalRequestService,
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

    @Test
    void shouldFallbackToSpaceIdFromFlowContextWhenArtifactMetadataMissing() {
        ExecutionRunService executionRunService = mock(ExecutionRunService.class);
        ExecutionStepService executionStepService = mock(ExecutionStepService.class);
        ApprovalRequestService approvalRequestService = mock(ApprovalRequestService.class);
        AuditEventService auditEventService = mock(AuditEventService.class);
        ExecutionRuntimePersistenceRecorder recorder = new ExecutionRuntimePersistenceRecorder(
                executionRunService,
                executionStepService,
                approvalRequestService,
                auditEventService,
                new ObjectMapper());
        when(executionRunService.findLatestByRunId("RUN-1")).thenReturn(Optional.empty());
        when(executionStepService.findByRunIdAndStepId("RUN-1", "create_leave")).thenReturn(Optional.empty());
        when(executionStepService.findByRunIdAndStepId("RUN-1", "submit_approval")).thenReturn(Optional.empty());

        recorder.record(descriptor(null), flowContext(88L), successfulResult(), successfulEvents());

        ArgumentCaptor<ExecutionRun> runCaptor = ArgumentCaptor.forClass(ExecutionRun.class);
        verify(executionRunService).save(runCaptor.capture());
        assertEquals(88L, runCaptor.getValue().getSpaceId());
    }

    @Test
    void shouldFallbackToConfiguredDefaultSpaceWhenFlowContextHasNoSpace() {
        ExecutionRunService executionRunService = mock(ExecutionRunService.class);
        ExecutionStepService executionStepService = mock(ExecutionStepService.class);
        ApprovalRequestService approvalRequestService = mock(ApprovalRequestService.class);
        AuditEventService auditEventService = mock(AuditEventService.class);
        PlatformSpaceService platformSpaceService = mock(PlatformSpaceService.class);
        PlatformSpace platformSpace = new PlatformSpace();
        platformSpace.setId(31L);
        platformSpace.setSpaceCode("default");
        when(platformSpaceService.resolveDefaultRuntimeSpace("prod")).thenReturn(Optional.of(platformSpace));
        ExecutionRuntimePersistenceRecorder recorder = new ExecutionRuntimePersistenceRecorder(
                executionRunService,
                executionStepService,
                approvalRequestService,
                auditEventService,
                new ObjectMapper(),
                new RuntimeSpaceResolver(platformSpaceService, "prod"));
        when(executionRunService.findLatestByRunId("RUN-1")).thenReturn(Optional.empty());
        when(executionStepService.findByRunIdAndStepId("RUN-1", "create_leave")).thenReturn(Optional.empty());
        when(executionStepService.findByRunIdAndStepId("RUN-1", "submit_approval")).thenReturn(Optional.empty());

        recorder.record(descriptor(null), flowContext(), successfulResult(), successfulEvents());

        ArgumentCaptor<ExecutionRun> runCaptor = ArgumentCaptor.forClass(ExecutionRun.class);
        verify(executionRunService).save(runCaptor.capture());
        assertEquals(31L, runCaptor.getValue().getSpaceId());
    }

    @Test
    void shouldPersistApprovalRequestWhenExecutionWaitsForApproval() {
        ExecutionRunService executionRunService = mock(ExecutionRunService.class);
        ExecutionStepService executionStepService = mock(ExecutionStepService.class);
        ApprovalRequestService approvalRequestService = mock(ApprovalRequestService.class);
        AuditEventService auditEventService = mock(AuditEventService.class);
        ExecutionRuntimePersistenceRecorder recorder = new ExecutionRuntimePersistenceRecorder(
                executionRunService,
                executionStepService,
                approvalRequestService,
                auditEventService,
                new ObjectMapper());
        when(executionRunService.findLatestByRunId("RUN-1")).thenReturn(Optional.empty());
        when(executionStepService.findByRunIdAndStepId("RUN-1", "submit_approval")).thenReturn(Optional.empty());
        when(approvalRequestService.findLatestPendingByRunAndStep("RUN-1", "submit_approval")).thenReturn(Optional.empty());

        recorder.record(descriptor(), flowContext(), waitingResult(), waitingEvents());

        ArgumentCaptor<ExecutionRun> runCaptor = ArgumentCaptor.forClass(ExecutionRun.class);
        verify(executionRunService).save(runCaptor.capture());
        assertEquals("WAITING_APPROVAL", runCaptor.getValue().getStatus());
        assertEquals("submit_approval", runCaptor.getValue().getPausedStepId());
        assertEquals("RUN-1:submit_approval", runCaptor.getValue().getApprovalRequestId());
        assertTrue(runCaptor.getValue().getContextSnapshotJson().contains("create_leave"));
        assertTrue(runCaptor.getValue().getContextSnapshotJson().contains("gougu_oa"));

        ArgumentCaptor<ApprovalRequest> approvalCaptor = ArgumentCaptor.forClass(ApprovalRequest.class);
        verify(approvalRequestService).save(approvalCaptor.capture());
        assertEquals("RUN-1:submit_approval", approvalCaptor.getValue().getRequestId());
        assertEquals("WAITING_APPROVAL", approvalCaptor.getValue().getStatus());
        assertEquals("submit_approval", approvalCaptor.getValue().getStepId());
    }
    @Test
    void shouldPreserveOriginalRunStartWhenPersistingResumedExecution() {
        ExecutionRunService executionRunService = mock(ExecutionRunService.class);
        ExecutionStepService executionStepService = mock(ExecutionStepService.class);
        ApprovalRequestService approvalRequestService = mock(ApprovalRequestService.class);
        AuditEventService auditEventService = mock(AuditEventService.class);
        ExecutionRuntimePersistenceRecorder recorder = new ExecutionRuntimePersistenceRecorder(
                executionRunService,
                executionStepService,
                approvalRequestService,
                auditEventService,
                new ObjectMapper());

        LocalDateTime originalStartedAt = LocalDateTime.of(2026, 3, 10, 18, 0);
        ExecutionRun existingRun = new ExecutionRun();
        existingRun.setId(10L);
        existingRun.setRunId("RUN-1");
        existingRun.setStatus("WAITING_APPROVAL");
        existingRun.setPausedStepId("submit_approval");
        existingRun.setApprovalRequestId("RUN-1:submit_approval");
        existingRun.setStartedAt(originalStartedAt);
        existingRun.setContextSnapshotJson("{\"resume\":true}");
        ExecutionStep existingStep = new ExecutionStep();
        existingStep.setId(20L);
        existingStep.setRunId("RUN-1");
        existingStep.setStepId("submit_approval");
        existingStep.setStatus("WAITING_APPROVAL");
        existingStep.setStartedAt(LocalDateTime.of(2026, 3, 10, 18, 5));
        existingStep.setCompletedAt(LocalDateTime.of(2026, 3, 10, 18, 6));
        when(executionRunService.findLatestByRunId("RUN-1")).thenReturn(Optional.of(existingRun));
        when(executionStepService.findByRunIdAndStepId("RUN-1", "submit_approval")).thenReturn(Optional.of(existingStep));

        recorder.record(descriptor(), flowContext(), resumedResult(), resumedEvents());

        ArgumentCaptor<ExecutionRun> runCaptor = ArgumentCaptor.forClass(ExecutionRun.class);
        verify(executionRunService).updateById(runCaptor.capture());
        assertEquals(originalStartedAt, runCaptor.getValue().getStartedAt());
        assertEquals("COMPLETED", runCaptor.getValue().getStatus());
        assertEquals(null, runCaptor.getValue().getPausedStepId());
        assertEquals(null, runCaptor.getValue().getApprovalRequestId());
        assertEquals(null, runCaptor.getValue().getContextSnapshotJson());

        ArgumentCaptor<ExecutionStep> stepCaptor = ArgumentCaptor.forClass(ExecutionStep.class);
        verify(executionStepService).updateById(stepCaptor.capture());
        assertEquals(LocalDateTime.of(2026, 3, 10, 18, 5), stepCaptor.getValue().getStartedAt());
        assertEquals(LocalDateTime.ofInstant(resumedEvents().get(2).occurredAt(), ZoneId.systemDefault()), stepCaptor.getValue().getCompletedAt());
        assertEquals("COMPLETED", stepCaptor.getValue().getStatus());
    }

    @Test
    void shouldGenerateDistinctAuditEventIdsForResumedExecution() {
        ExecutionRunService executionRunService = mock(ExecutionRunService.class);
        ExecutionStepService executionStepService = mock(ExecutionStepService.class);
        ApprovalRequestService approvalRequestService = mock(ApprovalRequestService.class);
        AuditEventService auditEventService = mock(AuditEventService.class);
        ExecutionRuntimePersistenceRecorder recorder = new ExecutionRuntimePersistenceRecorder(
                executionRunService,
                executionStepService,
                approvalRequestService,
                auditEventService,
                new ObjectMapper());

        ExecutionRun existingRun = new ExecutionRun();
        existingRun.setId(10L);
        existingRun.setRunId("RUN-1");
        existingRun.setStatus("WAITING_APPROVAL");
        existingRun.setPausedStepId("submit_approval");
        existingRun.setApprovalRequestId("RUN-1:submit_approval");
        when(executionRunService.findLatestByRunId("RUN-1")).thenReturn(Optional.of(existingRun));
        when(executionStepService.findByRunIdAndStepId("RUN-1", "submit_approval"))
                .thenReturn(Optional.of(new ExecutionStep()));

        recorder.record(descriptor(), flowContext(), resumedResult(), resumedEvents());

        ArgumentCaptor<AuditEvent> auditCaptor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditEventService, times(4)).save(auditCaptor.capture());
        List<String> eventIds = auditCaptor.getAllValues().stream().map(AuditEvent::getEventId).toList();
        assertTrue(eventIds.stream().noneMatch(id -> "RUN-1:1".equals(id) || "RUN-1:2".equals(id)
                || "RUN-1:3".equals(id) || "RUN-1:4".equals(id)));
    }
    private PublishedToolDescriptor descriptor() {
        return descriptor(1L);
    }

    private PublishedToolDescriptor descriptor(Long spaceId) {
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
                "medium",
                null,
                "write",
                1
        );
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
                1,
                action
        );
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
                "{\"enabled\":true,\"channel\":\"controlplane\"}",
                2,
                action
        );
        FlowDefinition flowDefinition = new FlowDefinition();
        flowDefinition.setEntry(List.of("create_leave"));
        flowDefinition.setTerminal(List.of("submit_approval"));
        return PublishedToolDescriptor.forArtifact(
                "tool-meta-catalog",
                "workflow:oa.leave.apply",
                "请假申请",
                null,
                null,
                false,
                "gougu_oa",
                new RuntimeArtifact(
                        spaceId,
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
        return flowContext(null);
    }

    private FlowContext flowContext(Long spaceId) {
        Map<String, Object> initialInputs = new LinkedHashMap<>();
        initialInputs.put("reason", "事假");
        if (spaceId != null) {
            initialInputs.put(AssistantStateKeys.SPACE_ID, spaceId);
            initialInputs.put("space_id", spaceId);
            initialInputs.put("spaceId", spaceId);
        }
        FlowContext context = new FlowContext(initialInputs);
        context.setRunId("RUN-1");
        context.setAssistantUid("u1");
        context.setThreadId("T-1");
        context.setSystemCode("gougu_oa");
        context.restoreStepOutput("create_leave", Map.of("leave_id", "L-1"));
        return context;
    }

    private FlowExecutionResult successfulResult() {
        FlowExecutionResult result = new FlowExecutionResult();
        result.setSuccess(true);
        result.setLifecycleStatus("COMPLETED");
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
        result.setLifecycleStatus("FAILED");
        LinkedHashMap<String, StepStatus> stepStatuses = new LinkedHashMap<>();
        stepStatuses.put("submit_approval", StepStatus.FAILED);
        result.setStepStatuses(stepStatuses);
        LinkedHashMap<String, StepResult> stepResults = new LinkedHashMap<>();
        stepResults.put("submit_approval", StepResult.failure("submit failed"));
        result.setStepResults(stepResults);
        result.setErrorMessage("submit failed");
        return result;
    }

    private FlowExecutionResult waitingResult() {
        FlowExecutionResult result = new FlowExecutionResult();
        result.setSuccess(false);
        result.setLifecycleStatus("WAITING_APPROVAL");
        result.setPausedStepId("submit_approval");
        result.setApprovalRequestId("RUN-1:submit_approval");
        LinkedHashMap<String, StepStatus> stepStatuses = new LinkedHashMap<>();
        stepStatuses.put("create_leave", StepStatus.COMPLETED);
        stepStatuses.put("submit_approval", StepStatus.WAITING_APPROVAL);
        result.setStepStatuses(stepStatuses);
        LinkedHashMap<String, StepResult> stepResults = new LinkedHashMap<>();
        stepResults.put("create_leave", StepResult.success(Map.of("leave_id", "L-1")));
        StepResult waiting = new StepResult();
        waiting.setSuccess(false);
        waiting.setOutputs(Map.of("waitingApproval", true));
        stepResults.put("submit_approval", waiting);
        result.setStepResults(stepResults);
        result.setFinalOutputs(Map.of("create_leave.leave_id", "L-1"));
        return result;
    }

    private List<ExecutionEvent> waitingEvents() {
        Instant base = Instant.parse("2026-03-10T10:00:00Z");
        return List.of(
                new ExecutionEvent("RUN-1", "oa.leave.apply", "WORKFLOW", null, 1L,
                        ExecutionEventType.RUN_STARTED, ExecutionLifecycleStatus.RUNNING, base, Map.of("source", "artifact-runtime")),
                new ExecutionEvent("RUN-1", "oa.leave.apply", "WORKFLOW", "create_leave", 2L,
                        ExecutionEventType.STEP_STARTED, ExecutionLifecycleStatus.RUNNING, base.plusSeconds(1), Map.of("stepName", "创建请假记录")),
                new ExecutionEvent("RUN-1", "oa.leave.apply", "WORKFLOW", "create_leave", 3L,
                        ExecutionEventType.STEP_COMPLETED, ExecutionLifecycleStatus.COMPLETED, base.plusSeconds(2), Map.of("stepName", "创建请假记录")),
                new ExecutionEvent("RUN-1", "oa.leave.apply", "WORKFLOW", "submit_approval", 4L,
                        ExecutionEventType.STEP_WAITING_APPROVAL, ExecutionLifecycleStatus.WAITING_APPROVAL, base.plusSeconds(3), Map.of("stepName", "提交审批", "approvalRequestId", "RUN-1:submit_approval")));
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
    private FlowExecutionResult resumedResult() {
        FlowExecutionResult result = new FlowExecutionResult();
        result.setSuccess(true);
        result.setLifecycleStatus("COMPLETED");
        LinkedHashMap<String, StepStatus> stepStatuses = new LinkedHashMap<>();
        stepStatuses.put("submit_approval", StepStatus.COMPLETED);
        result.setStepStatuses(stepStatuses);
        LinkedHashMap<String, StepResult> stepResults = new LinkedHashMap<>();
        stepResults.put("submit_approval", StepResult.success(Map.of("message", "approved")));
        result.setStepResults(stepResults);
        result.setFinalOutputs(Map.of("message", "approved"));
        return result;
    }

    private List<ExecutionEvent> resumedEvents() {
        Instant base = Instant.parse("2026-03-10T10:10:00Z");
        return List.of(
                new ExecutionEvent("RUN-1", "oa.leave.apply", "WORKFLOW", null, 1L,
                        ExecutionEventType.RUN_RESUMED, ExecutionLifecycleStatus.RUNNING, base, Map.of("source", "artifact-runtime")),
                new ExecutionEvent("RUN-1", "oa.leave.apply", "WORKFLOW", "submit_approval", 2L,
                        ExecutionEventType.STEP_STARTED, ExecutionLifecycleStatus.RUNNING, base.plusSeconds(1), Map.of("stepName", "提交审批")),
                new ExecutionEvent("RUN-1", "oa.leave.apply", "WORKFLOW", "submit_approval", 3L,
                        ExecutionEventType.STEP_COMPLETED, ExecutionLifecycleStatus.COMPLETED, base.plusSeconds(2), Map.of("stepName", "提交审批")),
                new ExecutionEvent("RUN-1", "oa.leave.apply", "WORKFLOW", null, 4L,
                        ExecutionEventType.RUN_COMPLETED, ExecutionLifecycleStatus.COMPLETED, base.plusSeconds(3), Map.of("finalOutputs", Map.of("message", "approved"))));
    }
}


