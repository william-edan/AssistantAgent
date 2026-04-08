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
package com.alibaba.assistant.agent.start.reward.node;

import com.alibaba.assistant.agent.start.reward.model.RewardIntentResult;
import com.alibaba.assistant.agent.start.reward.model.RewardWorkflowMode;
import com.alibaba.assistant.agent.start.reward.util.AmountParseUtil;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 员工奖惩意图识别节点。
 */
public class RewardIntentNode {

    private static final String USER_NAME_PATTERN = "[\\p{IsHan}A-Za-z·]{2,20}";

    private static final List<Pattern> USER_PATTERNS = List.of(
            Pattern.compile("(?:奖励|惩罚|处罚)\\s*(" + USER_NAME_PATTERN + ")"),
            Pattern.compile("(?:给|把)\\s*(" + USER_NAME_PATTERN + ")\\s*(?:奖励|惩罚|处罚)"));

    private static final Pattern DATE_PATTERN = Pattern.compile("(\\d{4}-\\d{2}-\\d{2})");

    private static final List<String> NON_USER_TOKENS = List.of(
            "生日",
            "员工",
            "人员",
            "这个月",
            "本月",
            "当月",
            "所有",
            "的人");

    public RewardIntentResult identify(String userInput) {
        return new RewardIntentResult(
                RewardWorkflowMode.FORM,
                isPunishment(userInput) ? 2 : 1,
                extractUserName(userInput),
                AmountParseUtil.parse(userInput).orElse(null),
                extractRewardDate(userInput),
                null,
                containsBirthday(userInput) ? "生日" : null);
    }

    private boolean isPunishment(String userInput) {
        return StringUtils.hasText(userInput) && (userInput.contains("惩罚") || userInput.contains("处罚"));
    }

    private boolean containsBirthday(String userInput) {
        return StringUtils.hasText(userInput) && userInput.contains("生日");
    }

    private String extractUserName(String userInput) {
        if (!StringUtils.hasText(userInput)) {
            return null;
        }
        return USER_PATTERNS.stream()
                .map(pattern -> pattern.matcher(userInput))
                .filter(Matcher::find)
                .map(matcher -> matcher.group(1))
                .map(String::trim)
                .filter(StringUtils::hasText)
                .filter(candidate -> NON_USER_TOKENS.stream().noneMatch(candidate::contains))
                .findFirst()
                .orElse(null);
    }

    private LocalDate extractRewardDate(String userInput) {
        if (!StringUtils.hasText(userInput)) {
            return null;
        }
        if (userInput.contains("今天") || userInput.contains("今日")) {
            return LocalDate.now();
        }
        Matcher matcher = DATE_PATTERN.matcher(userInput);
        return matcher.find() ? LocalDate.parse(matcher.group(1)) : null;
    }
}
