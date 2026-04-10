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
package com.alibaba.assistant.agent.start.expense.intent;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Optional;

/**
 * 报销新增意图识别器。
 *
 * <p>识别结果统一映射到 {@code expense_add}，供快速路由 Hook 直接复用。</p>
 */
@Component
public class ExpenseAddIntentRecognizer {

    public static final String INTENT_CODE = "expense_add";

    /**
     * 识别报销新增意图。
     */
    public RecognitionResult recognize(String userInput) {
        String normalizedInput = Optional.ofNullable(userInput)
                .map(String::trim)
                .orElse("");
        if (!StringUtils.hasText(normalizedInput)) {
            return RecognitionResult.notMatched();
        }

        boolean hasExpenseKeyword = containsAny(normalizedInput, "报销", "交通费", "差旅费", "费用申请");
        boolean hasActionKeyword = containsAny(normalizedInput, "我要", "提交", "申请", "添加", "新增", "发起")
                || normalizedInput.contains("报销");
        boolean looksLikeQuery = containsAny(normalizedInput, "查询", "查一下", "查看", "记录", "列表", "进度", "状态");
        if (!hasExpenseKeyword || !hasActionKeyword || looksLikeQuery && !containsAny(normalizedInput, "申请", "提交", "新增")) {
            return RecognitionResult.notMatched();
        }
        return new RecognitionResult(true, INTENT_CODE, normalizedInput);
    }

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
     * 报销意图识别结果。
     */
    public record RecognitionResult(
            boolean matched,
            String intentCode,
            String originalInput) {

        public static RecognitionResult notMatched() {
            return new RecognitionResult(false, null, null);
        }
    }
}
