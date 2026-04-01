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
 * 个人信息查询意图识别器。
 *
 * <p>负责识别个人档案、个人日程以及通用个人信息查询请求，
 * 并提取目标姓名，供快捷 Hook 和 Tool 直接命中本地查询链路。</p>
 */
@Component
public class IntentRecognizer {

    private static final Pattern POSSESSIVE_QUERY_PATTERN = Pattern.compile(
            "([\\u4e00-\\u9fa5]{2,4})\\s*\\u7684\\s*(\\u4e2a\\u4eba\\u6863\\u6848|\\u6863\\u6848|\\u4fe1\\u606f|\\u8d44\\u6599|\\u65e5\\u7a0b|\\u6392\\u671f|\\u65e5\\u5386|\\u884c\\u7a0b)");

    private static final Pattern LOOKUP_QUERY_PATTERN = Pattern.compile(
            "(?:\\u5e2e\\u6211\\u67e5\\u4e00\\u4e0b|\\u8bf7\\u5e2e\\u6211\\u67e5\\u4e00\\u4e0b|\\u67e5\\u4e00\\u4e0b|\\u67e5\\u8be2|\\u770b\\u770b|\\u5e2e\\u6211\\u770b\\u4e0b|\\u5e2e\\u6211\\u67e5)\\s*([\\u4e00-\\u9fa5]{2,4})\\s*(?:\\u7684)?\\s*(\\u4e2a\\u4eba\\u6863\\u6848|\\u6863\\u6848|\\u4fe1\\u606f|\\u8d44\\u6599|\\u65e5\\u7a0b|\\u6392\\u671f|\\u65e5\\u5386|\\u884c\\u7a0b)");

    private static final Pattern WHO_IS_PATTERN = Pattern.compile("([\\u4e00-\\u9fa5]{2,4})\\s*\\u662f\\u8c01");

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
        if (intentType == IntentType.UNKNOWN) {
            return RecognitionResult.notMatched();
        }
        return extractName(normalizedInput)
                .map(name -> new RecognitionResult(true, intentType, name, normalizedInput))
                .orElseGet(RecognitionResult::notMatched);
    }

    /**
     * 检测输入属于哪一种意图。
     *
     * <p>这里使用 switch 表达式，将关键词条件映射到目标意图。</p>
     *
     * @param normalizedInput 归一化后的输入
     * @return 意图类型
     */
    public IntentType detectIntentType(String normalizedInput) {
        boolean hasScheduleKeyword = containsAny(normalizedInput,
                "\u65e5\u7a0b", "\u6392\u671f", "\u65e5\u5386", "\u884c\u7a0b");
        boolean hasArchiveKeyword = containsAny(normalizedInput,
                "\u4e2a\u4eba\u6863\u6848", "\u6863\u6848");
        boolean hasGeneralKeyword = containsAny(normalizedInput,
                "\u4fe1\u606f", "\u8d44\u6599", "\u662f\u8c01");

        String intentCode = hasScheduleKeyword
                ? "PROFILE_SCHEDULE"
                : hasArchiveKeyword
                ? "PROFILE_ARCHIVE"
                : hasGeneralKeyword
                ? "PROFILE_GENERAL"
                : "UNKNOWN";

        return switch (intentCode) {
            case "PROFILE_ARCHIVE" -> IntentType.PROFILE_ARCHIVE;
            case "PROFILE_SCHEDULE" -> IntentType.PROFILE_SCHEDULE;
            case "PROFILE_GENERAL" -> IntentType.PROFILE_GENERAL;
            default -> IntentType.UNKNOWN;
        };
    }

    /**
     * 从输入中提取姓名。
     *
     * @param normalizedInput 归一化后的输入
     * @return 姓名
     */
    public Optional<String> extractName(String normalizedInput) {
        if (!StringUtils.hasText(normalizedInput)) {
            return Optional.empty();
        }

        Optional<String> matchedName = extractNameByPattern(normalizedInput, LOOKUP_QUERY_PATTERN, 1);
        if (matchedName.isPresent()) {
            return matchedName;
        }

        matchedName = extractNameByPattern(normalizedInput, POSSESSIVE_QUERY_PATTERN, 1);
        if (matchedName.isPresent()) {
            return matchedName;
        }

        matchedName = extractNameByPattern(normalizedInput, WHO_IS_PATTERN, 1);
        if (matchedName.isPresent()) {
            return matchedName;
        }

        String fallback = normalizedInput
                .replace("\u5e2e\u6211\u67e5\u4e00\u4e0b", "")
                .replace("\u8bf7\u5e2e\u6211\u67e5\u4e00\u4e0b", "")
                .replace("\u67e5\u4e00\u4e0b", "")
                .replace("\u67e5\u8be2", "")
                .replace("\u770b\u770b", "")
                .replace("\u5e2e\u6211\u770b\u4e0b", "")
                .replace("\u5e2e\u6211\u67e5", "")
                .replace("\u7684\u4e2a\u4eba\u6863\u6848", "")
                .replace("\u4e2a\u4eba\u6863\u6848", "")
                .replace("\u7684\u6863\u6848", "")
                .replace("\u6863\u6848", "")
                .replace("\u7684\u4fe1\u606f", "")
                .replace("\u4fe1\u606f", "")
                .replace("\u7684\u8d44\u6599", "")
                .replace("\u8d44\u6599", "")
                .replace("\u7684\u65e5\u7a0b", "")
                .replace("\u65e5\u7a0b", "")
                .replace("\u7684\u6392\u671f", "")
                .replace("\u6392\u671f", "")
                .replace("\u7684\u65e5\u5386", "")
                .replace("\u65e5\u5386", "")
                .replace("\u7684\u884c\u7a0b", "")
                .replace("\u884c\u7a0b", "")
                .replace("\u662f\u8c01", "")
                .trim();

        return Optional.of(fallback)
                .filter(StringUtils::hasText)
                .filter(candidate -> candidate.matches("[\\u4e00-\\u9fa5]{2,4}"));
    }

    /**
     * 根据正则模式提取姓名。
     *
     * @param normalizedInput 归一化输入
     * @param pattern 匹配模式
     * @param groupIndex 姓名所在分组
     * @return 命中的姓名
     */
    private Optional<String> extractNameByPattern(String normalizedInput, Pattern pattern, int groupIndex) {
        Matcher matcher = pattern.matcher(normalizedInput);
        if (!matcher.find()) {
            return Optional.empty();
        }
        return Optional.ofNullable(matcher.group(groupIndex))
                .map(String::trim)
                .map(this::normalizeNameCandidate)
                .filter(StringUtils::hasText);
    }

    /**
     * 清洗姓名候选值，避免把“查一下”的尾部误并入姓名。
     *
     * @param candidate 原始候选值
     * @return 清洗后的姓名
     */
    private String normalizeNameCandidate(String candidate) {
        if (!StringUtils.hasText(candidate)) {
            return candidate;
        }
        String normalizedCandidate = candidate.trim();
        String[] prefixes = {
                "\u8bf7\u5e2e\u6211\u67e5\u4e00\u4e0b",
                "\u5e2e\u6211\u67e5\u4e00\u4e0b",
                "\u67e5\u4e00\u4e0b",
                "\u67e5\u8be2",
                "\u770b\u770b",
                "\u5e2e\u6211\u770b\u4e0b",
                "\u5e2e\u6211\u67e5"
        };
        for (String prefix : prefixes) {
            if (normalizedCandidate.startsWith(prefix) && normalizedCandidate.length() > prefix.length()) {
                normalizedCandidate = normalizedCandidate.substring(prefix.length()).trim();
            }
        }
        if (normalizedCandidate.startsWith("\u4e00\u4e0b") && normalizedCandidate.length() > 2) {
            normalizedCandidate = normalizedCandidate.substring(2);
        }
        if (normalizedCandidate.endsWith("\u7684") && normalizedCandidate.length() > 1) {
            normalizedCandidate = normalizedCandidate.substring(0, normalizedCandidate.length() - 1);
        }
        return normalizedCandidate.trim();
    }

    /**
     * 判断输入中是否包含任一关键词。
     *
     * @param input 输入文本
     * @param keywords 关键词
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
     * 意图类型枚举。
     */
    public enum IntentType {
        PROFILE_ARCHIVE,
        PROFILE_SCHEDULE,
        PROFILE_GENERAL,
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
