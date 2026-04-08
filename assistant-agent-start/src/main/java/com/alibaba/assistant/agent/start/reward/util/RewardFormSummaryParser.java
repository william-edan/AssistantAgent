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
package com.alibaba.assistant.agent.start.reward.util;

import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses frontend summary text for reward forms into workflow slot inputs.
 */
public final class RewardFormSummaryParser {

    private static final Pattern AMOUNT_PATTERN = Pattern.compile("(-?\\d+(?:\\.\\d+)?)");

    private static final Pattern DATE_PATTERN = Pattern.compile("(\\d{4}[-/]\\d{2}[-/]\\d{2})");

    private RewardFormSummaryParser() {
    }

    public static boolean hasFormLikeSlotInputs(String userInput) {
        return extractSlotInputs(userInput).size() >= 2;
    }

    public static boolean looksLikeSubmissionSummary(String userInput) {
        Map<String, Object> slotInputs = extractSlotInputs(userInput);
        int completedFieldCount = 0;
        if (slotInputs.containsKey("uname")) {
            completedFieldCount++;
        }
        if (slotInputs.containsKey("types")) {
            completedFieldCount++;
        }
        if (slotInputs.containsKey("rewards_cate")) {
            completedFieldCount++;
        }
        if (slotInputs.containsKey("cost")) {
            completedFieldCount++;
        }
        if (slotInputs.containsKey("rewards_time")) {
            completedFieldCount++;
        }
        return completedFieldCount >= 4;
    }

    public static Map<String, Object> extractSlotInputs(String userInput) {
        Map<String, Object> slotInputs = new LinkedHashMap<>();
        if (!StringUtils.hasText(userInput)) {
            return slotInputs;
        }
        for (String rawSegment : userInput.split("[，,；;]")) {
            String segment = normalizeSegment(rawSegment);
            if (!StringUtils.hasText(segment)) {
                continue;
            }
            putIfHasText(slotInputs, "uname", extractValue(segment, List.of("员工姓名", "员工", "姓名")));
            Integer types = parseTypes(extractValue(segment, List.of("奖惩类型", "类型")));
            if (types != null) {
                slotInputs.put("types", types);
            }
            putIfHasText(slotInputs, "rewards_cate", extractValue(segment, List.of("奖惩项目", "奖惩类别", "项目", "类别")));
            putIfHasText(slotInputs, "cost", normalizeAmount(extractValue(segment, List.of("金额", "奖惩金额"))));
            putIfHasText(slotInputs, "rewards_time", normalizeDate(extractValue(segment, List.of("奖惩日期", "日期"))));
            putIfHasText(slotInputs, "remark", extractValue(segment, List.of("备注", "说明")));
        }
        return slotInputs;
    }

    private static String normalizeSegment(String segment) {
        if (!StringUtils.hasText(segment)) {
            return null;
        }
        String normalized = segment.trim();
        return StringUtils.hasText(normalized) ? normalized : null;
    }

    private static String extractValue(String segment, List<String> labels) {
        if (!StringUtils.hasText(segment)) {
            return null;
        }
        for (String label : labels) {
            if (!segment.startsWith(label)) {
                continue;
            }
            String value = segment.substring(label.length()).trim();
            while (StringUtils.hasText(value) && (value.startsWith(":") || value.startsWith("：") || value.startsWith("="))) {
                value = value.substring(1).trim();
            }
            return StringUtils.hasText(value) ? value : null;
        }
        return null;
    }

    private static Integer parseTypes(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        if (value.contains("惩") || value.contains("罚")) {
            return 2;
        }
        if (value.contains("奖")) {
            return 1;
        }
        return null;
    }

    private static String normalizeAmount(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        Matcher matcher = AMOUNT_PATTERN.matcher(value);
        if (!matcher.find()) {
            return value;
        }
        return matcher.group(1);
    }

    private static String normalizeDate(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        Matcher matcher = DATE_PATTERN.matcher(value);
        if (!matcher.find()) {
            return value;
        }
        return matcher.group(1).replace('/', '-');
    }

    private static void putIfHasText(Map<String, Object> values, String key, String value) {
        if (StringUtils.hasText(value)) {
            values.put(key, value);
        }
    }
}
