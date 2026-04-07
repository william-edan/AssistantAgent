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

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 奖惩流程异常消息提取工具。
 */
public final class RewardErrorMessageUtil {

    private static final Set<String> GENERIC_MESSAGES = Set.of(
            "\u5458\u5de5\u5956\u60e9\u5904\u7406\u5931\u8d25",
            "\u5904\u7406\u5931\u8d25",
            "\u5956\u60e9\u6d41\u7a0b\u6267\u884c\u5931\u8d25");

    private RewardErrorMessageUtil() {
    }

    /**
     * 优先返回异常链中最具体的可读消息。
     */
    public static String resolveMessage(Throwable throwable, String fallbackMessage) {
        Set<Throwable> visited = new LinkedHashSet<>();
        String genericMessage = null;
        Throwable current = throwable;
        while (current != null && visited.add(current)) {
            String message = normalize(current.getMessage());
            if (StringUtils.hasText(message)) {
                if (!GENERIC_MESSAGES.contains(message)) {
                    return message;
                }
                if (!StringUtils.hasText(genericMessage)) {
                    genericMessage = message;
                }
            }
            current = current.getCause();
        }
        return StringUtils.hasText(genericMessage) ? genericMessage : normalize(fallbackMessage);
    }

    private static String normalize(String message) {
        if (!StringUtils.hasText(message)) {
            return null;
        }
        return message.trim();
    }
}
