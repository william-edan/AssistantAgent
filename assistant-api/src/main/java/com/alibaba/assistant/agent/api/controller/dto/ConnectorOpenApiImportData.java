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

import com.alibaba.assistant.agent.controlplane.connector.ConnectorOpenApiImportResult;

import java.util.List;

/**
 * Response data for OpenAPI import results.
 */
public record ConnectorOpenApiImportData(
        String spaceCode,
        String environment,
        String connectorCode,
        String systemCode,
        String resolvedBaseUrl,
        int importedCount,
        List<ManagedToolMetaData> tools,
        List<String> warnings) {

    public static ConnectorOpenApiImportData from(ConnectorOpenApiImportResult result) {
        return new ConnectorOpenApiImportData(
                result.spaceCode(),
                result.environment(),
                result.connectorCode(),
                result.systemCode(),
                result.resolvedBaseUrl(),
                result.importedCount(),
                result.tools().stream().map(ManagedToolMetaData::from).toList(),
                result.warnings());
    }
}
