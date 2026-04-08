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
package com.alibaba.assistant.agent.start.reward.hook;

import com.alibaba.assistant.agent.api.protocol.FrontendStage;
import com.alibaba.assistant.agent.common.constant.HookPriorityConstants;
import com.alibaba.assistant.agent.common.hook.AgentPhase;
import com.alibaba.assistant.agent.common.hook.HookPhases;
import com.alibaba.assistant.agent.runtime.agent.AssistantStateKeys;
import com.alibaba.assistant.agent.start.reward.tool.RewardWorkflowTool;
import com.alibaba.assistant.agent.start.reward.util.RewardFormSummaryParser;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * 员工奖惩流程快速路由 Hook。
 */
@Component
@Profile("migration")
@HookPhases(AgentPhase.REACT)
@HookPositions(HookPosition.BEFORE_AGENT)
public class RewardFastIntentHook extends AgentHook implements Prioritized {

    private static final Logger log = LoggerFactory.getLogger(RewardFastIntentHook.class);

    private final ObjectMapper objectMapper;

    public RewardFastIntentHook(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public String getName() {
        return "RewardFastIntentHook";
    }

    @Override
    public int getOrder() {
        return HookPriorityConstants.FAST_INTENT_HOOK - 80;
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
        PendingRewardForm pendingRewardForm = resolvePendingRewardForm(state);
        boolean hasStructuredSlotInputs = hasMeaningfulSlotInputs(state);
        if (!pendingRewardForm.active() && !isRewardRequest(userInput)) {
            return CompletableFuture.completedFuture(Map.of());
        }
        if (pendingRewardForm.active() && !StringUtils.hasText(userInput) && !hasStructuredSlotInputs) {
            return CompletableFuture.completedFuture(Map.of());
        }

        boolean confirmed = pendingRewardForm.confirming()
                && (isConfirmText(userInput)
                || hasStructuredSlotInputs
                || RewardFormSummaryParser.looksLikeSubmissionSummary(userInput));
        String routeType = pendingRewardForm.active()
                ? (confirmed ? "REWARD_WORKFLOW_SUBMIT" : "REWARD_WORKFLOW_CONTINUE")
                : "REWARD_WORKFLOW_DIRECT";
        log.info("RewardFastIntentHook#beforeAgent - routeType={}, confirmed={}", routeType, confirmed);
        return CompletableFuture.completedFuture(buildToolUpdates(userInput, confirmed, routeType, state));
    }

    private Map<String, Object> buildToolUpdates(
            String userInput,
            boolean confirmed,
            String routeType,
            @Nullable OverAllState state) {
        try {
            Map<String, Object> toolArgs = new LinkedHashMap<>();
            toolArgs.put("userInput", Optional.ofNullable(userInput).orElse(""));
            toolArgs.put("confirmed", confirmed);

            Map<String, Object> slotInputs = extractCurrentTurnSlotInputs(state);
            if (!slotInputs.isEmpty()) {
                toolArgs.put("slotInputs", slotInputs);
            }

            Map<String, Object> frontendThreadState = extractFrontendThreadState(state);
            if (!frontendThreadState.isEmpty()) {
                toolArgs.put("frontendThreadState", frontendThreadState);
            }

            AssistantMessage assistantMessage = AssistantMessage.builder()
                    .content("")
                    .toolCalls(List.of(new AssistantMessage.ToolCall(
                            "reward_fast_intent_" + UUID.randomUUID().toString().substring(0, 8),
                            "function",
                            RewardWorkflowTool.TOOL_NAME,
                            objectMapper.writeValueAsString(toolArgs))))
                    .build();

            Map<String, Object> fastIntentState = new LinkedHashMap<>();
            fastIntentState.put("hit", true);
            fastIntentState.put("route_type", routeType);
            fastIntentState.put("tool_code", RewardWorkflowTool.TOOL_NAME);

            Map<String, Object> updates = new LinkedHashMap<>();
            updates.put("messages", List.of(assistantMessage));
            updates.put("jump_to", JumpTo.tool);
            updates.put("fast_intent", fastIntentState);
            return updates;
        }
        catch (Exception exception) {
            log.info("RewardFastIntentHook#buildToolUpdates - error={}", exception.getMessage());
            return Map.of();
        }
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

    private String resolveUserInput(OverAllState state) {
        if (state == null) {
            return "";
        }
        return Optional.ofNullable(state.value(AssistantStateKeys.CURRENT_TURN_USER_INPUT, String.class).orElse(null))
                .filter(StringUtils::hasText)
                .orElseGet(() -> state.value("input", String.class).orElse(""));
    }

    private PendingRewardForm resolvePendingRewardForm(OverAllState state) {
        if (state == null) {
            return PendingRewardForm.inactive();
        }
        Object raw = state.value(AssistantStateKeys.FRONTEND_THREAD_STATE, Object.class).orElse(null);
        if (!(raw instanceof Map<?, ?> threadState)) {
            return PendingRewardForm.inactive();
        }
        Object rawPendingForm = threadState.get("pendingForm");
        if (!(rawPendingForm instanceof Map<?, ?> pendingForm)) {
            return PendingRewardForm.inactive();
        }
        String toolCode = asText(pendingForm.get("toolCode"));
        if (!RewardWorkflowTool.TOOL_NAME.equals(toolCode) || Boolean.TRUE.equals(pendingForm.get("readOnly"))) {
            return PendingRewardForm.inactive();
        }
        String phase = firstText(pendingForm.get("phase"), threadState.get("phase"));
        String mode = asText(pendingForm.get("mode"));
        boolean confirming = "CONFIRM".equalsIgnoreCase(mode) || FrontendStage.CONFIRMING.name().equalsIgnoreCase(phase);
        return new PendingRewardForm(true, confirming);
    }

    private boolean hasMeaningfulSlotInputs(OverAllState state) {
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

    private boolean isRewardRequest(String userInput) {
        if (!StringUtils.hasText(userInput)) {
            return false;
        }
        return userInput.contains("奖励")
                || userInput.contains("惩罚")
                || userInput.contains("处罚")
                || (userInput.contains("生日")
                && (userInput.contains("给") || userInput.contains("发") || userInput.contains("福利")));
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

    private record PendingRewardForm(boolean active, boolean confirming) {

        private static PendingRewardForm inactive() {
            return new PendingRewardForm(false, false);
        }
    }
}
