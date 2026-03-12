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

import org.slf4j.Logger;
import org.springframework.lang.Nullable;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * Helper for structured observability around legacy compatibility fallbacks.
 *
 * @author Assistant Agent Team
 * @since 1.0.0
 */
public final class LegacyCompatibilityLogHelper {

    private LegacyCompatibilityLogHelper() {
    }

    public static void logFallback(
            Logger logger,
            String location,
            String target,
            @Nullable ToolPublicationProvider.PublicationScope scope,
            @Nullable String tenantId,
            @Nullable String toolCode) {
        CompatibilityMode mode = compatibilityMode(scope);
        String effectiveTenant = StringUtils.hasText(tenantId) ? tenantId : "default";
        if (mode == CompatibilityMode.UNSCOPED) {
            logger.info("{} - compatibility fallback to {}, mode={} tenantId={} toolCode={}",
                    location,
                    target,
                    mode.value,
                    effectiveTenant,
                    toolCode);
            return;
        }
        logger.warn("{} - compatibility fallback to {}, mode={} tenantId={} spaceId={} agentAppCode={} toolCode={}",
                location,
                target,
                mode.value,
                effectiveTenant,
                scope != null ? scope.spaceId() : null,
                scope != null ? scope.agentAppCode() : null,
                toolCode);
    }

    static CompatibilityMode compatibilityMode(@Nullable ToolPublicationProvider.PublicationScope scope) {
        if (scope == null || scope.spaceId() == null || !StringUtils.hasText(scope.agentAppCode())) {
            return CompatibilityMode.UNSCOPED;
        }
        if (containsIgnoreCase(scope.requestedSourceIds(), "legacy-bridge")) {
            return CompatibilityMode.EXPLICIT_REQUEST;
        }
        if (scope.sourceSelectionMode() == ToolPublicationProvider.SourceSelectionMode.MERGE
                && containsIgnoreCase(scope.requestedSourceIds(), "artifact-catalog")
                && !containsIgnoreCase(scope.blockedSourceIds(), "legacy-bridge")) {
            return CompatibilityMode.FALLBACK;
        }
        return CompatibilityMode.SCOPED_COMPATIBILITY;
    }

    private static boolean containsIgnoreCase(List<String> values, String target) {
        if (values == null || values.isEmpty() || !StringUtils.hasText(target)) {
            return false;
        }
        for (String value : values) {
            if (value != null && target.equalsIgnoreCase(value.trim())) {
                return true;
            }
        }
        return false;
    }

    enum CompatibilityMode {
        UNSCOPED("unscoped"),
        EXPLICIT_REQUEST("explicit_request"),
        FALLBACK("fallback"),
        SCOPED_COMPATIBILITY("scoped_compatibility");

        private final String value;

        CompatibilityMode(String value) {
            this.value = value;
        }
    }
}
