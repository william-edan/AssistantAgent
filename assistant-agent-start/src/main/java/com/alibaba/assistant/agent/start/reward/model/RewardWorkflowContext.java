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
package com.alibaba.assistant.agent.start.reward.model;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.lang.Nullable;

import java.util.Map;

/**
 * 奖惩工作流上下文。
 *
 * @param userInput 用户输入
 * @param slotInputs 当前表单输入
 * @param confirmed 是否已确认提交
 * @param intentResult 意图识别结果
 * @param toolContext 当前运行时上下文
 */
public record RewardWorkflowContext(
        String userInput,
        Map<String, Object> slotInputs,
        boolean confirmed,
        RewardIntentResult intentResult,
        @Nullable ToolContext toolContext) {

    public RewardWorkflowContext {
        slotInputs = slotInputs == null ? Map.of() : Map.copyOf(slotInputs);
    }

    public RewardWorkflowContext(
            String userInput,
            Map<String, Object> slotInputs,
            boolean confirmed,
            RewardIntentResult intentResult) {
        this(userInput, slotInputs, confirmed, intentResult, null);
    }
}
