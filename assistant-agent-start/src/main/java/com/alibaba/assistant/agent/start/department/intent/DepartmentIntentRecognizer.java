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
package com.alibaba.assistant.agent.start.department.intent;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Optional;

/**
 * 部门编制与人员变动意图识别器。
 *
 * <p>用于在进入大模型前，识别“各部门编制/变动”类查询，
 * 命中后可直接走本地 Tool + DataAgent 快速链路。</p>
 */
@Component
public class DepartmentIntentRecognizer {

    /**
     * 识别用户输入中的部门统计意图。
     *
     * @param userInput 用户原始输入
     * @return 识别结果
     */
    public RecognitionResult recognize(String userInput) {
        String normalizedInput = Optional.ofNullable(userInput)
                .map(String::trim)
                .orElse("");
        IntentType intentType = detectIntentType(normalizedInput);
        if (intentType == IntentType.UNKNOWN) {
            return RecognitionResult.notMatched();
        }
        return new RecognitionResult(true, intentType, normalizedInput);
    }

    /**
     * 判断输入是否属于部门编制与变动查询。
     *
     * <p>这里使用 switch 表达式对识别结果进行映射，便于后续扩展更多部门类查询。</p>
     *
     * @param normalizedInput 归一化后的输入
     * @return 意图类型
     */
    public IntentType detectIntentType(String normalizedInput) {
        boolean hasDepartmentScope = containsAny(normalizedInput, "部门", "各部门", "部门维度");
        boolean hasStaffingMetric = containsAny(normalizedInput,
                "编制", "人员编制", "变动", "人员变动", "入职", "离职", "增减");

        String intentCode = hasDepartmentScope && hasStaffingMetric
                ? "DEPARTMENT_STAFFING"
                : "UNKNOWN";

        return switch (intentCode) {
            case "DEPARTMENT_STAFFING" -> IntentType.DEPARTMENT_STAFFING;
            default -> IntentType.UNKNOWN;
        };
    }

    /**
     * 判断输入中是否包含任一关键词。
     *
     * @param input 输入文本
     * @param keywords 关键词列表
     * @return 命中时返回 true
     */
    private boolean containsAny(String input, String... keywords) {
        if (!StringUtils.hasText(input) || keywords == null || keywords.length == 0) {
            return false;
        }
        for (String keyword : keywords) {
            if (StringUtils.hasText(keyword) && input.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 部门查询意图类型。
     */
    public enum IntentType {
        DEPARTMENT_STAFFING,
        UNKNOWN
    }

    /**
     * 意图识别结果。
     *
     * @param matched 是否命中
     * @param intentType 意图类型
     * @param originalInput 原始输入
     */
    public record RecognitionResult(
            boolean matched,
            IntentType intentType,
            String originalInput) {

        /**
         * 创建未命中的识别结果。
         *
         * @return 未命中结果
         */
        public static RecognitionResult notMatched() {
            return new RecognitionResult(false, IntentType.UNKNOWN, null);
        }
    }
}
