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
package com.alibaba.assistant.agent.start.profile.intent;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 个人档案查询意图识别器。
 *
 * <p>负责识别“张三的个人档案”“李四是谁”“帮我查一下王五的信息”这类输入，
 * 并抽取姓名，用于后续快速跳转到 Tool 执行阶段。</p>
 */
@Component
public class IntentRecognizer {

    private static final Pattern NAME_EXTRACTION_PATTERN = Pattern.compile(
            "(?:帮我查一下|请帮我查一下|查一下|查询|看看|帮我)?\\s*([\\u4e00-\\u9fa5]{2,4})\\s*(?:的)?\\s*(?:个人档案|档案|信息|资料|是谁)");

    /**
     * 识别输入并返回结果。
     *
     * @param userInput 用户原始输入
     * @return 识别结果
     */
    public RecognitionResult recognize(String userInput) {
        String normalizedInput = Optional.ofNullable(userInput)
                .map(String::trim)
                .orElse("");
        IntentType intentType = detectIntentType(normalizedInput);
        if (intentType != IntentType.PROFILE_QUERY) {
            return RecognitionResult.notMatched();
        }
        return extractName(normalizedInput)
                .map(name -> new RecognitionResult(true, intentType, name, normalizedInput))
                .orElseGet(RecognitionResult::notMatched);
    }

    /**
     * 检测输入属于哪种意图。
     *
     * <p>这里使用 switch 表达式，把关键词条件映射到目标意图。</p>
     *
     * @param normalizedInput 归一化后的输入
     * @return 意图类型
     */
    public IntentType detectIntentType(String normalizedInput) {
        boolean hasActionKeyword = normalizedInput.contains("查")
                || normalizedInput.contains("查询")
                || normalizedInput.contains("看看")
                || normalizedInput.contains("帮我");
        boolean hasProfileKeyword = normalizedInput.contains("个人档案")
                || normalizedInput.contains("档案")
                || normalizedInput.contains("信息")
                || normalizedInput.contains("资料")
                || normalizedInput.contains("是谁");
        String intentCode = hasProfileKeyword
                ? "PROFILE_QUERY"
                : "UNKNOWN";
        return switch (intentCode) {
            case "PROFILE_QUERY" -> IntentType.PROFILE_QUERY;
            default -> IntentType.UNKNOWN;
        };
    }

    /**
     * 从输入中抽取姓名。
     *
     * @param normalizedInput 归一化后的输入
     * @return 姓名
     */
    public Optional<String> extractName(String normalizedInput) {
        if (!StringUtils.hasText(normalizedInput)) {
            return Optional.empty();
        }
        Matcher matcher = NAME_EXTRACTION_PATTERN.matcher(normalizedInput);
        if (matcher.find()) {
            return Optional.ofNullable(matcher.group(1))
                    .map(String::trim)
                    .map(name -> name.endsWith("的") ? name.substring(0, name.length() - 1) : name)
                    .filter(StringUtils::hasText);
        }
        String fallback = normalizedInput
                .replace("帮我查一下", "")
                .replace("请帮我查一下", "")
                .replace("查一下", "")
                .replace("查询", "")
                .replace("看看", "")
                .replace("帮我", "")
                .replace("的个人档案", "")
                .replace("个人档案", "")
                .replace("的档案", "")
                .replace("档案", "")
                .replace("的信息", "")
                .replace("信息", "")
                .replace("的资料", "")
                .replace("资料", "")
                .replace("是谁", "")
                .trim();
        return Optional.of(fallback)
                .filter(StringUtils::hasText)
                .filter(candidate -> candidate.matches("[\\u4e00-\\u9fa5]{2,4}"));
    }

    /**
     * 意图类型枚举。
     */
    public enum IntentType {
        PROFILE_QUERY,
        UNKNOWN
    }

    /**
     * 意图识别结果。
     *
     * @param matched 是否命中
     * @param intentType 意图类型
     * @param name 解析出的姓名
     * @param originalInput 原始输入
     */
    public record RecognitionResult(
            boolean matched,
            IntentType intentType,
            String name,
            String originalInput) {

        /**
         * 创建未命中结果。
         *
         * @return 未命中结果
         */
        public static RecognitionResult notMatched() {
            return new RecognitionResult(false, IntentType.UNKNOWN, null, null);
        }
    }
}
