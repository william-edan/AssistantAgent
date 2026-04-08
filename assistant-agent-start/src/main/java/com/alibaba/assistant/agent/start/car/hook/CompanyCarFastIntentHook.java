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

import com.alibaba.assistant.agent.common.constant.HookPriorityConstants;
import com.alibaba.assistant.agent.common.hook.AgentPhase;
import com.alibaba.assistant.agent.common.hook.HookPhases;
import com.alibaba.assistant.agent.runtime.agent.AssistantStateKeys;
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
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * 公司车辆信息查询快速 Hook。
 *
 * <p>在模型调用前识别“查询公司车辆信息”这类请求，
 * 命中后直接调用 {@code artifact_execute} 执行配置在 {@code tool_meta} 的查询工具，
 * 避免进入大模型自由生成路径。</p>
 */
@Component
@Profile("migration")
@HookPhases(AgentPhase.REACT)
@HookPositions(HookPosition.BEFORE_AGENT)
public class CompanyCarFastIntentHook extends AgentHook implements Prioritized {

    /**
     * 车辆查询工具编码（由 tool_meta 迁移脚本注册）。
     */
    public static final String COMPANY_CAR_QUERY_TOOL_CODE = "gougu_oa.company_car_info_query";

    private static final String EXECUTE_TOOL_NAME = "artifact_execute";

    private final ObjectMapper objectMapper;

    public CompanyCarFastIntentHook(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public String getName() {
        return "CompanyCarFastIntentHook";
    }

    @Override
    public int getOrder() {
        return HookPriorityConstants.FAST_INTENT_HOOK - 95;
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
        if (!isCompanyCarQuery(userInput)) {
            return CompletableFuture.completedFuture(Map.of());
        }
        try {
            Map<String, Object> executeArgs = new LinkedHashMap<>();
            executeArgs.put("toolCode", COMPANY_CAR_QUERY_TOOL_CODE);
            executeArgs.put("params", Map.of());

            AssistantMessage assistantMessage = AssistantMessage.builder()
                    .content("")
                    .toolCalls(List.of(new AssistantMessage.ToolCall(
                            "car_fast_intent_" + UUID.randomUUID().toString().substring(0, 8),
                            "function",
                            EXECUTE_TOOL_NAME,
                            objectMapper.writeValueAsString(executeArgs))))
                    .build();

            Map<String, Object> fastIntentState = new LinkedHashMap<>();
            fastIntentState.put("hit", true);
            fastIntentState.put("route_type", "COMPANY_CAR_QUERY_DIRECT");
            fastIntentState.put("tool_code", COMPANY_CAR_QUERY_TOOL_CODE);

            Map<String, Object> updates = new LinkedHashMap<>();
            updates.put("messages", List.of(assistantMessage));
            updates.put("jump_to", JumpTo.tool);
            updates.put("fast_intent", fastIntentState);
            return CompletableFuture.completedFuture(updates);
        }
        catch (Exception exception) {
            return CompletableFuture.completedFuture(Map.of());
        }
    }

    /**
     * 识别是否为公司车辆查询请求。
     */
    private boolean isCompanyCarQuery(String userInput) {
        if (!StringUtils.hasText(userInput)) {
            return false;
        }
        String normalizedInput = userInput.trim();
        if (normalizedInput.contains("查询公司车辆信息")) {
            return true;
        }
        boolean hasVehicleKeyword = containsAny(normalizedInput, "车辆", "用车", "公车");
        boolean hasCompanyKeyword = containsAny(normalizedInput, "公司", "企业", "单位");
        boolean hasQueryKeyword = containsAny(normalizedInput, "查询", "查下", "查一下", "查看", "看看", "获取", "列表", "信息");
        return hasVehicleKeyword && hasCompanyKeyword && hasQueryKeyword;
    }

    private boolean containsAny(String text, String... keywords) {
        if (!StringUtils.hasText(text) || keywords == null || keywords.length == 0) {
            return false;
        }
        for (String keyword : keywords) {
            if (StringUtils.hasText(keyword) && text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 从状态中提取当前轮用户输入。
     */
    private String resolveUserInput(OverAllState state) {
        if (state == null) {
            return "";
        }
        return Optional.ofNullable(state.value(AssistantStateKeys.CURRENT_TURN_USER_INPUT, String.class).orElse(null))
                .filter(StringUtils::hasText)
                .orElseGet(() -> state.value("input", String.class).orElse(""));
    }
}
