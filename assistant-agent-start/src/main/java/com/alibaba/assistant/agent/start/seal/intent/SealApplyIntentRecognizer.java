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
package com.alibaba.assistant.agent.start.seal.intent;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Optional;

/**
 * 用章申请意图识别器。
 *
 * <p>识别结果统一映射到 {@code seal_apply}，供快速意图 Hook 复用。</p>
 */
@Component
public class SealApplyIntentRecognizer {

    public static final String INTENT_CODE = "seal_apply";

    /**
     * 识别用章申请意图。
     */
    public RecognitionResult recognize(String userInput) {
        String normalizedInput = Optional.ofNullable(userInput)
                .map(String::trim)
                .orElse("");
        if (!StringUtils.hasText(normalizedInput)) {
            return RecognitionResult.notMatched();
        }
        boolean hasSealKeyword = containsAny(normalizedInput, "用章", "用印", "盖章");
        boolean hasActionKeyword = containsAny(normalizedInput, "我要", "申请", "提交", "发起");
        if (!hasSealKeyword || !hasActionKeyword) {
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
     * 用章意图识别结果。
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
