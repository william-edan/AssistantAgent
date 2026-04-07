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

import com.alibaba.assistant.agent.start.reward.model.RewardCategoryRecord;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * 奖惩分类匹配工具。
 */
public final class CategoryMatchUtil {

    private CategoryMatchUtil() {
    }

    public static Optional<RewardCategoryRecord> match(String userInput, List<RewardCategoryRecord> categories) {
        List<RewardCategoryRecord> normalizedCategories = Optional.ofNullable(categories)
                .orElse(List.of())
                .stream()
                .filter(category -> category != null && StringUtils.hasText(category.name()))
                .toList();
        if (normalizedCategories.isEmpty()) {
            return Optional.empty();
        }
        String normalizedInput = normalize(userInput);

        Optional<RewardCategoryRecord> explicitMatch = normalizedCategories.stream()
                .filter(category -> normalizedInput.contains(normalize(category.name())))
                .findFirst();
        if (explicitMatch.isPresent()) {
            return explicitMatch;
        }
        if (normalizedInput.contains("生日")) {
            Optional<RewardCategoryRecord> birthdayMatch = normalizedCategories.stream()
                    .filter(category -> normalize(category.name()).contains("生日"))
                    .findFirst();
            if (birthdayMatch.isPresent()) {
                return birthdayMatch;
            }
        }
        Optional<RewardCategoryRecord> excellentMatch = normalizedCategories.stream()
                .filter(category -> normalize(category.name()).contains("表现优秀"))
                .findFirst();
        return excellentMatch.isPresent() ? excellentMatch : Optional.of(normalizedCategories.get(0));
    }

    private static String normalize(String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }
}
