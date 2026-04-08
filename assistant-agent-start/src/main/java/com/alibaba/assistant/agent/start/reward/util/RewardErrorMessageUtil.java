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
            "员工奖惩处理失败",
            "处理失败",
            "奖惩流程执行失败");

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
