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

import com.alibaba.assistant.agent.controlplane.toolregistry.ToolMetaUpsertCommand;

import java.util.Map;

/**
 * Request body for managed tool upserts.
 */
public record ManagedToolMetaRequest(
        String toolName,
        String description,
        String toolType,
        String visibility,
        String invocationPolicy,
        String executionMode,
        Map<String, Object> parameterSchema,
        Map<String, Object> executionPlan,
        Map<String, Object> interactionPolicy,
        String apiEndpoint,
        String httpMethod,
        String contentType,
        String riskLevel,
        Boolean requiresAuth,
        Boolean requiresConfirm,
        String capabilityType,
        Integer version,
        String status) {

    public static ManagedToolMetaRequest empty() {
        return new ManagedToolMetaRequest(
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null);
    }

    public ToolMetaUpsertCommand toCommand() {
        return new ToolMetaUpsertCommand(
                toolName,
                description,
                toolType,
                visibility,
                invocationPolicy,
                executionMode,
                parameterSchema,
                executionPlan,
                interactionPolicy,
                apiEndpoint,
                httpMethod,
                contentType,
                riskLevel,
                requiresAuth,
                requiresConfirm,
                capabilityType,
                version,
                status);
    }
}
