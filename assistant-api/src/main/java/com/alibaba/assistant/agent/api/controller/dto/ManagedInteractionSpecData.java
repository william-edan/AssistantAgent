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

import com.alibaba.assistant.agent.controlplane.interaction.ResolvedInteractionSpecManagementView;
import org.springframework.util.StringUtils;

import java.util.Locale;
import java.util.Map;

/**
 * Managed interaction-spec payload.
 */
public record ManagedInteractionSpecData(
        String interactionCode,
        Map<String, Object> slotSchema,
        Map<String, Object> askStrategy,
        Map<String, Object> autoFillRules,
        Map<String, Object> summaryLayout,
        Map<String, Object> confirmationPolicy,
        Map<String, Object> editPolicy,
        String status) {

    public static ManagedInteractionSpecData from(ResolvedInteractionSpecManagementView resolved) {
        return new ManagedInteractionSpecData(
                resolved.interactionCode(),
                resolved.slotSchema(),
                resolved.askStrategy(),
                resolved.autoFillRules(),
                resolved.summaryLayout(),
                resolved.confirmationPolicy(),
                resolved.editPolicy(),
                upper(resolved.status()));
    }

    private static String upper(String value) {
        return StringUtils.hasText(value) ? value.trim().toUpperCase(Locale.ROOT) : "";
    }
}
