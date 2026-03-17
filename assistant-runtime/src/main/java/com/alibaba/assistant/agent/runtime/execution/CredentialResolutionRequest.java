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
package com.alibaba.assistant.agent.runtime.execution;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 原生执行链使用的统一凭证解析请求。
 */
public record CredentialResolutionRequest(
        Long spaceId,
        Long connectorId,
        List<String> candidateAuthProfileCodes,
        String platformPrincipalId,
        String platformPrincipalType,
        List<String> requestedScopes,
        String runId,
        String stepId,
        String agentAppCode,
        String rolePackageCode,
        String rolePackageVersion,
        String scenarioCode,
        String executionSubjectType,
        String executionSubjectId) {

    public CredentialResolutionRequest {
        candidateAuthProfileCodes = normalize(candidateAuthProfileCodes);
        requestedScopes = normalize(requestedScopes);
        platformPrincipalId = normalize(platformPrincipalId);
        platformPrincipalType = normalize(platformPrincipalType);
        runId = normalize(runId);
        stepId = normalize(stepId);
        agentAppCode = normalize(agentAppCode);
        rolePackageCode = normalize(rolePackageCode);
        rolePackageVersion = normalize(rolePackageVersion);
        scenarioCode = normalize(scenarioCode);
        executionSubjectType = normalize(executionSubjectType);
        executionSubjectId = normalize(executionSubjectId);
    }

    public CredentialResolutionRequest(
            Long spaceId,
            Long connectorId,
            List<String> candidateAuthProfileCodes,
            String platformPrincipalId,
            String platformPrincipalType,
            List<String> requestedScopes,
            String runId,
            String stepId) {
        this(
                spaceId,
                connectorId,
                candidateAuthProfileCodes,
                platformPrincipalId,
                platformPrincipalType,
                requestedScopes,
                runId,
                stepId,
                null,
                null,
                null,
                null,
                null,
                null);
    }

    private static List<String> normalize(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        Set<String> normalized = new LinkedHashSet<>();
        for (String value : values) {
            String text = normalize(value);
            if (text != null) {
                normalized.add(text);
            }
        }
        return List.copyOf(normalized);
    }

    private static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
