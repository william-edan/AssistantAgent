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
package com.alibaba.assistant.agent.controlplane.connector;

import com.alibaba.assistant.agent.controlplane.toolregistry.ResolvedToolMetaManagementView;
import com.alibaba.assistant.agent.controlplane.toolregistry.ToolMetaManagementService;
import com.alibaba.assistant.agent.controlplane.toolregistry.ToolMetaUpsertCommand;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.parameters.RequestBody;
import io.swagger.v3.parser.OpenAPIV3Parser;
import io.swagger.v3.parser.core.models.ParseOptions;
import io.swagger.v3.parser.core.models.SwaggerParseResult;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Imports OpenAPI documents into connector-scoped canonical ToolMeta records.
 */
@Service
public class ConnectorOpenApiOnboardingService {

    private static final String DEFAULT_ENVIRONMENT = "prod";

    private static final String APPLICATION_JSON = "application/json";

    private static final String FORM_URLENCODED = "application/x-www-form-urlencoded";

    private final ConnectorManagementService connectorManagementService;

    private final ToolMetaManagementService toolMetaManagementService;

    private final ObjectMapper objectMapper;

    public ConnectorOpenApiOnboardingService(
            ConnectorManagementService connectorManagementService,
            ToolMetaManagementService toolMetaManagementService,
            ObjectMapper objectMapper) {
        this.connectorManagementService = connectorManagementService;
        this.toolMetaManagementService = toolMetaManagementService;
        this.objectMapper = objectMapper;
    }

    public Optional<ConnectorOpenApiImportResult> importOpenApi(
            String spaceCode,
            String environment,
            String connectorCode,
            ConnectorOpenApiImportCommand command) {
        if (command == null || !StringUtils.hasText(command.document())) {
            throw new IllegalArgumentException("openapi_document_missing");
        }
        String normalizedEnvironment = normalizeEnvironment(environment);
        Optional<ResolvedConnectorView> connectorOptional = connectorManagementService
                .getConnector(spaceCode, normalizedEnvironment, connectorCode);
        if (connectorOptional.isEmpty()) {
            return Optional.empty();
        }

        SwaggerParseResult parseResult = parse(command.document());
        OpenAPI openApi = parseResult.getOpenAPI();
        if (openApi == null) {
            throw new IllegalArgumentException("openapi_document_invalid");
        }

        List<String> warnings = new ArrayList<>();
        if (parseResult.getMessages() != null) {
            warnings.addAll(parseResult.getMessages());
        }

        ResolvedConnectorView connector = connectorOptional.get();
        String serverBaseUrl = resolveServerBaseUrl(openApi);
        ResolvedConnectorView effectiveConnector = ensureConnectorBaseUrl(
                spaceCode,
                normalizedEnvironment,
                connector,
                command.baseUrl(),
                serverBaseUrl,
                warnings);
        String resolvedBaseUrl = firstNonBlank(command.baseUrl(), effectiveConnector.baseUrl(), serverBaseUrl);
        if (!StringUtils.hasText(resolvedBaseUrl)) {
            throw new IllegalArgumentException("connector_base_url_missing");
        }

        Set<String> selectedOperationIds = normalizeOperationSelection(command.operationIds());
        String toolCodePrefix = resolveToolCodePrefix(command.toolCodePrefix(), effectiveConnector);

        List<ResolvedToolMetaManagementView> importedTools = new ArrayList<>();
        if (openApi.getPaths() != null) {
            for (Map.Entry<String, PathItem> entry : openApi.getPaths().entrySet()) {
                PathItem pathItem = entry.getValue();
                if (pathItem == null || pathItem.readOperationsMap() == null) {
                    continue;
                }
                for (Map.Entry<PathItem.HttpMethod, Operation> operationEntry : pathItem.readOperationsMap().entrySet()) {
                    Operation operation = operationEntry.getValue();
                    if (operation == null || !shouldImport(operation, operationEntry.getKey(), entry.getKey(), selectedOperationIds)) {
                        continue;
                    }
                    Optional<ToolImportDefinition> definition = toImportDefinition(
                            entry.getKey(),
                            pathItem,
                            operationEntry.getKey(),
                            operation,
                            toolCodePrefix,
                            command,
                            warnings);
                    if (definition.isEmpty()) {
                        continue;
                    }
                    ToolImportDefinition importDefinition = definition.get();
                    ResolvedToolMetaManagementView imported = toolMetaManagementService.upsertTool(
                                    spaceCode,
                                    normalizedEnvironment,
                                    effectiveConnector.connectorCode(),
                                    importDefinition.toolCode(),
                                    importDefinition.command())
                            .orElseThrow(() -> new IllegalStateException("openapi_tool_import_failed"));
                    importedTools.add(imported);
                }
            }
        }

        if (importedTools.isEmpty()) {
            throw new IllegalArgumentException("openapi_import_no_operations");
        }

        return Optional.of(new ConnectorOpenApiImportResult(
                effectiveConnector.spaceCode(),
                effectiveConnector.environment(),
                effectiveConnector.connectorCode(),
                effectiveConnector.systemCode(),
                resolvedBaseUrl,
                importedTools.size(),
                List.copyOf(importedTools),
                List.copyOf(warnings)));
    }

    private SwaggerParseResult parse(String document) {
        ParseOptions parseOptions = new ParseOptions();
        parseOptions.setResolve(true);
        parseOptions.setResolveFully(true);
        parseOptions.setFlatten(true);
        return new OpenAPIV3Parser().readContents(document, null, parseOptions);
    }

    private ResolvedConnectorView ensureConnectorBaseUrl(
            String spaceCode,
            String environment,
            ResolvedConnectorView connector,
            String requestedBaseUrl,
            String serverBaseUrl,
            List<String> warnings) {
        String currentBaseUrl = normalize(connector.baseUrl());
        String desiredBaseUrl = firstNonBlank(requestedBaseUrl, currentBaseUrl, serverBaseUrl);
        if (!StringUtils.hasText(desiredBaseUrl)) {
            return connector;
        }
        boolean explicitOverride = StringUtils.hasText(requestedBaseUrl)
                && !desiredBaseUrl.equalsIgnoreCase(firstNonBlank(currentBaseUrl, ""));
        boolean inferredBackfill = !StringUtils.hasText(currentBaseUrl) && StringUtils.hasText(serverBaseUrl);
        if (!explicitOverride && !inferredBackfill) {
            return connector;
        }
        ResolvedConnectorView updated = connectorManagementService.upsertConnector(
                        spaceCode,
                        environment,
                        connector.connectorCode(),
                        new ConnectorUpsertCommand(
                                connector.systemCode(),
                                connector.displayName(),
                                connector.protocolType(),
                                connector.networkZone(),
                                desiredBaseUrl,
                                connector.status()))
                .orElseThrow(() -> new IllegalStateException("connector_base_url_update_failed"));
        warnings.add("connector_base_url_applied:" + desiredBaseUrl);
        return updated;
    }

    private Optional<ToolImportDefinition> toImportDefinition(
            String path,
            PathItem pathItem,
            PathItem.HttpMethod httpMethod,
            Operation operation,
            String toolCodePrefix,
            ConnectorOpenApiImportCommand command,
            List<String> warnings) {
        String method = httpMethod != null ? httpMethod.name() : "GET";
        RequestShape requestShape = buildRequestShape(path, pathItem, method, operation, warnings);
        if (requestShape.skipOperation()) {
            return Optional.empty();
        }

        String operationKey = resolveOperationKey(method, path, operation);
        String toolCode = toolCodePrefix + "." + normalizeCodeSegment(operationKey);
        String toolName = firstNonBlank(operation.getSummary(), operation.getOperationId(), method + " " + path);
        String description = firstNonBlank(operation.getDescription(), operation.getSummary(), method + " " + path);
        boolean queryLike = isQueryLikeMethod(method);

        Map<String, Object> interactionPolicy = new LinkedHashMap<>();
        interactionPolicy.put("source", "OPENAPI");
        if (StringUtils.hasText(operation.getOperationId())) {
            interactionPolicy.put("operationId", operation.getOperationId().trim());
        }
        if (operation.getTags() != null && !operation.getTags().isEmpty()) {
            interactionPolicy.put("tags", List.copyOf(operation.getTags()));
        }

        ToolMetaUpsertCommand upsertCommand = new ToolMetaUpsertCommand(
                toolName,
                description,
                queryLike ? "QUERY" : "ACTION",
                firstNonBlank(command.visibility(), queryLike ? "PLANNER" : "USER"),
                firstNonBlank(command.invocationPolicy(), queryLike ? "COMPOSABLE" : "DIRECT"),
                firstNonBlank(command.executionMode(), "SYNC"),
                requestShape.parameterSchema(),
                buildExecutionPlan(path, method, toolName, requestShape),
                interactionPolicy,
                path,
                method,
                requestShape.contentType(),
                firstNonBlank(command.riskLevel(), defaultRiskLevel(method)),
                command.requiresAuth() != null ? command.requiresAuth() : Boolean.TRUE,
                command.requiresConfirm() != null ? command.requiresConfirm() : defaultRequiresConfirm(method),
                firstNonBlank(command.capabilityType(), queryLike ? "READ" : "ACTION"),
                null,
                firstNonBlank(command.status(), "enabled"));
        return Optional.of(new ToolImportDefinition(toolCode, upsertCommand));
    }

    private RequestShape buildRequestShape(
            String path,
            PathItem pathItem,
            String method,
            Operation operation,
            List<String> warnings) {
        Map<String, Object> rootSchema = new LinkedHashMap<>();
        rootSchema.put("type", "object");
        Map<String, Object> properties = new LinkedHashMap<>();
        List<String> requiredGroups = new ArrayList<>();
        Map<String, String> inputMapping = new LinkedHashMap<>();

        ParameterGroup pathGroup = buildParameterGroup(pathItem, operation, "path");
        ParameterGroup queryGroup = buildParameterGroup(pathItem, operation, "query");
        ParameterGroup headerGroup = buildParameterGroup(pathItem, operation, "header");
        BodyGroup bodyGroup = buildBodyGroup(path, method, operation.getRequestBody(), warnings);

        addGroup(properties, requiredGroups, inputMapping, "path", pathGroup.schema(), pathGroup.required());
        addGroup(properties, requiredGroups, inputMapping, "query", queryGroup.schema(), queryGroup.required());
        addGroup(properties, requiredGroups, inputMapping, "headers", headerGroup.schema(), headerGroup.required());
        addGroup(properties, requiredGroups, inputMapping, "body", bodyGroup.schema(), bodyGroup.required());

        rootSchema.put("properties", properties.isEmpty() ? Map.of() : Map.copyOf(properties));
        if (!requiredGroups.isEmpty()) {
            rootSchema.put("required", List.copyOf(requiredGroups));
        }

        return new RequestShape(
                Map.copyOf(rootSchema),
                inputMapping.isEmpty() ? Map.of() : Map.copyOf(inputMapping),
                bodyGroup.contentType() != null ? bodyGroup.contentType() : APPLICATION_JSON,
                bodyGroup.skipOperation());
    }

    private ParameterGroup buildParameterGroup(PathItem pathItem, Operation operation, String location) {
        List<Parameter> parameters = collectParameters(pathItem, operation);
        if (parameters.isEmpty()) {
            return ParameterGroup.empty();
        }
        Map<String, Object> properties = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();
        for (Parameter parameter : parameters) {
            if (parameter == null || !location.equalsIgnoreCase(parameter.getIn())) {
                continue;
            }
            Schema<?> schema = parameter.getSchema();
            if (schema == null && parameter.getContent() != null && !parameter.getContent().isEmpty()) {
                MediaType mediaType = parameter.getContent().values().iterator().next();
                schema = mediaType != null ? mediaType.getSchema() : null;
            }
            Map<String, Object> schemaMap = schema != null ? toSchemaMap(schema) : new LinkedHashMap<>();
            if (schemaMap.isEmpty()) {
                schemaMap.put("type", "string");
            }
            if (StringUtils.hasText(parameter.getDescription()) && !schemaMap.containsKey("description")) {
                schemaMap.put("description", parameter.getDescription().trim());
            }
            properties.put(parameter.getName(), Map.copyOf(schemaMap));
            if (Boolean.TRUE.equals(parameter.getRequired())) {
                required.add(parameter.getName());
            }
        }
        if (properties.isEmpty()) {
            return ParameterGroup.empty();
        }
        Map<String, Object> groupSchema = new LinkedHashMap<>();
        groupSchema.put("type", "object");
        groupSchema.put("properties", Map.copyOf(properties));
        if (!required.isEmpty()) {
            groupSchema.put("required", List.copyOf(required));
        }
        return new ParameterGroup(Map.copyOf(groupSchema), !required.isEmpty() || "path".equalsIgnoreCase(location));
    }

    private List<Parameter> collectParameters(PathItem pathItem, Operation operation) {
        Map<String, Parameter> parameters = new LinkedHashMap<>();
        if (pathItem != null && pathItem.getParameters() != null) {
            for (Parameter parameter : pathItem.getParameters()) {
                if (parameter != null) {
                    parameters.put(parameterKey(parameter), parameter);
                }
            }
        }
        if (operation != null && operation.getParameters() != null) {
            for (Parameter parameter : operation.getParameters()) {
                if (parameter != null) {
                    parameters.put(parameterKey(parameter), parameter);
                }
            }
        }
        return List.copyOf(parameters.values());
    }

    private String parameterKey(Parameter parameter) {
        return normalize(parameter != null ? parameter.getIn() : null) + ":" + normalize(parameter != null ? parameter.getName() : null);
    }

    private BodyGroup buildBodyGroup(String path, String method, RequestBody requestBody, List<String> warnings) {
        if (requestBody == null || requestBody.getContent() == null || requestBody.getContent().isEmpty()) {
            return BodyGroup.empty();
        }
        MediaTypeSelection mediaTypeSelection = selectSupportedMediaType(path, method, requestBody.getContent(), warnings);
        if (mediaTypeSelection == null || mediaTypeSelection.mediaType() == null || mediaTypeSelection.schema() == null) {
            warnings.add("openapi_request_body_unsupported:" + method + " " + path);
            return BodyGroup.skip();
        }
        return new BodyGroup(
                Map.copyOf(toSchemaMap(mediaTypeSelection.schema())),
                Boolean.TRUE.equals(requestBody.getRequired()),
                mediaTypeSelection.mediaType(),
                false);
    }

    private MediaTypeSelection selectSupportedMediaType(
            String path,
            String method,
            Content content,
            List<String> warnings) {
        if (content == null || content.isEmpty()) {
            return null;
        }
        if (content.containsKey(APPLICATION_JSON) && content.get(APPLICATION_JSON) != null) {
            return new MediaTypeSelection(APPLICATION_JSON, content.get(APPLICATION_JSON).getSchema());
        }
        if (content.containsKey(FORM_URLENCODED) && content.get(FORM_URLENCODED) != null) {
            return new MediaTypeSelection(FORM_URLENCODED, content.get(FORM_URLENCODED).getSchema());
        }
        String unsupported = content.keySet().iterator().next();
        warnings.add("openapi_request_body_media_type_unsupported:" + method + " " + path + ":" + unsupported);
        return null;
    }

    private void addGroup(
            Map<String, Object> properties,
            List<String> requiredGroups,
            Map<String, String> inputMapping,
            String key,
            Map<String, Object> schema,
            boolean required) {
        if (schema == null || schema.isEmpty()) {
            return;
        }
        properties.put(key, schema);
        inputMapping.put(key, "${" + key + "}");
        if (required) {
            requiredGroups.add(key);
        }
    }

    private Map<String, Object> buildExecutionPlan(
            String path,
            String method,
            String toolName,
            RequestShape requestShape) {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("method", method);
        config.put("endpoint", path);
        config.put("contentType", requestShape.contentType());
        if (!requestShape.inputMapping().isEmpty()) {
            config.put("inputMapping", requestShape.inputMapping());
        }
        config.put("outputMapping", Map.of("response", "$"));

        Map<String, Object> step = new LinkedHashMap<>();
        step.put("stepId", "invoke");
        step.put("name", toolName);
        step.put("type", "HTTP");
        step.put("config", Map.copyOf(config));

        Map<String, Object> steps = new LinkedHashMap<>();
        steps.put("invoke", Map.copyOf(step));

        Map<String, Object> executionPlan = new LinkedHashMap<>();
        executionPlan.put("version", "2.0");
        executionPlan.put("entry", List.of("invoke"));
        executionPlan.put("terminal", List.of("invoke"));
        executionPlan.put("steps", Map.copyOf(steps));
        return Map.copyOf(executionPlan);
    }

    private Map<String, Object> toSchemaMap(Schema<?> schema) {
        if (schema == null) {
            return Map.of();
        }
        Map<String, Object> schemaMap = objectMapper.convertValue(schema, new TypeReference<Map<String, Object>>() {
        });
        if (schemaMap == null || schemaMap.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> sanitized = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : schemaMap.entrySet()) {
            if (entry.getKey() != null && entry.getValue() != null) {
                sanitized.put(entry.getKey(), entry.getValue());
            }
        }
        return sanitized.isEmpty() ? Map.of() : sanitized;
    }

    private boolean shouldImport(
            Operation operation,
            PathItem.HttpMethod httpMethod,
            String path,
            Set<String> selectedOperationIds) {
        if (selectedOperationIds.isEmpty()) {
            return true;
        }
        String operationId = normalize(operation != null ? operation.getOperationId() : null);
        String fallback = normalize(resolveOperationKey(httpMethod != null ? httpMethod.name() : "GET", path, operation));
        return selectedOperationIds.contains(operationId) || selectedOperationIds.contains(fallback);
    }

    private Set<String> normalizeOperationSelection(Collection<String> operationIds) {
        if (operationIds == null || operationIds.isEmpty()) {
            return Set.of();
        }
        Set<String> normalized = new LinkedHashSet<>();
        for (String operationId : operationIds) {
            String normalizedId = normalize(operationId);
            if (StringUtils.hasText(normalizedId)) {
                normalized.add(normalizedId);
            }
        }
        return Set.copyOf(normalized);
    }

    private String resolveServerBaseUrl(OpenAPI openApi) {
        if (openApi == null || openApi.getServers() == null || openApi.getServers().isEmpty()) {
            return null;
        }
        return normalize(openApi.getServers().get(0).getUrl());
    }

    private String resolveToolCodePrefix(String requestedPrefix, ResolvedConnectorView connector) {
        String base = firstNonBlank(requestedPrefix, connector.systemCode(), connector.connectorCode());
        String normalized = normalize(base);
        if (!StringUtils.hasText(normalized)) {
            throw new IllegalArgumentException("tool_code_prefix_missing");
        }
        return normalized.replaceAll("[^a-zA-Z0-9_]+", "_").toLowerCase(Locale.ROOT);
    }

    private String resolveOperationKey(String method, String path, Operation operation) {
        return firstNonBlank(
                operation != null ? operation.getOperationId() : null,
                method + "_" + path);
    }

    private boolean isQueryLikeMethod(String method) {
        return "GET".equalsIgnoreCase(method) || "HEAD".equalsIgnoreCase(method);
    }

    private String defaultRiskLevel(String method) {
        if ("DELETE".equalsIgnoreCase(method)) {
            return "HIGH";
        }
        if (isQueryLikeMethod(method)) {
            return "LOW";
        }
        return "MEDIUM";
    }

    private Boolean defaultRequiresConfirm(String method) {
        return "DELETE".equalsIgnoreCase(method) ? Boolean.TRUE : Boolean.FALSE;
    }

    private String normalizeEnvironment(String environment) {
        return StringUtils.hasText(environment) ? environment.trim() : DEFAULT_ENVIRONMENT;
    }

    private String normalize(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private String normalizeCodeSegment(String value) {
        if (!StringUtils.hasText(value)) {
            return "operation";
        }
        String normalized = value.trim()
                .replaceAll("([a-z0-9])([A-Z])", "$1_$2")
                .replaceAll("[^a-zA-Z0-9]+", "_")
                .replaceAll("_+", "_")
                .replaceAll("^_+", "")
                .replaceAll("_+$", "");
        return StringUtils.hasText(normalized) ? normalized.toLowerCase(Locale.ROOT) : "operation";
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value.trim();
            }
        }
        return null;
    }

    private record ToolImportDefinition(String toolCode, ToolMetaUpsertCommand command) {
    }

    private record RequestShape(
            Map<String, Object> parameterSchema,
            Map<String, String> inputMapping,
            String contentType,
            boolean skipOperation) {
    }

    private record ParameterGroup(Map<String, Object> schema, boolean required) {

        static ParameterGroup empty() {
            return new ParameterGroup(Map.of(), false);
        }
    }

    private record BodyGroup(Map<String, Object> schema, boolean required, String contentType, boolean skipOperation) {

        static BodyGroup empty() {
            return new BodyGroup(Map.of(), false, APPLICATION_JSON, false);
        }

        static BodyGroup skip() {
            return new BodyGroup(Map.of(), false, null, true);
        }
    }

    private record MediaTypeSelection(String mediaType, Schema<?> schema) {
    }
}
