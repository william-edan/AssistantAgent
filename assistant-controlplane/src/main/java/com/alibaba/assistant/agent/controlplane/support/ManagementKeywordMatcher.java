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
package com.alibaba.assistant.agent.controlplane.support;

import org.springframework.util.StringUtils;

import java.util.Locale;

/**
 * Shared keyword matching helpers for control-plane management list endpoints.
 */
public final class ManagementKeywordMatcher {

    private ManagementKeywordMatcher() {
    }

    /**
     * Normalize a user-supplied keyword for case-insensitive contains matching.
     *
     * @param keyword raw keyword input
     * @return trimmed lowercase keyword, or {@code null} when blank
     */
    public static String normalizeKeyword(String keyword) {
        return StringUtils.hasText(keyword) ? keyword.trim().toLowerCase(Locale.ROOT) : null;
    }

    /**
     * Check whether any candidate contains the normalized keyword.
     *
     * @param normalizedKeyword normalized keyword from {@link #normalizeKeyword(String)}
     * @param candidates string candidates to match against
     * @return {@code true} when keyword is blank or any candidate contains it
     */
    public static boolean matches(String normalizedKeyword, String... candidates) {
        if (!StringUtils.hasText(normalizedKeyword)) {
            return true;
        }
        if (candidates == null) {
            return false;
        }
        for (String candidate : candidates) {
            if (StringUtils.hasText(candidate)
                    && candidate.toLowerCase(Locale.ROOT).contains(normalizedKeyword)) {
                return true;
            }
        }
        return false;
    }
}
