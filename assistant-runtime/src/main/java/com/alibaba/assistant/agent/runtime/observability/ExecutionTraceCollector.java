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
package com.alibaba.assistant.agent.runtime.observability;

import com.alibaba.assistant.agent.execution.flow.FlowContext;
import com.alibaba.assistant.agent.execution.flow.FlowExecutionResult;
import com.alibaba.assistant.agent.execution.persistence.ExecutionSpan;
import com.alibaba.assistant.agent.execution.persistence.ExecutionSpanService;
import com.alibaba.assistant.agent.execution.persistence.ExecutionTrace;
import com.alibaba.assistant.agent.execution.persistence.ExecutionTraceService;
import com.alibaba.assistant.agent.runtime.agent.AssistantStateKeys;
import com.alibaba.assistant.agent.runtime.execution.ExecutionEvent;
import com.alibaba.assistant.agent.runtime.execution.ExecutionEventType;
import com.alibaba.assistant.agent.runtime.registry.PublishedToolDescriptor;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Persists business execution traces and publishes Micrometer observations.
 */
@Component
public class ExecutionTraceCollector {

    private final ExecutionTraceService executionTraceService;

    private final ExecutionSpanService executionSpanService;

    private final ObservationRegistry observationRegistry;

    private final CostAccountant costAccountant;

    private final ObjectMapper objectMapper;

    public ExecutionTraceCollector(
            ExecutionTraceService executionTraceService,
            ExecutionSpanService executionSpanService,
            @Nullable ObservationRegistry observationRegistry,
            CostAccountant costAccountant) {
        this(executionTraceService, executionSpanService, observationRegistry, costAccountant, new ObjectMapper());
    }

    @Autowired
    public ExecutionTraceCollector(
            ExecutionTraceService executionTraceService,
            ExecutionSpanService executionSpanService,
            @Nullable ObservationRegistry observationRegistry,
            CostAccountant costAccountant,
            ObjectMapper objectMapper) {
        this.executionTraceService = executionTraceService;
        this.executionSpanService = executionSpanService;
        this.observationRegistry = observationRegistry != null ? observationRegistry : ObservationRegistry.NOOP;
        this.costAccountant = costAccountant;
        this.objectMapper = objectMapper;
    }

    public void collect(
            PublishedToolDescriptor descriptor,
            FlowContext flowContext,
            FlowExecutionResult flowResult,
            List<ExecutionEvent> executionEvents) {
        if (descriptor == null || flowContext == null || flowResult == null || !StringUtils.hasText(flowContext.getRunId())) {
            return;
        }
        TraceSnapshot snapshot = buildSnapshot(descriptor, flowContext, flowResult, executionEvents != null ? executionEvents : List.of());
        persist(snapshot);
        publishObservation(snapshot);
    }

    private TraceSnapshot buildSnapshot(
            PublishedToolDescriptor descriptor,
            FlowContext flowContext,
            FlowExecutionResult flowResult,
            List<ExecutionEvent> executionEvents) {
        Map<String, StepSpanAggregate> stepAggregates = new LinkedHashMap<>();
        Instant runStartedAt = null;
        Instant runCompletedAt = null;
        for (ExecutionEvent event : executionEvents) {
            if (event == null) {
                continue;
            }
            if (event.eventType() == ExecutionEventType.RUN_STARTED || event.eventType() == ExecutionEventType.RUN_RESUMED) {
                runStartedAt = firstNonNull(runStartedAt, event.occurredAt());
            }
            if (event.eventType() == ExecutionEventType.RUN_COMPLETED || event.eventType() == ExecutionEventType.RUN_FAILED) {
                runCompletedAt = event.occurredAt();
            }
            if (!StringUtils.hasText(event.stepId())) {
                continue;
            }
            stepAggregates.computeIfAbsent(event.stepId(), StepSpanAggregate::new).accept(event);
        }
        String runId = flowContext.getRunId();
        String lifecycleStatus = normalizeStatus(flowResult.getLifecycleStatus());
        LocalDateTime startedAt = asLocalDateTime(runStartedAt);
        LocalDateTime completedAt = asLocalDateTime(runCompletedAt);
        Long durationMs = flowResult.getDurationMs() > 0
                ? flowResult.getDurationMs()
                : (runStartedAt != null && runCompletedAt != null ? runCompletedAt.toEpochMilli() - runStartedAt.toEpochMilli() : null);

        ExecutionTrace trace = executionTraceService.findLatestByRunId(runId).orElseGet(ExecutionTrace::new);
        trace.setTraceId(runId);
        trace.setRunId(runId);
        trace.setSpaceId(asLong(flowContext.getInitialInputs().get(AssistantStateKeys.SPACE_ID)));
        trace.setAgentAppCode(asText(flowContext.getInitialInputs().get(AssistantStateKeys.AGENT_APP_CODE)));
        trace.setRolePackageCode(asText(flowContext.getInitialInputs().get(AssistantStateKeys.ROLE_PACKAGE_CODE)));
        trace.setRolePackageVersion(asText(flowContext.getInitialInputs().get(AssistantStateKeys.ROLE_PACKAGE_VERSION)));
        trace.setScenarioCode(asText(flowContext.getInitialInputs().get(AssistantStateKeys.ROLE_SCENARIO_CODE)));
        trace.setPlatformPrincipalId(firstNonBlank(
                asText(flowContext.getInitialInputs().get(AssistantStateKeys.PLATFORM_PRINCIPAL_ID)),
                flowContext.getAssistantUid()));
        trace.setThreadId(flowContext.getThreadId());
        trace.setArtifactCode(descriptor.artifact() != null ? descriptor.artifact().getArtifactCode() : null);
        trace.setArtifactType(descriptor.artifact() != null ? descriptor.artifact().getArtifactType().name() : descriptor.toolType());
        trace.setStatus(lifecycleStatus);
        trace.setProactive(StringUtils.hasText(asText(flowContext.getInitialInputs().get(AssistantStateKeys.PROACTIVE_TASK_CODE))));
        trace.setTotalSteps(stepAggregates.size());
        trace.setCompletedSteps((int) stepAggregates.values().stream()
                .filter(aggregate -> "COMPLETED".equalsIgnoreCase(aggregate.status))
                .count());
        trace.setDurationMs(durationMs);
        trace.setEstimatedCost(costAccountant.estimate(executionEvents));
        trace.setStartedAt(startedAt);
        trace.setCompletedAt(completedAt);

        List<ExecutionSpan> spans = new ArrayList<>();
        for (StepSpanAggregate aggregate : stepAggregates.values()) {
            ExecutionSpan span = new ExecutionSpan();
            span.setTraceId(runId);
            span.setRunId(runId);
            span.setStepId(aggregate.stepId);
            span.setSpanType("STEP");
            span.setSpanName(aggregate.stepName);
            span.setStatus(aggregate.status);
            span.setSequenceNo(aggregate.sequenceNo);
            span.setDurationMs(aggregate.durationMs());
            span.setPayloadJson(serializePayload(aggregate.lastPayload));
            span.setStartedAt(asLocalDateTime(aggregate.startedAt));
            span.setCompletedAt(asLocalDateTime(aggregate.completedAt));
            spans.add(span);
        }
        return new TraceSnapshot(trace, spans);
    }

    private void persist(TraceSnapshot snapshot) {
        ExecutionTrace trace = snapshot.trace();
        if (trace.getId() == null) {
            executionTraceService.save(trace);
        }
        else {
            executionTraceService.updateById(trace);
        }
        executionSpanService.deleteByRunId(trace.getRunId());
        if (!snapshot.spans().isEmpty()) {
            executionSpanService.saveBatch(snapshot.spans());
        }
    }

    private void publishObservation(TraceSnapshot snapshot) {
        ExecutionTrace trace = snapshot.trace();
        Observation.createNotStarted("assistant.execution.trace", observationRegistry)
                .lowCardinalityKeyValue("artifact.code", firstNonBlank(trace.getArtifactCode(), "unknown"))
                .lowCardinalityKeyValue("role.package.code", firstNonBlank(trace.getRolePackageCode(), "none"))
                .lowCardinalityKeyValue("role.scenario.code", firstNonBlank(trace.getScenarioCode(), "none"))
                .highCardinalityKeyValue("run.id", firstNonBlank(trace.getRunId(), "unknown"))
                .observe(() -> {
                });
    }

    private String normalizeStatus(String lifecycleStatus) {
        if (!StringUtils.hasText(lifecycleStatus)) {
            return "RUNNING";
        }
        return lifecycleStatus.trim().toUpperCase();
    }

    private Long asLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String text && StringUtils.hasText(text)) {
            try {
                return Long.parseLong(text.trim());
            }
            catch (NumberFormatException ignore) {
                return null;
            }
        }
        return null;
    }

    private String asText(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return StringUtils.hasText(text) ? text : null;
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value;
            }
        }
        return null;
    }

    private <T> T firstNonNull(T first, T second) {
        return first != null ? first : second;
    }

    private LocalDateTime asLocalDateTime(Instant instant) {
        if (instant == null) {
            return null;
        }
        return LocalDateTime.ofInstant(instant, ZoneId.systemDefault());
    }

    private String serializePayload(Map<String, Object> payload) {
        if (payload == null || payload.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(payload);
        }
        catch (JsonProcessingException ex) {
            return "{\"serializationError\":\"" + ex.getClass().getSimpleName() + "\"}";
        }
    }

    private record TraceSnapshot(ExecutionTrace trace, List<ExecutionSpan> spans) {
    }

    private static final class StepSpanAggregate {

        private final String stepId;

        private String stepName;

        private String status = "RUNNING";

        private Long sequenceNo = 0L;

        private Instant startedAt;

        private Instant completedAt;

        private Map<String, Object> lastPayload = Map.of();

        private StepSpanAggregate(String stepId) {
            this.stepId = stepId;
        }

        private void accept(ExecutionEvent event) {
            this.sequenceNo = event.sequence();
            this.stepName = firstNonBlank(asText(event.payload().get("stepName")), this.stepName, stepId);
            this.lastPayload = event.payload();
            switch (event.eventType()) {
                case STEP_STARTED -> this.startedAt = firstNonNull(this.startedAt, event.occurredAt());
                case STEP_COMPLETED, STEP_FAILED, STEP_WAITING_APPROVAL -> {
                    this.completedAt = event.occurredAt();
                    this.status = event.lifecycleStatus().name();
                    if (this.startedAt == null) {
                        this.startedAt = event.occurredAt();
                    }
                }
                default -> {
                }
            }
        }

        private Long durationMs() {
            if (startedAt == null || completedAt == null) {
                return null;
            }
            return completedAt.toEpochMilli() - startedAt.toEpochMilli();
        }

        private static <T> T firstNonNull(T first, T second) {
            return first != null ? first : second;
        }

        private static String asText(Object value) {
            return value != null ? String.valueOf(value) : null;
        }

        private static String firstNonBlank(String... values) {
            if (values == null) {
                return null;
            }
            for (String value : values) {
                if (StringUtils.hasText(value)) {
                    return value;
                }
            }
            return null;
        }
    }
}



