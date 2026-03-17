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
package com.alibaba.assistant.agent.runtime.role;

import com.alibaba.assistant.agent.controlplane.rolepackage.ResolvedRolePackageManagementView;
import org.springframework.context.annotation.Profile;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Lightweight scenario router for role packages.
 */
@Component
@Profile("migration")
public class ScenarioRouter {

    private final RoleContextResolver roleContextResolver;

    public ScenarioRouter(RoleContextResolver roleContextResolver) {
        this.roleContextResolver = roleContextResolver;
    }

    /**
     * Resolve a scenario code from runtime attributes and the current user input.
     */
    public Optional<String> resolveScenario(@Nullable Map<String, Object> attributes, @Nullable String userMessage) {
        if (!StringUtils.hasText(userMessage)) {
            return Optional.empty();
        }
        return roleContextResolver.resolve(attributes)
                .flatMap(context -> resolveScenario(context, userMessage));
    }

    Optional<String> resolveScenario(RoleContextResolver.RoleContext context, String userMessage) {
        if (context == null || context.scenarios().isEmpty() || !StringUtils.hasText(userMessage)) {
            return Optional.empty();
        }
        String normalizedMessage = normalize(userMessage);
        String bestScenarioCode = null;
        int bestScore = 0;
        for (ResolvedRolePackageManagementView.RoleScenarioView scenario : context.scenarios()) {
            if (scenario == null || !StringUtils.hasText(scenario.scenarioCode())) {
                continue;
            }
            int score = scoreScenario(scenario, normalizedMessage);
            if (score > bestScore) {
                bestScore = score;
                bestScenarioCode = scenario.scenarioCode();
            }
        }
        return bestScore > 0 ? Optional.of(bestScenarioCode) : Optional.empty();
    }

    private int scoreScenario(ResolvedRolePackageManagementView.RoleScenarioView scenario, String normalizedMessage) {
        int score = 0;
        score += scoreText(normalizedMessage, scenario.scenarioCode(), 4);
        score += scoreText(normalizedMessage, scenario.displayName(), 5);
        score += scoreText(normalizedMessage, scenario.description(), 3);
        if (scenario.routingHints() != null && !scenario.routingHints().isEmpty()) {
            for (Object value : scenario.routingHints().values()) {
                for (String token : flattenHintValues(value)) {
                    score += scoreText(normalizedMessage, token, 6);
                }
            }
        }
        return score;
    }

    private int scoreText(String normalizedMessage, String rawCandidate, int weight) {
        if (!StringUtils.hasText(rawCandidate)) {
            return 0;
        }
        String normalizedCandidate = normalize(rawCandidate);
        if (!StringUtils.hasText(normalizedCandidate)) {
            return 0;
        }
        if (normalizedMessage.contains(normalizedCandidate)) {
            return weight;
        }
        int tokenScore = 0;
        for (String token : normalizedCandidate.split("[\\s/_-]+")) {
            if (token.length() < 2) {
                continue;
            }
            if (normalizedMessage.contains(token)) {
                tokenScore += Math.max(1, weight - 2);
            }
        }
        return tokenScore;
    }

    private List<String> flattenHintValues(Object value) {
        if (value == null) {
            return List.of();
        }
        List<String> flattened = new ArrayList<>();
        if (value instanceof String text) {
            flattened.add(text);
        }
        else if (value instanceof Collection<?> collection) {
            for (Object item : collection) {
                flattened.addAll(flattenHintValues(item));
            }
        }
        else if (value instanceof Map<?, ?> map) {
            flattened.addAll(flattenHintValues(map.values()));
        }
        else {
            flattened.add(String.valueOf(value));
        }
        return flattened;
    }

    private String normalize(String value) {
        return StringUtils.hasText(value) ? value.trim().toLowerCase(Locale.ROOT) : "";
    }
}
