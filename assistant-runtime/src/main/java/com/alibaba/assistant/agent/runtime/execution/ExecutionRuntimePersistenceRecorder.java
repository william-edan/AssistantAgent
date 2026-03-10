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
import com.alibaba.assistant.agent.execution.persistence.ExecutionRun;
import com.alibaba.assistant.agent.execution.persistence.ExecutionRunService;
import com.alibaba.assistant.agent.execution.persistence.ExecutionStep;
import com.alibaba.assistant.agent.execution.persistence.ExecutionStepService;
import com.alibaba.assistant.agent.runtime.compiler.RuntimeArtifact;
import com.alibaba.assistant.agent.runtime.registry.PublishedToolDescriptor;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Persists artifact execution results into execution run/step and audit records.
 */
@Service
public class ExecutionRuntimePersistenceRecorder {

    private final ExecutionRunService executionRunService;
    private final ExecutionStepService executionStepService;
    private final AuditEventService auditEventService;
    private final ObjectMapper objectMapper;

    @Autowired
    public ExecutionRuntimePersistenceRecorder(
            ExecutionRunService executionRunService,
            ExecutionStepService executionStepService,
            AuditEventService auditEventService,
            ObjectMapper objectMapper) {
        this.executionRunService = executionRunService;
        this.executionStepService = executionStepService;
        this.auditEventService = auditEventService;
        this.objectMapper = objectMapper;
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
        List<ExecutionEvent> safeEvents = executionEvents != null ? executionEvents : List.of();
        upsertRun(descriptor, flowContext, safeEvents, flowResult);
        upsertSteps(descriptor, runId, safeEvents);
        persistAuditEvents(descriptor, flowContext, runId, safeEvents);
    }

    private void upsertRun(
            PublishedToolDescriptor descriptor,
            FlowContext flowContext,
            List<ExecutionEvent> executionEvents,
            FlowExecutionResult flowResult) {
        ExecutionRun run = executionRunService.findLatestByRunId(flowContext.getRunId()).orElseGet(ExecutionRun::new);
        run.setRunId(flowContext.getRunId());
        run.setArtifactCode(descriptor.artifact().getArtifactCode());
        run.setArtifactType(descriptor.artifact().getArtifactType().name());
        run.setSpaceId(descriptor.artifact().getSpaceId());
        run.setPlatformPrincipalId(normalize(flowContext.getAssistantUid()));
        run.setThreadId(normalize(flowContext.getThreadId()));
        run.setStatus(resolveRunStatus(executionEvents, flowResult));
        run.setStartedAt(resolveRunTimestamp(executionEvents, ExecutionEventType.RUN_STARTED));
        run.setCompletedAt(resolveRunCompletedAt(executionEvents));
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
            step.setStartedAt(aggregate.startedAt);
            step.setCompletedAt(aggregate.completedAt);
            step.setErrorMessage(aggregate.errorMessage);
            saveOrUpdateStep(step);
        }
    }

    private void persistAuditEvents(
            PublishedToolDescriptor descriptor,
            FlowContext flowContext,
            String runId,
            List<ExecutionEvent> executionEvents) {
        for (ExecutionEvent event : executionEvents) {
            AuditEvent auditEvent = new AuditEvent();
            auditEvent.setEventId(runId + ":" + event.sequence());
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

    private void saveOrUpdateRun(ExecutionRun run) {
        if (run.getId() == null) {
            executionRunService.save(run);
        }
        else {
            executionRunService.updateById(run);
        }
    }

    private void saveOrUpdateStep(ExecutionStep step) {
        if (step.getId() == null) {
            executionStepService.save(step);
        }
        else {
            executionStepService.updateById(step);
        }
    }

    private String resolveRunStatus(List<ExecutionEvent> executionEvents, FlowExecutionResult flowResult) {
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

    private LocalDateTime resolveRunTimestamp(List<ExecutionEvent> executionEvents, ExecutionEventType type) {
        for (ExecutionEvent event : executionEvents) {
            if (event.eventType() == type) {
                return asLocalDateTime(event.occurredAt());
            }
        }
        return null;
    }

    private LocalDateTime resolveRunCompletedAt(List<ExecutionEvent> executionEvents) {
        for (int i = executionEvents.size() - 1; i >= 0; i--) {
            ExecutionEvent event = executionEvents.get(i);
            if (event.eventType() == ExecutionEventType.RUN_COMPLETED
                    || event.eventType() == ExecutionEventType.RUN_FAILED) {
                return asLocalDateTime(event.occurredAt());
            }
        }
        return null;
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
