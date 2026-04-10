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
package com.alibaba.assistant.agent.start.customer.node;

import com.alibaba.assistant.agent.start.customer.model.HighIntentCustomerIntentResult;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 高意向客户查询意图节点。
 */
@Component
@Profile("migration")
public class HighIntentCustomerIntentNode {

    private static final String HIGH_INTENT_CUSTOMER = "\u9ad8\u610f\u5411\u5ba2\u6237";

    private static final String USERNAME_PATTERN = "[\\p{IsHan}A-Za-z0-9\\u00b7]{2,20}";

    private static final List<String> PREFIXES = List.of(
            "\u8bf7\u5e2e\u6211\u67e5\u8be2\u4e00\u4e0b",
            "\u8bf7\u5e2e\u6211\u67e5\u4e00\u4e0b",
            "\u8bf7\u5e2e\u6211\u770b\u4e00\u4e0b",
            "\u6211\u8981\u67e5\u8be2",
            "\u6211\u8981\u67e5",
            "\u8981\u67e5\u8be2",
            "\u8981\u67e5",
            "\u60f3\u67e5\u8be2",
            "\u60f3\u67e5",
            "\u5e2e\u6211\u770b\u4e00\u4e0b",
            "\u5e2e\u6211\u67e5\u4e00\u4e0b",
            "\u5e2e\u6211\u770b\u4e0b",
            "\u5e2e\u6211\u67e5\u4e0b",
            "\u5e2e\u6211\u770b\u770b",
            "\u5e2e\u6211\u67e5\u67e5",
            "\u67e5\u8be2\u4e00\u4e0b",
            "\u770b\u4e00\u4e0b",
            "\u770b\u4e0b",
            "\u770b\u770b",
            "\u67e5\u4e00\u4e0b",
            "\u67e5\u4e0b",
            "\u67e5\u8be2",
            "\u67e5");

    private static final List<Pattern> USERNAME_PATTERNS = List.of(
            Pattern.compile("(?<username>" + USERNAME_PATTERN + ")\u7684" + HIGH_INTENT_CUSTOMER),
            Pattern.compile("(?<username>" + USERNAME_PATTERN + ")\u6709\u54ea\u4e9b" + HIGH_INTENT_CUSTOMER),
            Pattern.compile("(?<username>" + USERNAME_PATTERN + ")\u6709" + HIGH_INTENT_CUSTOMER),
            Pattern.compile("(?<username>" + USERNAME_PATTERN + ")" + HIGH_INTENT_CUSTOMER));

    private static final Pattern PAGE_PATTERN = Pattern.compile("(?:\u7b2c)?(?<page>\\d{1,3})\u9875");

    private static final List<Pattern> LIMIT_PATTERNS = List.of(
            Pattern.compile("\u6bcf\u9875(?<limit>\\d{1,3})\u6761"),
            Pattern.compile("limit(?<limit>\\d{1,3})"));

    /**
     * 从自然语言中提取查询意图与关键参数。
     */
    public HighIntentCustomerIntentResult identify(String userInput) {
        String normalizedInput = normalizeInput(userInput);
        boolean request = StringUtils.hasText(normalizedInput) && normalizedInput.contains(HIGH_INTENT_CUSTOMER);
        return new HighIntentCustomerIntentResult(
                request,
                extractUsername(normalizedInput),
                extractNumber(normalizedInput, PAGE_PATTERN).orElse(1),
                extractLimit(normalizedInput).orElse(20));
    }

    private String normalizeInput(String userInput) {
        if (!StringUtils.hasText(userInput)) {
            return "";
        }
        String normalized = userInput.trim().replaceAll("[\uFF0C\u3002\uFF01\uFF1F\u3001\uFF1A\uFF1B,.!?;:\\s]+", "");
        return stripLeadingPrefixes(normalized);
    }

    private String extractUsername(String normalizedInput) {
        if (!StringUtils.hasText(normalizedInput)) {
            return null;
        }
        return USERNAME_PATTERNS.stream()
                .map(pattern -> pattern.matcher(normalizedInput))
                .filter(Matcher::find)
                .map(matcher -> matcher.group("username"))
                .map(this::sanitizeUsername)
                .filter(StringUtils::hasText)
                .findFirst()
                .orElse(null);
    }

    private Optional<Integer> extractLimit(String normalizedInput) {
        for (Pattern pattern : LIMIT_PATTERNS) {
            Optional<Integer> value = extractNumber(normalizedInput, pattern);
            if (value.isPresent()) {
                return value;
            }
        }
        return Optional.empty();
    }

    private Optional<Integer> extractNumber(String normalizedInput, Pattern pattern) {
        if (!StringUtils.hasText(normalizedInput) || pattern == null) {
            return Optional.empty();
        }
        Matcher matcher = pattern.matcher(normalizedInput);
        if (!matcher.find()) {
            return Optional.empty();
        }
        try {
            return Optional.of(Integer.parseInt(matcher.group(1)));
        }
        catch (NumberFormatException exception) {
            return Optional.empty();
        }
    }

    private String sanitizeUsername(String username) {
        if (!StringUtils.hasText(username)) {
            return null;
        }
        // 先去掉口语化动词前缀，避免“要查询张三”被整体当成姓名。
        String sanitized = stripLeadingPrefixes(username)
                .replace("\u6709\u54ea\u4e9b", "")
                .replace(HIGH_INTENT_CUSTOMER, "")
                .replace("\u7684", "")
                .trim();
        return StringUtils.hasText(sanitized) ? sanitized : null;
    }

    private String stripLeadingPrefixes(String input) {
        if (!StringUtils.hasText(input)) {
            return "";
        }
        String normalized = input;
        boolean removed;
        do {
            removed = false;
            for (String prefix : PREFIXES) {
                if (normalized.startsWith(prefix)) {
                    normalized = normalized.substring(prefix.length());
                    removed = true;
                }
            }
        }
        while (removed);
        return normalized;
    }
}
