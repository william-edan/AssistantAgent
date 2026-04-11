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
package com.alibaba.assistant.agent.start.invoice.hook;

import com.alibaba.assistant.agent.api.protocol.FrontendStage;
import com.alibaba.assistant.agent.common.constant.HookPriorityConstants;
import com.alibaba.assistant.agent.common.hook.AgentPhase;
import com.alibaba.assistant.agent.common.hook.HookPhases;
import com.alibaba.assistant.agent.runtime.agent.AssistantStateKeys;
import com.alibaba.assistant.agent.start.invoice.intent.InvoiceApplyIntentRecognizer;
import com.alibaba.assistant.agent.start.invoice.tool.InvoiceApplyFormTool;
import com.alibaba.assistant.agent.start.invoice.tool.InvoiceApplyTool;
import com.alibaba.cloud.ai.graph.KeyStrategy;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.Prioritized;
import com.alibaba.cloud.ai.graph.agent.hook.AgentHook;
import com.alibaba.cloud.ai.graph.agent.hook.HookPosition;
import com.alibaba.cloud.ai.graph.agent.hook.HookPositions;
import com.alibaba.cloud.ai.graph.agent.hook.JumpTo;
import com.alibaba.cloud.ai.graph.state.strategy.ReplaceStrategy;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.context.annotation.Profile;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * 开票申请快速意图 Hook。
 */
@Component
@Profile("migration")
@HookPhases(AgentPhase.REACT)
@HookPositions(HookPosition.BEFORE_AGENT)
public class InvoiceApplyFastIntentHook extends AgentHook implements Prioritized {

    private final ObjectMapper objectMapper;

    private final InvoiceApplyIntentRecognizer intentRecognizer;

    public InvoiceApplyFastIntentHook(ObjectMapper objectMapper, InvoiceApplyIntentRecognizer intentRecognizer) {
        this.objectMapper = objectMapper;
        this.intentRecognizer = intentRecognizer;
    }

    @Override
    public String getName() {
        return "InvoiceApplyFastIntentHook";
    }

    @Override
    public int getOrder() {
        return HookPriorityConstants.FAST_INTENT_HOOK - 79;
    }

    @Override
    public List<JumpTo> canJumpTo() {
        return List.of(JumpTo.tool, JumpTo.model);
    }

    @Override
    public Map<String, KeyStrategy> getKeyStrategys() {
        return Map.of("jump_to", new ReplaceStrategy());
    }

    @Override
    public CompletableFuture<Map<String, Object>> beforeAgent(OverAllState state, RunnableConfig config) {
        String userInput = resolveUserInput(state);
        PendingInvoiceForm pendingInvoiceForm = resolvePendingInvoiceForm(state);
        boolean hasStructuredSlotInputs = hasMeaningfulSlotInputs(state);

        if (pendingInvoiceForm.active()) {
            if (!StringUtils.hasText(userInput) && !hasStructuredSlotInputs) {
                return CompletableFuture.completedFuture(Map.of());
            }
            boolean confirmed = pendingInvoiceForm.confirming()
                    && (hasStructuredSlotInputs || isConfirmText(userInput));
            String toolName = confirmed ? InvoiceApplyTool.TOOL_NAME : InvoiceApplyFormTool.TOOL_NAME;
            String routeType = confirmed ? "INVOICE_APPLY_SUBMIT" : "INVOICE_APPLY_CONTINUE";
            return CompletableFuture.completedFuture(buildToolUpdates(
                    toolName,
                    userInput,
                    confirmed,
                    routeType,
                    state));
        }

        InvoiceApplyIntentRecognizer.RecognitionResult recognitionResult = intentRecognizer.recognize(userInput);
        if (!recognitionResult.matched()) {
            return CompletableFuture.completedFuture(Map.of());
        }

        return CompletableFuture.completedFuture(buildToolUpdates(
                InvoiceApplyFormTool.TOOL_NAME,
                userInput,
                false,
                recognitionResult.intentCode(),
                state));
    }

    private Map<String, Object> buildToolUpdates(
            String toolName,
            String userInput,
            boolean confirmed,
            String routeType,
            @Nullable OverAllState state) {
        try {
            Map<String, Object> toolArgs = new LinkedHashMap<>();
            toolArgs.put("userInput", Optional.ofNullable(userInput).orElse(""));

            Map<String, Object> slotInputs = extractCurrentTurnSlotInputs(state);
            if (!slotInputs.isEmpty()) {
                toolArgs.put("slotInputs", slotInputs);
            }

            Map<String, Object> frontendThreadState = extractFrontendThreadState(state);
            if (InvoiceApplyTool.TOOL_NAME.equals(toolName)) {
                toolArgs.put("confirmed", confirmed);
                if (!frontendThreadState.isEmpty()) {
                    toolArgs.put("frontendThreadState", frontendThreadState);
                }
            }
            else if (InvoiceApplyFormTool.TOOL_NAME.equals(toolName)) {
                Map<String, Object> pendingValues = extractPendingFormValues(frontendThreadState);
                if (!pendingValues.isEmpty()) {
                    toolArgs.put("values", pendingValues);
                }
            }

            AssistantMessage assistantMessage = AssistantMessage.builder()
                    .content("")
                    .toolCalls(List.of(new AssistantMessage.ToolCall(
                            "invoice_apply_fast_intent_" + UUID.randomUUID().toString().substring(0, 8),
                            "function",
                            toolName,
                            objectMapper.writeValueAsString(toolArgs))))
                    .build();

            Map<String, Object> fastIntentState = new LinkedHashMap<>();
            fastIntentState.put("hit", true);
            fastIntentState.put("route_type", routeType);
            fastIntentState.put("tool_code", toolName);

            Map<String, Object> updates = new LinkedHashMap<>();
            updates.put("messages", List.of(assistantMessage));
            updates.put("jump_to", JumpTo.tool);
            updates.put("fast_intent", fastIntentState);
            return updates;
        }
        catch (Exception exception) {
            return Map.of();
        }
    }

    private boolean isConfirmText(String userInput) {
        if (!StringUtils.hasText(userInput)) {
            return false;
        }
        return userInput.contains("确认")
                || userInput.contains("提交")
                || userInput.contains("确定")
                || userInput.contains("可以");
    }

    private PendingInvoiceForm resolvePendingInvoiceForm(@Nullable OverAllState state) {
        if (state == null) {
            return PendingInvoiceForm.inactive();
        }
        Object raw = state.value(AssistantStateKeys.FRONTEND_THREAD_STATE, Object.class).orElse(null);
        if (!(raw instanceof Map<?, ?> frontendThreadState)) {
            return PendingInvoiceForm.inactive();
        }
        Object rawPendingForm = frontendThreadState.get("pendingForm");
        if (!(rawPendingForm instanceof Map<?, ?> pendingForm)) {
            return PendingInvoiceForm.inactive();
        }
        String toolCode = asText(pendingForm.get("toolCode"));
        if (!InvoiceApplyTool.TOOL_NAME.equals(toolCode) || Boolean.TRUE.equals(pendingForm.get("readOnly"))) {
            return PendingInvoiceForm.inactive();
        }
        String phase = firstText(pendingForm.get("phase"), frontendThreadState.get("phase"));
        String mode = asText(pendingForm.get("mode"));
        boolean confirming = "CONFIRM".equalsIgnoreCase(mode)
                || FrontendStage.CONFIRMING.name().equalsIgnoreCase(phase);
        return new PendingInvoiceForm(true, confirming);
    }

    private Map<String, Object> extractCurrentTurnSlotInputs(@Nullable OverAllState state) {
        if (state == null) {
            return Map.of();
        }
        Object raw = state.value(AssistantStateKeys.CURRENT_TURN_SLOT_INPUTS, Object.class).orElse(null);
        if (!(raw instanceof Map<?, ?> slotInputs) || slotInputs.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> normalized = new LinkedHashMap<>();
        slotInputs.forEach((key, value) -> {
            if (key != null && value != null) {
                normalized.put(String.valueOf(key), value);
            }
        });
        return normalized;
    }

    private Map<String, Object> extractFrontendThreadState(@Nullable OverAllState state) {
        if (state == null) {
            return Map.of();
        }
        Object raw = state.value(AssistantStateKeys.FRONTEND_THREAD_STATE, Object.class).orElse(null);
        if (!(raw instanceof Map<?, ?> frontendThreadState) || frontendThreadState.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> normalized = new LinkedHashMap<>();
        frontendThreadState.forEach((key, value) -> {
            if (key != null && value != null) {
                normalized.put(String.valueOf(key), value);
            }
        });
        return normalized;
    }

    private Map<String, Object> extractPendingFormValues(Map<String, Object> frontendThreadState) {
        if (frontendThreadState == null || frontendThreadState.isEmpty()) {
            return Map.of();
        }
        Object rawPendingForm = frontendThreadState.get("pendingForm");
        if (!(rawPendingForm instanceof Map<?, ?> pendingForm)) {
            return Map.of();
        }
        Object rawValues = pendingForm.get("values");
        if (!(rawValues instanceof Map<?, ?> values) || values.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> normalized = new LinkedHashMap<>();
        values.forEach((key, value) -> {
            if (key != null && value != null) {
                normalized.put(String.valueOf(key), value);
            }
        });
        return normalized;
    }

    private boolean hasMeaningfulSlotInputs(@Nullable OverAllState state) {
        if (state == null) {
            return false;
        }
        Object raw = state.value(AssistantStateKeys.CURRENT_TURN_SLOT_INPUTS, Object.class).orElse(null);
        if (!(raw instanceof Map<?, ?> slotInputs) || slotInputs.isEmpty()) {
            return false;
        }
        return slotInputs.entrySet().stream()
                .filter(entry -> entry.getKey() != null)
                .anyMatch(entry -> hasMeaningfulValue(entry.getValue()));
    }

    private boolean hasMeaningfulValue(Object value) {
        if (value == null) {
            return false;
        }
        if (value instanceof String text) {
            return StringUtils.hasText(text);
        }
        if (value instanceof Map<?, ?> map) {
            return !map.isEmpty();
        }
        if (value instanceof List<?> list) {
            return !list.isEmpty();
        }
        return true;
    }

    private String resolveUserInput(@Nullable OverAllState state) {
        if (state == null) {
            return "";
        }
        return Optional.ofNullable(state.value(AssistantStateKeys.CURRENT_TURN_USER_INPUT, String.class).orElse(null))
                .filter(StringUtils::hasText)
                .orElseGet(() -> state.value("input", String.class).orElse(""));
    }

    private String firstText(Object... values) {
        if (values == null) {
            return null;
        }
        for (Object value : values) {
            String text = asText(value);
            if (StringUtils.hasText(text)) {
                return text;
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

    private record PendingInvoiceForm(boolean active, boolean confirming) {

        private static PendingInvoiceForm inactive() {
            return new PendingInvoiceForm(false, false);
        }
    }
}
