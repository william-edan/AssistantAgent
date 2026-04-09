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
package com.alibaba.assistant.agent.start.car.util;

import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses frontend summary text for the car-fee form into workflow values.
 */
public final class CarFeeFormSummaryParser {

    private static final Pattern AMOUNT_PATTERN = Pattern.compile("(-?\\d+(?:\\.\\d+)?)");

    private static final Pattern DATE_PATTERN = Pattern.compile("(\\d{4})[-/](\\d{1,2})[-/](\\d{1,2})");

    private CarFeeFormSummaryParser() {
    }

    public static boolean looksLikeSubmissionSummary(String userInput) {
        Map<String, String> values = extractRawValues(userInput);
        int completedFieldCount = 0;
        if (values.containsKey("car_id")) {
            completedFieldCount++;
        }
        if (values.containsKey("types")) {
            completedFieldCount++;
        }
        if (values.containsKey("title")) {
            completedFieldCount++;
        }
        if (values.containsKey("fee_time")) {
            completedFieldCount++;
        }
        if (values.containsKey("amount")) {
            completedFieldCount++;
        }
        if (values.containsKey("handled")) {
            completedFieldCount++;
        }
        return completedFieldCount >= 4;
    }

    public static Map<String, String> extractRawValues(String userInput) {
        Map<String, String> values = new LinkedHashMap<>();
        if (!StringUtils.hasText(userInput)) {
            return values;
        }
        for (String rawSegment : userInput.split("[,，;；\\n]")) {
            String segment = normalizeSegment(rawSegment);
            if (!StringUtils.hasText(segment)) {
                continue;
            }
            putIfHasText(values, "car_id", extractValue(segment, List.of("车辆", "车牌", "车辆名称")));
            putIfHasText(values, "types", extractValue(segment, List.of("费用类型", "类型")));
            putIfHasText(values, "title", extractValue(segment, List.of("费用主题", "主题")));
            putIfHasText(values, "fee_time", normalizeDate(extractValue(segment, List.of("费用日期", "日期"))));
            putIfHasText(values, "amount", normalizeAmount(extractValue(segment, List.of("费用金额", "金额"))));
            putIfHasText(values, "handled", extractValue(segment, List.of("经手人", "处理人")));
            putIfHasText(values, "file_ids", extractValue(segment, List.of("附件")));
            putIfHasText(values, "content", extractValue(segment, List.of("备注", "说明")));
        }
        return values;
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
            while (StringUtils.hasText(value)
                    && (value.startsWith(":")
                    || value.startsWith("：")
                    || value.startsWith("="))) {
                value = value.substring(1).trim();
            }
            return StringUtils.hasText(value) ? value : null;
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
        return String.format(
                Locale.ROOT,
                "%s-%02d-%02d",
                matcher.group(1),
                Integer.parseInt(matcher.group(2)),
                Integer.parseInt(matcher.group(3)));
    }

    private static void putIfHasText(Map<String, String> values, String key, String value) {
        if (StringUtils.hasText(value)) {
            values.put(key, value);
        }
    }
}
