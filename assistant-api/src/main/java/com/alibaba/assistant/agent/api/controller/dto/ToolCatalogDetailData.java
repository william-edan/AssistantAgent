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

import com.alibaba.assistant.agent.controlplane.toolregistry.ResolvedToolMetaDetailView;

import java.util.Map;

/**
 * Tool catalog detail payload.
 */
public record ToolCatalogDetailData(
        String spaceCode,
        String environment,
        String toolCode,
        String toolName,
        String description,
        String systemCode,
        String toolType,
        String visibility,
        String invocationPolicy,
        String executionMode,
        String apiEndpoint,
        String httpMethod,
        String contentType,
        Map<String, Object> parameterSchema,
        Map<String, Object> executionPlan,
        Map<String, Object> interactionPolicy,
        String riskLevel,
        Boolean requiresAuth,
        Boolean requiresConfirm,
        String capabilityType,
        String status,
        Integer version) {

    public static ToolCatalogDetailData from(ResolvedToolMetaDetailView resolved) {
        return new ToolCatalogDetailData(
                resolved.spaceCode(),
                resolved.environment() == null ? null : resolved.environment().toUpperCase(),
                resolved.toolCode(),
                resolved.toolName(),
                resolved.description(),
                resolved.systemCode(),
                normalizeUpper(resolved.toolType()),
                normalizeUpper(resolved.visibility()),
                normalizeUpper(resolved.invocationPolicy()),
                normalizeUpper(resolved.executionMode()),
                resolved.apiEndpoint(),
                normalizeUpper(resolved.httpMethod()),
                resolved.contentType(),
                resolved.parameterSchema(),
                resolved.executionPlan(),
                resolved.interactionPolicy(),
                normalizeUpper(resolved.riskLevel()),
                resolved.requiresAuth(),
                resolved.requiresConfirm(),
                normalizeUpper(resolved.capabilityType()),
                normalizeUpper(resolved.status()),
                resolved.version());
    }

    private static String normalizeUpper(String value) {
        return value == null ? null : value.toUpperCase();
    }
}
