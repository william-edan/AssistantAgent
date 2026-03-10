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
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.Map;

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

        FlowContext flowContext = buildFlowContext(descriptor, arguments, toolContext);
        FlowExecutionResult flowResult = dagFlowExecutor.execute(descriptor.artifact().getFlowDefinition(), flowContext);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("success", flowResult.isSuccess());
        payload.put("artifactCode", descriptor.artifact().getArtifactCode());
        payload.put("artifactType", descriptor.artifact().getArtifactType().name());
        payload.put("finalOutputs", flowResult.getFinalOutputs());
        payload.put("stepStatuses", flowResult.getStepStatuses());
        payload.put("stepResults", flowResult.getStepResults());
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
