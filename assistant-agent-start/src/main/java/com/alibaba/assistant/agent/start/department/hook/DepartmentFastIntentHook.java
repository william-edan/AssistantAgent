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
package com.alibaba.assistant.agent.start.department.hook;

import com.alibaba.assistant.agent.common.constant.HookPriorityConstants;
import com.alibaba.assistant.agent.common.hook.AgentPhase;
import com.alibaba.assistant.agent.common.hook.HookPhases;
import com.alibaba.assistant.agent.runtime.agent.AssistantStateKeys;
import com.alibaba.assistant.agent.start.department.intent.DepartmentIntentRecognizer;
import com.alibaba.assistant.agent.start.department.tool.DepartmentStaffingTool;
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
 * 部门编制与变动查询快速 Hook。
 *
 * <p>当输入命中部门维度统计查询时，直接构造工具调用并跳转到 Tool 节点，
 * 从而绕过 AssistantAgent 侧的大模型生成链路。</p>
 */
@Component
@Profile("migration")
@HookPhases(AgentPhase.REACT)
@HookPositions(HookPosition.BEFORE_AGENT)
public class DepartmentFastIntentHook extends AgentHook implements Prioritized {

    private final DepartmentIntentRecognizer intentRecognizer;

    private final ObjectMapper objectMapper;

    public DepartmentFastIntentHook(DepartmentIntentRecognizer intentRecognizer, ObjectMapper objectMapper) {
        this.intentRecognizer = intentRecognizer;
        this.objectMapper = objectMapper;
    }

    /**
     * 返回 Hook 名称。
     *
     * @return Hook 名称
     */
    @Override
    public String getName() {
        return "DepartmentFastIntentHook";
    }

    /**
     * 返回 Hook 执行顺序。
     *
     * @return 顺序值
     */
    @Override
    public int getOrder() {
        return HookPriorityConstants.FAST_INTENT_HOOK - 90;
    }

    /**
     * 声明当前 Hook 可跳转的目标节点。
     *
     * @return 可跳转节点列表
     */
    @Override
    public List<JumpTo> canJumpTo() {
        return List.of(JumpTo.tool, JumpTo.model);
    }

    /**
     * 返回状态字段更新策略。
     *
     * @return 字段策略
     */
    @Override
    public Map<String, KeyStrategy> getKeyStrategys() {
        return Map.of("jump_to", new ReplaceStrategy());
    }

    /**
     * 在进入 Agent 前执行快速意图判断。
     *
     * @param state 当前会话状态
     * @param config 运行配置
     * @return 状态更新结果
     */
    @Override
    public CompletableFuture<Map<String, Object>> beforeAgent(OverAllState state, RunnableConfig config) {
        String userInput = resolveUserInput(state);
        DepartmentIntentRecognizer.RecognitionResult recognitionResult = intentRecognizer.recognize(userInput);
        if (!recognitionResult.matched()) {
            return CompletableFuture.completedFuture(Map.of());
        }
        try {
            AssistantMessage assistantMessage = AssistantMessage.builder()
                    .content("")
                    .toolCalls(List.of(new AssistantMessage.ToolCall(
                            "department_fast_intent_" + UUID.randomUUID().toString().substring(0, 8),
                            "function",
                            DepartmentStaffingTool.TOOL_NAME,
                            objectMapper.writeValueAsString(Map.of("userInput", userInput)))))
                    .build();

            Map<String, Object> fastIntentState = new LinkedHashMap<>();
            fastIntentState.put("hit", true);
            fastIntentState.put("route_type", "DEPARTMENT_STAFFING_DIRECT");
            fastIntentState.put("query", recognitionResult.originalInput());

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
     * 从状态中解析当前轮用户输入。
     *
     * @param state 当前会话状态
     * @return 当前输入
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
