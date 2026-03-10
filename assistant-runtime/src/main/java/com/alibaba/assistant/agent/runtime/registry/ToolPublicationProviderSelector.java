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
package com.alibaba.assistant.agent.runtime.registry;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Selects runtime publication providers for the current invocation scope.
 */
@Component
public class ToolPublicationProviderSelector {

    /**
     * Select providers for the current publication scope.
     */
    public List<ToolPublicationProvider> selectProviders(
            ToolPublicationProvider.PublicationScope scope,
            List<ToolPublicationProvider> providers) {
        if (providers == null || providers.isEmpty()) {
            return List.of();
        }

        ToolPublicationProvider.PublicationScope effectiveScope = scope != null
                ? scope
                : new ToolPublicationProvider.PublicationScope("default", null, null, null);
        Map<String, ToolPublicationProvider> eligible = new LinkedHashMap<>();
        for (ToolPublicationProvider provider : providers) {
            if (provider == null) {
                continue;
            }
            String providerId = normalizeId(provider.providerId());
            if (providerId == null) {
                providerId = normalizeId(provider.getClass().getName() + "@" + System.identityHashCode(provider));
            }
            if (providerId == null || eligible.containsKey(providerId)) {
                continue;
            }
            eligible.put(providerId, provider);
        }

        if (eligible.isEmpty()) {
            return List.of();
        }

        Set<String> blocked = new LinkedHashSet<>(effectiveScope.blockedSourceIds());
        List<String> requested = effectiveScope.requestedSourceIds();
        List<ToolPublicationProvider> selected = new ArrayList<>();
        Set<String> added = new LinkedHashSet<>();

        if (effectiveScope.sourceSelectionMode() == ToolPublicationProvider.SourceSelectionMode.EXCLUSIVE
                && !requested.isEmpty()) {
            for (String requestedId : requested) {
                addIfEligible(selected, added, blocked, eligible, requestedId);
            }
            return List.copyOf(selected);
        }

        for (String requestedId : requested) {
            addIfEligible(selected, added, blocked, eligible, requestedId);
        }
        for (Map.Entry<String, ToolPublicationProvider> entry : eligible.entrySet()) {
            addIfEligible(selected, added, blocked, eligible, entry.getKey());
        }
        return List.copyOf(selected);
    }

    private void addIfEligible(
            List<ToolPublicationProvider> selected,
            Set<String> added,
            Set<String> blocked,
            Map<String, ToolPublicationProvider> eligible,
            String providerId) {
        String normalized = normalizeId(providerId);
        if (normalized == null || blocked.contains(normalized) || !added.add(normalized)) {
            return;
        }
        ToolPublicationProvider provider = eligible.get(normalized);
        if (provider != null) {
            selected.add(provider);
        }
    }

    private String normalizeId(String providerId) {
        if (providerId == null || providerId.isBlank()) {
            return null;
        }
        return providerId.trim().toLowerCase(Locale.ROOT);
    }
}
