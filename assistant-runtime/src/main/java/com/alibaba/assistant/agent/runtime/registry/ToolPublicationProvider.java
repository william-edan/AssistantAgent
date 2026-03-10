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

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/**
 * Provider contract for runtime-published tools.
 */
public interface ToolPublicationProvider {

    /**
     * List published tool descriptors for a runtime publication scope.
     */
    List<PublishedToolDescriptor> listPublishedTools(PublicationScope scope);

    /**
     * Stable provider identifier used by runtime source-selection policies.
     */
    default String providerId() {
        return getClass().getSimpleName().toLowerCase(Locale.ROOT);
    }

    /**
     * Whether this provider can participate in the current scope.
     */
    default boolean supportsScope(PublicationScope scope) {
        return true;
    }

    /**
     * Runtime source-selection mode.
     */
    enum SourceSelectionMode {
        MERGE,
        EXCLUSIVE;

        static SourceSelectionMode fromValue(String raw) {
            if (raw == null || raw.isBlank()) {
                return MERGE;
            }
            String normalized = raw.trim().toUpperCase(Locale.ROOT);
            for (SourceSelectionMode mode : values()) {
                if (mode.name().equals(normalized)) {
                    return mode;
                }
            }
            return MERGE;
        }
    }

    /**
     * Publication lookup scope passed to runtime publication providers.
     */
    record PublicationScope(
            String tenantId,
            Long spaceId,
            String environment,
            String agentAppCode,
            SourceSelectionMode sourceSelectionMode,
            List<String> requestedSourceIds,
            List<String> blockedSourceIds) {

        public PublicationScope(String tenantId, Long spaceId, String environment, String agentAppCode) {
            this(tenantId, spaceId, environment, agentAppCode, SourceSelectionMode.MERGE, List.of(), List.of());
        }

        public PublicationScope {
            sourceSelectionMode = sourceSelectionMode != null ? sourceSelectionMode : SourceSelectionMode.MERGE;
            requestedSourceIds = normalizeIds(requestedSourceIds);
            blockedSourceIds = normalizeIds(blockedSourceIds);
        }

        private static List<String> normalizeIds(Collection<String> ids) {
            if (ids == null || ids.isEmpty()) {
                return List.of();
            }
            LinkedHashSet<String> normalized = new LinkedHashSet<>();
            for (String id : ids) {
                if (id == null || id.isBlank()) {
                    continue;
                }
                normalized.add(id.trim().toLowerCase(Locale.ROOT));
            }
            return normalized.isEmpty() ? List.of() : List.copyOf(new ArrayList<>(normalized));
        }
    }
}
