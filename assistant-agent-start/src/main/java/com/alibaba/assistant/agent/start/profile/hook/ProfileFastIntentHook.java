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
package com.alibaba.assistant.agent.start.profile.hook;

import com.alibaba.assistant.agent.common.constant.HookPriorityConstants;
import com.alibaba.assistant.agent.common.hook.AgentPhase;
import com.alibaba.assistant.agent.common.hook.HookPhases;
import com.alibaba.assistant.agent.runtime.agent.AssistantStateKeys;
import com.alibaba.assistant.agent.start.profile.intent.IntentRecognizer;
import com.alibaba.assistant.agent.start.profile.tool.ProfileQueryTool;
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
 * 个人档案查询快速路由 Hook。
 *
 * <p>该 Hook 在模型调用前识别档案查询意图。
 * 一旦命中，就直接构造 `profile_query` 工具调用并跳转到工具节点，
 * 从而满足“这类请求不走大模型生成”的要求。</p>
 */
@Component
@Profile("migration")
@HookPhases(AgentPhase.REACT)
@HookPositions(HookPosition.BEFORE_AGENT)
public class ProfileFastIntentHook extends AgentHook implements Prioritized {

    private final IntentRecognizer intentRecognizer;

    private final ObjectMapper objectMapper;

    public ProfileFastIntentHook(IntentRecognizer intentRecognizer, ObjectMapper objectMapper) {
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
        return "ProfileFastIntentHook";
    }

    /**
     * 返回 Hook 顺序。
     *
     * @return 执行顺序
     */
    @Override
    public int getOrder() {
        return HookPriorityConstants.FAST_INTENT_HOOK - 100;
    }

    /**
     * 声明允许跳转的节点。
     *
     * @return 可跳转节点
     */
    @Override
    public List<JumpTo> canJumpTo() {
        return List.of(JumpTo.tool, JumpTo.model);
    }

    /**
     * 返回状态字段更新策略。
     *
     * @return 状态字段策略
     */
    @Override
    public Map<String, KeyStrategy> getKeyStrategys() {
        return Map.of("jump_to", new ReplaceStrategy());
    }

    /**
     * 在模型执行前进行快速意图判定。
     *
     * @param state 当前对话状态
     * @param config 运行配置
     * @return 状态更新
     */
    @Override
    public CompletableFuture<Map<String, Object>> beforeAgent(OverAllState state, RunnableConfig config) {
        String userInput = resolveUserInput(state);
        IntentRecognizer.RecognitionResult recognitionResult = intentRecognizer.recognize(userInput);
        if (!recognitionResult.matched()) {
            return CompletableFuture.completedFuture(Map.of());
        }
        try {
            AssistantMessage assistantMessage = AssistantMessage.builder()
                    .content("")
                    .toolCalls(List.of(new AssistantMessage.ToolCall(
                            "profile_fast_intent_" + UUID.randomUUID().toString().substring(0, 8),
                            "function",
                            ProfileQueryTool.TOOL_NAME,
                            objectMapper.writeValueAsString(Map.of("userInput", userInput)))))
                    .build();
            Map<String, Object> fastIntentState = new LinkedHashMap<>();
            fastIntentState.put("hit", true);
            fastIntentState.put("route_type", "PROFILE_QUERY_DIRECT");
            fastIntentState.put("name", recognitionResult.name());

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
     * 从状态中解析用户输入。
     *
     * @param state 当前状态
     * @return 当前轮用户输入
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
