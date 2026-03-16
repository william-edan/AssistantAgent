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
import com.alibaba.assistant.agent.execution.flow.FlowExecutionResult;
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
import com.alibaba.assistant.agent.runtime.registry.PublishedToolDescriptor;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 执行运行记录持久化器。
 *
 * <p>负责把一次动作或工作流执行过程拆分并落库到：
 * {@code execution_run}、{@code execution_step}、{@code approval_request} 和审计事件表。
 * 前端任务中心、控制面执行历史和审批列表最终都依赖这里写出的数据。</p>
 */
@Service
public class ExecutionRuntimePersistenceRecorder {


    private final ExecutionRunService executionRunService;
    private final ExecutionStepService executionStepService;
    private final ApprovalRequestService approvalRequestService;
    private final AuditEventService auditEventService;
    private final ObjectMapper objectMapper;

    private final RuntimeSpaceResolver runtimeSpaceResolver;

    public ExecutionRuntimePersistenceRecorder(
            ExecutionRunService executionRunService,
            ExecutionStepService executionStepService,
            ApprovalRequestService approvalRequestService,
            AuditEventService auditEventService,
            ObjectMapper objectMapper) {
        this(executionRunService, executionStepService, approvalRequestService, auditEventService, objectMapper, null);
    }

    @Autowired
    public ExecutionRuntimePersistenceRecorder(
            ExecutionRunService executionRunService,
            ExecutionStepService executionStepService,
            ApprovalRequestService approvalRequestService,
            AuditEventService auditEventService,
            ObjectMapper objectMapper,
            RuntimeSpaceResolver runtimeSpaceResolver) {
        this.executionRunService = executionRunService;
        this.executionStepService = executionStepService;
        this.approvalRequestService = approvalRequestService;
        this.auditEventService = auditEventService;
        this.objectMapper = objectMapper;
        this.runtimeSpaceResolver = runtimeSpaceResolver;
    }

    public void record(
            PublishedToolDescriptor descriptor,
            FlowContext flowContext,
            FlowExecutionResult flowResult,
            List<ExecutionEvent> executionEvents) {
        if (descriptor == null || descriptor.artifact() == null || flowContext == null || flowResult == null) {
            return;
        }
        String runId = normalize(flowContext.getRunId());
        if (!StringUtils.hasText(runId)) {
            return;
        }
        if ("WAITING_APPROVAL".equalsIgnoreCase(flowResult.getLifecycleStatus())
                && !StringUtils.hasText(flowResult.getApprovalRequestId())
                && StringUtils.hasText(flowResult.getPausedStepId())) {
            flowResult.setApprovalRequestId(runId + ":" + flowResult.getPausedStepId());
        }
        // 运行记录、步骤记录、审批记录和审计事件必须基于同一批执行事件生成，避免前后端读到不一致的数据。
        List<ExecutionEvent> safeEvents = executionEvents != null ? executionEvents : List.of();
        upsertRun(descriptor, flowContext, safeEvents, flowResult);
        upsertSteps(descriptor, runId, safeEvents);
        upsertApprovalRequest(descriptor, flowResult, safeEvents);
        persistAuditEvents(descriptor, flowContext, runId, safeEvents);
    }

    private void upsertRun(
            PublishedToolDescriptor descriptor,
            FlowContext flowContext,
            List<ExecutionEvent> executionEvents,
            FlowExecutionResult flowResult) {
        ExecutionRun run = executionRunService.findLatestByRunId(flowContext.getRunId()).orElseGet(ExecutionRun::new);
        Long resolvedSpaceId = resolveSpaceId(descriptor, flowContext);
        run.setRunId(flowContext.getRunId());
        run.setArtifactCode(descriptor.artifact().getArtifactCode());
        run.setArtifactType(descriptor.artifact().getArtifactType().name());
        run.setSpaceId(resolvedSpaceId);
        run.setPlatformPrincipalId(normalize(flowContext.getAssistantUid()));
        run.setThreadId(normalize(flowContext.getThreadId()));
        run.setStatus(resolveRunStatus(executionEvents, flowResult));
        run.setPausedStepId(normalize(flowResult.getPausedStepId()));
        run.setApprovalRequestId(normalize(flowResult.getApprovalRequestId()));
        run.setContextSnapshotJson(serializeSnapshot(flowContext, flowResult));
        run.setStartedAt(resolveRunStartedAt(run.getStartedAt(), executionEvents));
        run.setCompletedAt(resolveRunCompletedAt(executionEvents, flowResult));
        saveOrUpdateRun(run);
    }

    private void upsertSteps(PublishedToolDescriptor descriptor, String runId, List<ExecutionEvent> executionEvents) {
        if (executionEvents.isEmpty()) {
            return;
        }
        Map<String, StepAggregate> aggregates = new LinkedHashMap<>();
        for (ExecutionEvent event : executionEvents) {
            if (!StringUtils.hasText(event.stepId())) {
                continue;
            }
            StepAggregate aggregate = aggregates.computeIfAbsent(event.stepId(), StepAggregate::new);
            aggregate.accept(event);
        }
        for (StepAggregate aggregate : aggregates.values()) {
            ExecutionStep step = executionStepService.findByRunIdAndStepId(runId, aggregate.stepId)
                    .orElseGet(ExecutionStep::new);
            step.setRunId(runId);
            step.setStepId(aggregate.stepId);
            step.setStepName(aggregate.stepName);
            RuntimeArtifact.StepBinding stepBinding = descriptor.artifact().getSteps().get(aggregate.stepId);
            if (stepBinding != null) {
                step.setConnectorId(stepBinding.connectorId() != null
                        ? stepBinding.connectorId()
                        : stepBinding.action() != null ? stepBinding.action().connectorId() : null);
                step.setAuthProfileCode(resolveAuthProfileCode(stepBinding));
            }
            step.setStatus(aggregate.status);
            step.setStartedAt(resolveStepStartedAt(step.getStartedAt(), aggregate.startedAt));
            step.setCompletedAt(aggregate.completedAt);
            step.setErrorMessage(aggregate.errorMessage);
            saveOrUpdateStep(step);
        }
    }

    private void upsertApprovalRequest(
            PublishedToolDescriptor descriptor,
            FlowExecutionResult flowResult,
            List<ExecutionEvent> executionEvents) {
        if (!"WAITING_APPROVAL".equalsIgnoreCase(flowResult.getLifecycleStatus())
                || !StringUtils.hasText(flowResult.getPausedStepId())
                || !StringUtils.hasText(flowResult.getApprovalRequestId())) {
            return;
        }
        ApprovalRequest approvalRequest = approvalRequestService
                .findLatestPendingByRunAndStep(extractRunId(flowResult.getApprovalRequestId()), flowResult.getPausedStepId())
                .orElseGet(ApprovalRequest::new);
        approvalRequest.setRequestId(flowResult.getApprovalRequestId());
        approvalRequest.setRunId(extractRunId(flowResult.getApprovalRequestId()));
        approvalRequest.setStepId(flowResult.getPausedStepId());
        approvalRequest.setApprovalChannel(resolveApprovalChannel(descriptor, flowResult.getPausedStepId()));
        approvalRequest.setStatus("WAITING_APPROVAL");
        approvalRequest.setRequestedAt(resolveStepWaitingApprovalAt(executionEvents, flowResult.getPausedStepId()));
        saveOrUpdateApprovalRequest(approvalRequest);
    }

    private void persistAuditEvents(
            PublishedToolDescriptor descriptor,
            FlowContext flowContext,
            String runId,
            List<ExecutionEvent> executionEvents) {
        for (ExecutionEvent event : executionEvents) {
            AuditEvent auditEvent = new AuditEvent();
            auditEvent.setEventId(buildAuditEventId(runId, event));
            auditEvent.setTraceId(runId);
            auditEvent.setExecutionId(runId);
            auditEvent.setRunId(runId);
            auditEvent.setStepId(normalize(event.stepId()));
            auditEvent.setEventType(event.eventType().name());
            auditEvent.setThreadId(normalize(flowContext.getThreadId()));
            auditEvent.setAssistantUid(normalize(flowContext.getAssistantUid()));
            auditEvent.setSystemCode(firstNonBlank(flowContext.getSystemCode(), descriptor.executionSystemCode()));
            auditEvent.setToolName(descriptor.artifact().getArtifactCode());
            auditEvent.setAgentPhase(event.lifecycleStatus().name());
            auditEvent.setToolOutput(serializePayload(event.payload()));
            auditEvent.setStatus(event.lifecycleStatus().name());
            auditEvent.setErrorMessage(extractError(event.payload()));
            auditEvent.setCreatedAt(asLocalDateTime(event.occurredAt()));
            auditEventService.save(auditEvent);
        }
    }

    /**
     * 使用运行轨迹生成确定性事件 ID，避免恢复执行时 sequence 重置导致主键冲突。
     */
    private String buildAuditEventId(String runId, ExecutionEvent event) {
        String fingerprint = firstNonBlank(
                normalize(runId),
                "") + "|" + firstNonBlank(normalize(event.stepId()), "-")
                + "|" + event.eventType().name()
                + "|" + event.lifecycleStatus().name()
                + "|" + (event.occurredAt() != null ? event.occurredAt().toEpochMilli() : 0L)
                + "|" + event.sequence();
        return UUID.nameUUIDFromBytes(fingerprint.getBytes(StandardCharsets.UTF_8)).toString();
    }

    private void saveOrUpdateRun(ExecutionRun run) {
        if (run.getId() == null) {
            executionRunService.save(run);
        }
        else {
            executionRunService.updateById(run);
        }
    }

    private Long resolveSpaceId(PublishedToolDescriptor descriptor, FlowContext flowContext) {
        Long artifactSpaceId = descriptor != null && descriptor.artifact() != null
                ? descriptor.artifact().getSpaceId()
                : null;
        if (artifactSpaceId != null) {
            return artifactSpaceId;
        }
        if (flowContext == null || flowContext.getInitialInputs().isEmpty()) {
            return null;
        }
        Long resolvedSpaceId = firstNonNull(
                asLong(flowContext.getInitialInputs().get(AssistantStateKeys.SPACE_ID)),
                asLong(flowContext.getInitialInputs().get("space_id")),
                asLong(flowContext.getInitialInputs().get("spaceId")));
        if (resolvedSpaceId != null || runtimeSpaceResolver == null) {
            return resolvedSpaceId;
        }
        return runtimeSpaceResolver.resolve(flowContext.getInitialInputs()).spaceId();
    }

    private void saveOrUpdateStep(ExecutionStep step) {
        if (step.getId() == null) {
            executionStepService.save(step);
        }
        else {
            executionStepService.updateById(step);
        }
    }

    private void saveOrUpdateApprovalRequest(ApprovalRequest approvalRequest) {
        if (approvalRequest.getId() == null) {
            approvalRequestService.save(approvalRequest);
        }
        else {
            approvalRequestService.updateById(approvalRequest);
        }
    }

    private String resolveRunStatus(List<ExecutionEvent> executionEvents, FlowExecutionResult flowResult) {
        if (StringUtils.hasText(flowResult.getLifecycleStatus())) {
            return flowResult.getLifecycleStatus().trim();
        }
        for (int i = executionEvents.size() - 1; i >= 0; i--) {
            ExecutionEvent event = executionEvents.get(i);
            if (event.eventType() == ExecutionEventType.RUN_COMPLETED
                    || event.eventType() == ExecutionEventType.RUN_FAILED) {
                return event.lifecycleStatus().name();
            }
        }
        return flowResult.isSuccess()
                ? ExecutionLifecycleStatus.COMPLETED.name()
                : ExecutionLifecycleStatus.FAILED.name();
    }

    private LocalDateTime resolveRunTimestamp(List<ExecutionEvent> executionEvents, ExecutionEventType... types) {
        for (ExecutionEvent event : executionEvents) {
            for (ExecutionEventType type : types) {
                if (event.eventType() == type) {
                    return asLocalDateTime(event.occurredAt());
                }
            }
        }
        return null;
    }

    private LocalDateTime resolveRunStartedAt(LocalDateTime existingStartedAt, List<ExecutionEvent> executionEvents) {
        LocalDateTime startedAt = resolveRunTimestamp(executionEvents, ExecutionEventType.RUN_STARTED);
        if (startedAt != null) {
            return startedAt;
        }
        if (existingStartedAt != null) {
            return existingStartedAt;
        }
        return resolveRunTimestamp(executionEvents, ExecutionEventType.RUN_RESUMED);
    }

    private LocalDateTime resolveRunCompletedAt(List<ExecutionEvent> executionEvents, FlowExecutionResult flowResult) {
        if ("WAITING_APPROVAL".equalsIgnoreCase(flowResult.getLifecycleStatus())) {
            return null;
        }
        for (int i = executionEvents.size() - 1; i >= 0; i--) {
            ExecutionEvent event = executionEvents.get(i);
            if (event.eventType() == ExecutionEventType.RUN_COMPLETED
                    || event.eventType() == ExecutionEventType.RUN_FAILED) {
                return asLocalDateTime(event.occurredAt());
            }
        }
        return null;
    }

    private LocalDateTime resolveStepWaitingApprovalAt(List<ExecutionEvent> executionEvents, String stepId) {
        for (ExecutionEvent event : executionEvents) {
            if (event.eventType() == ExecutionEventType.STEP_WAITING_APPROVAL
                    && stepId.equals(event.stepId())) {
                return asLocalDateTime(event.occurredAt());
            }
        }
        return LocalDateTime.now();
    }

    private String resolveApprovalChannel(PublishedToolDescriptor descriptor, String stepId) {
        if (descriptor == null || descriptor.artifact() == null || !StringUtils.hasText(stepId)) {
            return null;
        }
        RuntimeArtifact.StepBinding stepBinding = descriptor.artifact().getSteps().get(stepId);
        if (stepBinding == null || !StringUtils.hasText(stepBinding.approvalGateJson())) {
            return null;
        }
        try {
            Map<String, Object> json = objectMapper.readValue(stepBinding.approvalGateJson(), Map.class);
            Object channel = json.get("channel");
            return channel != null ? normalize(String.valueOf(channel)) : null;
        }
        catch (Exception ignored) {
            return null;
        }
    }

    private LocalDateTime resolveStepStartedAt(LocalDateTime existingStartedAt, LocalDateTime aggregateStartedAt) {
        return existingStartedAt != null ? existingStartedAt : aggregateStartedAt;
    }

    private String resolveAuthProfileCode(RuntimeArtifact.StepBinding stepBinding) {
        if (stepBinding == null) {
            return null;
        }
        if (stepBinding.action() != null && StringUtils.hasText(stepBinding.action().defaultAuthProfileCode())) {
            return stepBinding.action().defaultAuthProfileCode().trim();
        }
        return null;
    }

    private String serializeSnapshot(FlowContext flowContext, FlowExecutionResult flowResult) {
        if (flowContext == null || flowResult == null) {
            return null;
        }
        if (!"WAITING_APPROVAL".equalsIgnoreCase(flowResult.getLifecycleStatus())) {
            return null;
        }
        Map<String, String> stepStatuses = new LinkedHashMap<>();
        if (flowResult.getStepStatuses() != null) {
            for (Map.Entry<String, StepStatus> entry : flowResult.getStepStatuses().entrySet()) {
                if (entry.getValue() != null) {
                    stepStatuses.put(entry.getKey(), entry.getValue().name());
                }
            }
        }
        ExecutionContextSnapshot snapshot = new ExecutionContextSnapshot(
                flowContext.getSystemCode(),
                flowContext.getInitialInputs(),
                flowContext.getStepOutputsSnapshot(),
                stepStatuses);
        try {
            return objectMapper.writeValueAsString(snapshot);
        }
        catch (JsonProcessingException ignored) {
            return null;
        }
    }

    private String extractError(Map<String, Object> payload) {
        if (payload == null || payload.isEmpty()) {
            return null;
        }
        Object error = payload.get("error");
        return error != null ? normalize(String.valueOf(error)) : null;
    }

    private String serializePayload(Map<String, Object> payload) {
        if (payload == null || payload.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(payload);
        }
        catch (JsonProcessingException ignored) {
            return payload.toString();
        }
    }

    private LocalDateTime asLocalDateTime(Instant instant) {
        if (instant == null) {
            return null;
        }
        return LocalDateTime.ofInstant(instant, ZoneId.systemDefault());
    }

    private String extractRunId(String requestId) {
        if (!StringUtils.hasText(requestId) || !requestId.contains(":")) {
            return normalize(requestId);
        }
        return normalize(requestId.substring(0, requestId.indexOf(':')));
    }

    private Long asLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        if (!StringUtils.hasText(text)) {
            return null;
        }
        try {
            return Long.parseLong(text);
        }
        catch (NumberFormatException ignored) {
            return null;
        }
    }

    @SafeVarargs
    private final <T> T firstNonNull(T... values) {
        if (values == null) {
            return null;
        }
        for (T value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private String normalize(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value.trim();
            }
        }
        return null;
    }

    private final class StepAggregate {

        private final String stepId;
        private String stepName;
        private String status;
        private LocalDateTime startedAt;
        private LocalDateTime completedAt;
        private String errorMessage;

        private StepAggregate(String stepId) {
            this.stepId = stepId;
        }

        private void accept(ExecutionEvent event) {
            if (event.payload() != null && StringUtils.hasText(stringValue(event.payload().get("stepName")))) {
                this.stepName = stringValue(event.payload().get("stepName"));
            }
            if (event.eventType() == ExecutionEventType.STEP_STARTED) {
                this.startedAt = asLocalDateTime(event.occurredAt());
                this.status = event.lifecycleStatus().name();
            }
            else if (event.eventType() == ExecutionEventType.STEP_COMPLETED
                    || event.eventType() == ExecutionEventType.STEP_FAILED
                    || event.eventType() == ExecutionEventType.STEP_WAITING_APPROVAL) {
                this.completedAt = asLocalDateTime(event.occurredAt());
                this.status = event.lifecycleStatus().name();
                this.errorMessage = event.payload() != null ? stringValue(event.payload().get("error")) : null;
            }
        }

        private String stringValue(Object value) {
            if (value == null) {
                return null;
            }
            String text = String.valueOf(value).trim();
            return StringUtils.hasText(text) ? text : null;
        }
    }
}
