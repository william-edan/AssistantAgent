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
package com.alibaba.assistant.agent.start.invoice.intent;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Optional;

/**
 * 开票申请意图识别器。
 *
 * <p>只负责识别“我要开票/给某人开发票”这类新增开票请求，不处理查询类意图。</p>
 */
@Component
public class InvoiceApplyIntentRecognizer {

    public static final String INTENT_CODE = "invoice_apply";

    /**
     * 识别开票申请意图。
     */
    public RecognitionResult recognize(String userInput) {
        String normalizedInput = Optional.ofNullable(userInput)
                .map(String::trim)
                .orElse("");
        if (!StringUtils.hasText(normalizedInput)) {
            return RecognitionResult.notMatched();
        }

        boolean hasInvoiceKeyword = containsAny(normalizedInput, "开票", "开发票", "发票申请", "申请发票", "专票", "普票");
        boolean hasActionKeyword = containsAny(normalizedInput, "我要", "申请", "提交", "新增", "添加", "给", "帮");
        boolean looksLikeQuery = containsAny(normalizedInput, "查询", "查看", "进度", "状态", "列表", "记录");
        if (!hasInvoiceKeyword || !hasActionKeyword || looksLikeQuery && !containsAny(normalizedInput, "申请", "新增", "添加", "提交")) {
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
     * 开票申请识别结果。
     */
    public record RecognitionResult(boolean matched, String intentCode, String originalInput) {

        public static RecognitionResult notMatched() {
            return new RecognitionResult(false, null, null);
        }
    }
}
