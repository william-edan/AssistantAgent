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
            "([一-龥]{2,4}?)\\s*的\\s*(个人档案|档案|个人资料|信息|资料|日程|排期|日历|行程|在用资产|正在使用的资产|在使用的资产|名下资产|资产)");

    private static final Pattern LOOKUP_QUERY_PATTERN = Pattern.compile(
            "(?:帮我查一下|请帮我查一下|查一下|查询|看看|帮我看下|帮我查)\\s*([一-龥]{2,4}?)\\s*(?:的)?\\s*(个人档案|档案|个人资料|信息|资料|日程|排期|日历|行程|在用资产|正在使用的资产|在使用的资产|名下资产|资产)");

    private static final Pattern WHO_IS_PATTERN = Pattern.compile("([一-龥]{2,4})\\s*是谁");

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
        // 避免将“公司车辆信息”误识别为“个人信息”。
        if (isCompanyCarQuery(normalizedInput)) {
            return RecognitionResult.notMatched();
        }
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
        boolean hasAssetKeyword = containsAny(normalizedInput,
                "在用资产", "正在使用的资产", "在使用的资产", "名下资产", "资产");
        boolean hasScheduleKeyword = containsAny(normalizedInput,
                "日程", "排期", "日历", "行程");
        boolean hasArchiveKeyword = containsAny(normalizedInput,
                "个人档案", "档案");
        boolean hasGeneralKeyword = containsAny(normalizedInput,
                "信息", "资料", "是谁");

        String intentCode = hasAssetKeyword
                ? "PROFILE_ASSET_IN_USE"
                : hasScheduleKeyword
                ? "PROFILE_SCHEDULE"
                : hasArchiveKeyword
                ? "PROFILE_ARCHIVE"
                : hasGeneralKeyword
                ? "PROFILE_GENERAL"
                : "UNKNOWN";

        return switch (intentCode) {
            case "PROFILE_ASSET_IN_USE" -> IntentType.PROFILE_ASSET_IN_USE;
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
                .replace("帮我查一下", "")
                .replace("请帮我查一下", "")
                .replace("查一下", "")
                .replace("查询", "")
                .replace("看看", "")
                .replace("帮我看下", "")
                .replace("帮我查", "")
                .replace("的个人档案", "")
                .replace("个人档案", "")
                .replace("的个人资料", "")
                .replace("个人资料", "")
                .replace("的档案", "")
                .replace("档案", "")
                .replace("的信息", "")
                .replace("信息", "")
                .replace("的资料", "")
                .replace("资料", "")
                .replace("的日程", "")
                .replace("日程", "")
                .replace("的排期", "")
                .replace("排期", "")
                .replace("的日历", "")
                .replace("日历", "")
                .replace("的行程", "")
                .replace("行程", "")
                .replace("正在使用的资产", "")
                .replace("在使用的资产", "")
                .replace("的在用资产", "")
                .replace("在用资产", "")
                .replace("的名下资产", "")
                .replace("名下资产", "")
                .replace("的资产", "")
                .replace("资产", "")
                .replace("是谁", "")
                .trim();

        return Optional.of(fallback)
                .filter(StringUtils::hasText)
                .filter(candidate -> candidate.matches("[一-龥]{2,4}"));
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
                "请帮我查一下",
                "帮我查一下",
                "查一下",
                "查询",
                "看看",
                "帮我看下",
                "帮我查"
        };
        for (String prefix : prefixes) {
            if (normalizedCandidate.startsWith(prefix) && normalizedCandidate.length() > prefix.length()) {
                normalizedCandidate = normalizedCandidate.substring(prefix.length()).trim();
            }
        }
        if (normalizedCandidate.startsWith("一下") && normalizedCandidate.length() > 2) {
            normalizedCandidate = normalizedCandidate.substring(2);
        }
        if (normalizedCandidate.endsWith("的") && normalizedCandidate.length() > 1) {
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

    private boolean isCompanyCarQuery(String input) {
        boolean hasVehicleKeyword = containsAny(input, "车辆", "用车", "公车");
        boolean hasCompanyKeyword = containsAny(input, "公司", "企业", "单位");
        boolean hasQueryKeyword = containsAny(input, "查询", "查", "查看", "信息", "列表");
        return hasVehicleKeyword && hasCompanyKeyword && hasQueryKeyword;
    }

    /**
     * 意图类型枚举。
     */
    public enum IntentType {
        PROFILE_ASSET_IN_USE,
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
