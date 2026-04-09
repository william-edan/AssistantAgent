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
package com.alibaba.assistant.agent.start.car.hook;

import com.alibaba.assistant.agent.api.protocol.FrontendStage;
import com.alibaba.assistant.agent.common.constant.HookPriorityConstants;
import com.alibaba.assistant.agent.common.hook.AgentPhase;
import com.alibaba.assistant.agent.common.hook.HookPhases;
import com.alibaba.assistant.agent.runtime.agent.AssistantStateKeys;
import com.alibaba.assistant.agent.start.car.tool.CarFeeAddFormTool;
import com.alibaba.assistant.agent.start.car.tool.CarFeeAddTool;
import com.alibaba.assistant.agent.start.car.util.CarFeeFormSummaryParser;
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
 * 车辆费用新增快速路由 Hook。
 */
@Component
@Profile("migration")
@HookPhases(AgentPhase.REACT)
@HookPositions(HookPosition.BEFORE_AGENT)
public class CarFeeFastIntentHook extends AgentHook implements Prioritized {

    private final ObjectMapper objectMapper;

    public CarFeeFastIntentHook(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public String getName() {
        return "CarFeeFastIntentHook";
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
        PendingCarFeeForm pendingCarFeeForm = resolvePendingCarFeeForm(state);
        boolean hasStructuredSlotInputs = hasMeaningfulSlotInputs(state);

        // 已有待提交表单时，优先继续走提交链路。
        if (pendingCarFeeForm.active()) {
            if (!StringUtils.hasText(userInput) && !hasStructuredSlotInputs) {
                return CompletableFuture.completedFuture(Map.of());
            }
            boolean confirmed = pendingCarFeeForm.confirming()
                    && (hasStructuredSlotInputs
                    || isConfirmText(userInput)
                    || CarFeeFormSummaryParser.looksLikeSubmissionSummary(userInput));
            String toolName = confirmed ? CarFeeAddTool.TOOL_NAME : CarFeeAddFormTool.TOOL_NAME;
            String routeType = confirmed ? "CAR_FEE_WORKFLOW_SUBMIT" : "CAR_FEE_WORKFLOW_CONTINUE";
            return CompletableFuture.completedFuture(buildToolUpdates(
                    toolName,
                    userInput,
                    confirmed,
                    routeType,
                    state));
        }

        // 首次命中关键词时，直接打开车辆费用表单工具。
        if (!isCarFeeRequest(userInput)) {
            return CompletableFuture.completedFuture(Map.of());
        }
        return CompletableFuture.completedFuture(buildToolUpdates(
                CarFeeAddFormTool.TOOL_NAME,
                userInput,
                false,
                "CAR_FEE_WORKFLOW_DIRECT",
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
            if (CarFeeAddTool.TOOL_NAME.equals(toolName)) {
                toolArgs.put("confirmed", confirmed);
                if (!frontendThreadState.isEmpty()) {
                    toolArgs.put("frontendThreadState", frontendThreadState);
                }
            }
            else if (CarFeeAddFormTool.TOOL_NAME.equals(toolName)) {
                Map<String, Object> pendingValues = extractPendingFormValues(frontendThreadState);
                if (!pendingValues.isEmpty()) {
                    toolArgs.put("values", pendingValues);
                }
            }

            AssistantMessage assistantMessage = AssistantMessage.builder()
                    .content("")
                    .toolCalls(List.of(new AssistantMessage.ToolCall(
                            "car_fee_fast_intent_" + UUID.randomUUID().toString().substring(0, 8),
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

    private boolean isCarFeeRequest(String userInput) {
        if (!StringUtils.hasText(userInput)) {
            return false;
        }
        String normalizedInput = userInput.trim();
        return normalizedInput.contains("我要新增车辆费用")
                || normalizedInput.contains("添加车辆费用")
                || normalizedInput.contains("新增车辆费用")
                || normalizedInput.contains("车辆费用报销");
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

    private PendingCarFeeForm resolvePendingCarFeeForm(@Nullable OverAllState state) {
        if (state == null) {
            return PendingCarFeeForm.inactive();
        }
        Object raw = state.value(AssistantStateKeys.FRONTEND_THREAD_STATE, Object.class).orElse(null);
        if (!(raw instanceof Map<?, ?> frontendThreadState)) {
            return PendingCarFeeForm.inactive();
        }
        Object rawPendingForm = frontendThreadState.get("pendingForm");
        if (!(rawPendingForm instanceof Map<?, ?> pendingForm)) {
            return PendingCarFeeForm.inactive();
        }
        String toolCode = asText(pendingForm.get("toolCode"));
        if (!CarFeeAddTool.TOOL_NAME.equals(toolCode) || Boolean.TRUE.equals(pendingForm.get("readOnly"))) {
            return PendingCarFeeForm.inactive();
        }
        String phase = firstText(pendingForm.get("phase"), frontendThreadState.get("phase"));
        String mode = asText(pendingForm.get("mode"));
        boolean confirming = "CONFIRM".equalsIgnoreCase(mode)
                || FrontendStage.CONFIRMING.name().equalsIgnoreCase(phase);
        return new PendingCarFeeForm(true, confirming);
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

    private record PendingCarFeeForm(boolean active, boolean confirming) {

        private static PendingCarFeeForm inactive() {
            return new PendingCarFeeForm(false, false);
        }
    }
}
