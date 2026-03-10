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
package com.alibaba.assistant.agent.controlplane.agentapp;

import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/**
 * Typed control-plane definition for an agent-app default publication source policy.
 */
public record AgentAppPublicationSourcePolicy(
        String sourceSelectionMode,
        List<String> allowedSourceIds,
        List<String> blockedSourceIds) {

    public AgentAppPublicationSourcePolicy {
        sourceSelectionMode = normalizeMode(sourceSelectionMode);
        allowedSourceIds = normalizeIds(allowedSourceIds);
        blockedSourceIds = normalizeIds(blockedSourceIds);
    }

    /**
     * Whether this policy carries any non-default source-selection constraint.
     *
     * @return true when the policy is effectively empty
     */
    public boolean isEmpty() {
        return "MERGE".equals(sourceSelectionMode)
                && allowedSourceIds.isEmpty()
                && blockedSourceIds.isEmpty();
    }

    private static String normalizeMode(String rawMode) {
        if (!StringUtils.hasText(rawMode)) {
            return "MERGE";
        }
        return rawMode.trim().toUpperCase(Locale.ROOT);
    }

    private static List<String> normalizeIds(Collection<String> rawIds) {
        if (rawIds == null || rawIds.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String rawId : rawIds) {
            if (!StringUtils.hasText(rawId)) {
                continue;
            }
            normalized.add(rawId.trim().toLowerCase(Locale.ROOT));
        }
        return normalized.isEmpty() ? List.of() : List.copyOf(new ArrayList<>(normalized));
    }
}
