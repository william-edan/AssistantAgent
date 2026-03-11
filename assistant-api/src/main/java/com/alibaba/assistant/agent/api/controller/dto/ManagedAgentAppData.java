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
package com.alibaba.assistant.agent.api.controller.dto;

import com.alibaba.assistant.agent.controlplane.agentapp.ResolvedAgentAppManagementView;
import org.springframework.util.StringUtils;

import java.util.Locale;
import java.util.Map;

/**
 * Managed agent-app payload.
 */
public record ManagedAgentAppData(
        String agentAppCode,
        String displayName,
        Map<String, Object> promptPolicy,
        Map<String, Object> memoryPolicy,
        Map<String, Object> approvalStrategy,
        String status) {

    public static ManagedAgentAppData from(ResolvedAgentAppManagementView resolved) {
        return new ManagedAgentAppData(
                resolved.agentAppCode(),
                resolved.displayName(),
                resolved.promptPolicy(),
                resolved.memoryPolicy(),
                resolved.approvalStrategy(),
                upper(resolved.status()));
    }

    private static String upper(String value) {
        return StringUtils.hasText(value) ? value.trim().toUpperCase(Locale.ROOT) : "";
    }
}
