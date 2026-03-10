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

import com.alibaba.assistant.agent.runtime.agent.AssistantStateKeys;
import com.alibaba.assistant.agent.runtime.registry.PublishedToolDescriptor;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.agent.tools.ToolContextConstants;
import com.alibaba.assistant.agent.execution.flow.DAGFlowExecutor;
import com.alibaba.assistant.agent.execution.flow.FlowContext;
import com.alibaba.assistant.agent.execution.flow.FlowExecutionResult;
import com.alibaba.assistant.agent.execution.model.StepDefinition;
import com.alibaba.assistant.agent.execution.model.StepResult;
import com.alibaba.assistant.agent.execution.model.StepStatus;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Executes published runtime artifacts through the new runtime flow definition.
 */
@Component
public class ArtifactRuntimeExecutor {

    private final DAGFlowExecutor dagFlowExecutor;

    public ArtifactRuntimeExecutor(DAGFlowExecutor dagFlowExecutor) {
        this.dagFlowExecutor = dagFlowExecutor;
    }

    /**
     * Execute a published artifact with the current runtime context.
     */
    public Map<String, Object> execute(
            PublishedToolDescriptor descriptor,
            Map<String, Object> arguments,
            @Nullable ToolContext toolContext) {
        if (descriptor == null || descriptor.artifact() == null) {
            return Map.of("success", false, "error", "Published artifact descriptor is missing");
        }

        String runId = UUID.randomUUID().toString();
        FlowContext flowContext = buildFlowContext(descriptor, arguments, toolContext);
        FlowExecutionResult flowResult = dagFlowExecutor.execute(descriptor.artifact().getFlowDefinition(), flowContext);
        List<ExecutionEvent> executionEvents = buildExecutionEvents(runId, descriptor, flowResult);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("success", flowResult.isSuccess());
        payload.put("runId", runId);
        payload.put("artifactCode", descriptor.artifact().getArtifactCode());
        payload.put("artifactType", descriptor.artifact().getArtifactType().name());
        payload.put("finalOutputs", flowResult.getFinalOutputs());
        payload.put("stepStatuses", flowResult.getStepStatuses());
        payload.put("stepResults", flowResult.getStepResults());
        payload.put("executionEvents", executionEvents);
        payload.put("durationMs", flowResult.getDurationMs());
        payload.put("error", flowResult.getErrorMessage());
        return payload;
    }

    private FlowContext buildFlowContext(
            PublishedToolDescriptor descriptor,
            Map<String, Object> arguments,
            @Nullable ToolContext toolContext) {
        Map<String, Object> safeArguments = arguments != null ? new LinkedHashMap<>(arguments) : new LinkedHashMap<>();
        FlowContext flowContext = new FlowContext(safeArguments);
        flowContext.setSystemCode(firstNonBlank(
                descriptor.executionSystemCode(),
                readContextText(toolContext, AssistantStateKeys.SYSTEM_CODE),
                readContextText(toolContext, "systemCode"),
                asText(safeArguments.get(AssistantStateKeys.SYSTEM_CODE)),
                asText(safeArguments.get("systemCode"))));
        flowContext.setAssistantUid(firstNonBlank(
                readContextText(toolContext, AssistantStateKeys.ASSISTANT_UID),
                readContextText(toolContext, "assistantUid"),
                asText(safeArguments.get(AssistantStateKeys.ASSISTANT_UID)),
                asText(safeArguments.get("assistantUid"))));
        flowContext.setThreadId(firstNonBlank(
                readContextText(toolContext, AssistantStateKeys.THREAD_ID),
                readContextText(toolContext, "thread_id"),
                asText(safeArguments.get(AssistantStateKeys.THREAD_ID)),
                asText(safeArguments.get("thread_id"))));
        return flowContext;
    }

    private List<ExecutionEvent> buildExecutionEvents(
            String runId,
            PublishedToolDescriptor descriptor,
            FlowExecutionResult flowResult) {
        if (descriptor == null || descriptor.artifact() == null || flowResult == null) {
            return List.of();
        }
        List<ExecutionEvent> events = new ArrayList<>();
        long sequence = 1L;
        events.add(new ExecutionEvent(
                runId,
                descriptor.artifact().getArtifactCode(),
                descriptor.artifact().getArtifactType().name(),
                null,
                sequence++,
                ExecutionEventType.RUN_STARTED,
                ExecutionLifecycleStatus.RUNNING,
                Instant.now(),
                Map.of("source", "artifact-runtime")));

        Map<String, StepResult> stepResults = flowResult.getStepResults() != null
                ? flowResult.getStepResults()
                : Map.of();
        Map<String, StepStatus> stepStatuses = flowResult.getStepStatuses() != null
                ? flowResult.getStepStatuses()
                : Map.of();
        for (Map.Entry<String, StepResult> entry : stepResults.entrySet()) {
            String stepId = entry.getKey();
            StepResult stepResult = entry.getValue();
            String stepName = resolveStepName(descriptor, stepId);
            Map<String, Object> startedPayload = new LinkedHashMap<>();
            if (StringUtils.hasText(stepName)) {
                startedPayload.put("stepName", stepName);
            }
            events.add(new ExecutionEvent(
                    runId,
                    descriptor.artifact().getArtifactCode(),
                    descriptor.artifact().getArtifactType().name(),
                    stepId,
                    sequence++,
                    ExecutionEventType.STEP_STARTED,
                    ExecutionLifecycleStatus.RUNNING,
                    Instant.now(),
                    startedPayload));

            StepStatus stepStatus = stepStatuses.get(stepId);
            if (stepStatus == StepStatus.COMPLETED && stepResult != null && stepResult.isSuccess()) {
                Map<String, Object> completedPayload = new LinkedHashMap<>();
                if (StringUtils.hasText(stepName)) {
                    completedPayload.put("stepName", stepName);
                }
                if (stepResult.getOutputs() != null && !stepResult.getOutputs().isEmpty()) {
                    completedPayload.put("outputs", stepResult.getOutputs());
                }
                events.add(new ExecutionEvent(
                        runId,
                        descriptor.artifact().getArtifactCode(),
                        descriptor.artifact().getArtifactType().name(),
                        stepId,
                        sequence++,
                        ExecutionEventType.STEP_COMPLETED,
                        ExecutionLifecycleStatus.COMPLETED,
                        Instant.now(),
                        completedPayload));
            }
            else {
                Map<String, Object> failedPayload = new LinkedHashMap<>();
                if (StringUtils.hasText(stepName)) {
                    failedPayload.put("stepName", stepName);
                }
                if (stepResult != null && StringUtils.hasText(stepResult.getErrorMessage())) {
                    failedPayload.put("error", stepResult.getErrorMessage());
                }
                events.add(new ExecutionEvent(
                        runId,
                        descriptor.artifact().getArtifactCode(),
                        descriptor.artifact().getArtifactType().name(),
                        stepId,
                        sequence++,
                        ExecutionEventType.STEP_FAILED,
                        ExecutionLifecycleStatus.FAILED,
                        Instant.now(),
                        failedPayload));
            }
        }

        Map<String, Object> terminalPayload = new LinkedHashMap<>();
        if (flowResult.getFinalOutputs() != null && !flowResult.getFinalOutputs().isEmpty()) {
            terminalPayload.put("finalOutputs", flowResult.getFinalOutputs());
        }
        if (StringUtils.hasText(flowResult.getErrorMessage())) {
            terminalPayload.put("error", flowResult.getErrorMessage());
        }
        events.add(new ExecutionEvent(
                runId,
                descriptor.artifact().getArtifactCode(),
                descriptor.artifact().getArtifactType().name(),
                null,
                sequence,
                flowResult.isSuccess() ? ExecutionEventType.RUN_COMPLETED : ExecutionEventType.RUN_FAILED,
                flowResult.isSuccess() ? ExecutionLifecycleStatus.COMPLETED : ExecutionLifecycleStatus.FAILED,
                Instant.now(),
                terminalPayload));
        return List.copyOf(events);
    }

    private String resolveStepName(PublishedToolDescriptor descriptor, String stepId) {
        if (descriptor == null || descriptor.artifact() == null || !StringUtils.hasText(stepId)) {
            return null;
        }
        if (descriptor.artifact().getSteps().containsKey(stepId)
                && StringUtils.hasText(descriptor.artifact().getSteps().get(stepId).stepName())) {
            return descriptor.artifact().getSteps().get(stepId).stepName();
        }
        if (descriptor.artifact().getFlowDefinition() == null || descriptor.artifact().getFlowDefinition().getSteps() == null) {
            return null;
        }
        StepDefinition stepDefinition = descriptor.artifact().getFlowDefinition().getSteps().get(stepId);
        return stepDefinition != null && StringUtils.hasText(stepDefinition.getName())
                ? stepDefinition.getName()
                : null;
    }

    private String readContextText(@Nullable ToolContext toolContext, String key) {
        if (toolContext == null || toolContext.getContext() == null || !StringUtils.hasText(key)) {
            return null;
        }
        Object direct = toolContext.getContext().get(key);
        if (direct != null) {
            return asText(direct);
        }
        Object rawState = toolContext.getContext().get(ToolContextConstants.AGENT_STATE_CONTEXT_KEY);
        if (rawState instanceof OverAllState state) {
            Object value = state.value(key, Object.class).orElse(null);
            return asText(value);
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
}
