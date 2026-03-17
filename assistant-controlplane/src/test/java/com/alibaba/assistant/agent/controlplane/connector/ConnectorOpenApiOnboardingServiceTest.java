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
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.mock;

class ConnectorOpenApiOnboardingServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void shouldImportOpenApiOperationsIntoCanonicalToolMetaAndHydrateConnectorBaseUrl() throws Exception {
        ConnectorManagementService connectorManagementService = mock(ConnectorManagementService.class);
        ToolMetaManagementService toolMetaManagementService = mock(ToolMetaManagementService.class);
        ConnectorOpenApiOnboardingService service = new ConnectorOpenApiOnboardingService(
                connectorManagementService,
                toolMetaManagementService,
                objectMapper);

        when(connectorManagementService.getConnector("enterprise-default", "prod", "oa-core"))
                .thenReturn(Optional.of(new ResolvedConnectorView(
                        11L,
                        "enterprise-default",
                        "prod",
                        "oa-core",
                        "gougu_oa",
                        "OA Core",
                        "openapi",
                        "intranet",
                        null,
                        "active",
                        1)));
        when(connectorManagementService.upsertConnector(
                eq("enterprise-default"),
                eq("prod"),
                eq("oa-core"),
                any(ConnectorUpsertCommand.class)))
                .thenReturn(Optional.of(new ResolvedConnectorView(
                        11L,
                        "enterprise-default",
                        "prod",
                        "oa-core",
                        "gougu_oa",
                        "OA Core",
                        "openapi",
                        "intranet",
                        "http://oa.internal",
                        "active",
                        2)));
        when(toolMetaManagementService.upsertTool(
                eq("enterprise-default"),
                eq("prod"),
                eq("oa-core"),
                any(String.class),
                any(ToolMetaUpsertCommand.class)))
                .thenAnswer(invocation -> {
                    String toolCode = invocation.getArgument(3, String.class);
                    ToolMetaUpsertCommand command = invocation.getArgument(4, ToolMetaUpsertCommand.class);
                    return Optional.of(new ResolvedToolMetaManagementView(
                            31L,
                            "enterprise-default",
                            "prod",
                            "oa-core",
                            toolCode,
                            command.toolName(),
                            command.description(),
                            "gougu_oa",
                            command.toolType(),
                            command.visibility(),
                            command.invocationPolicy(),
                            command.executionMode(),
                            command.apiEndpoint(),
                            command.httpMethod(),
                            command.contentType(),
                            command.parameterSchema(),
                            command.executionPlan(),
                            command.interactionPolicy(),
                            command.riskLevel(),
                            Boolean.TRUE.equals(command.requiresAuth()),
                            Boolean.TRUE.equals(command.requiresConfirm()),
                            command.capabilityType(),
                            1,
                            command.status()));
                });

        Optional<ConnectorOpenApiImportResult> result = service.importOpenApi(
                "enterprise-default",
                "prod",
                "oa-core",
                new ConnectorOpenApiImportCommand(
                        """
                                {
                                  "openapi": "3.0.3",
                                  "info": {
                                    "title": "OA API",
                                    "version": "1.0.0"
                                  },
                                  "servers": [
                                    {
                                      "url": "http://oa.internal"
                                    }
                                  ],
                                  "paths": {
                                    "/users/{userId}": {
                                      "get": {
                                        "operationId": "getUser",
                                        "summary": "查询用户",
                                        "parameters": [
                                          {
                                            "name": "userId",
                                            "in": "path",
                                            "required": true,
                                            "schema": {
                                              "type": "string"
                                            }
                                          },
                                          {
                                            "name": "keyword",
                                            "in": "query",
                                            "schema": {
                                              "type": "string"
                                            }
                                          },
                                          {
                                            "name": "X-Trace-Id",
                                            "in": "header",
                                            "schema": {
                                              "type": "string"
                                            }
                                          }
                                        ],
                                        "responses": {
                                          "200": {
                                            "description": "OK"
                                          }
                                        }
                                      }
                                    },
                                    "/leaves": {
                                      "post": {
                                        "operationId": "createLeave",
                                        "summary": "创建请假",
                                        "requestBody": {
                                          "required": true,
                                          "content": {
                                            "application/json": {
                                              "schema": {
                                                "type": "object",
                                                "required": [
                                                  "reason"
                                                ],
                                                "properties": {
                                                  "reason": {
                                                    "type": "string"
                                                  },
                                                  "days": {
                                                    "type": "integer"
                                                  }
                                                }
                                              }
                                            }
                                          }
                                        },
                                        "responses": {
                                          "200": {
                                            "description": "OK"
                                          }
                                        }
                                      }
                                    }
                                  }
                                }
                                """,
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
                        null));

        assertTrue(result.isPresent());
        assertEquals(2, result.get().importedCount());
        assertEquals("http://oa.internal", result.get().resolvedBaseUrl());
        assertFalse(result.get().warnings().isEmpty());
        assertTrue(result.get().warnings().stream().anyMatch(message -> message.contains("connector_base_url")));

        ArgumentCaptor<ConnectorUpsertCommand> connectorCommandCaptor = ArgumentCaptor.forClass(ConnectorUpsertCommand.class);
        verify(connectorManagementService).upsertConnector(
                eq("enterprise-default"),
                eq("prod"),
                eq("oa-core"),
                connectorCommandCaptor.capture());
        assertEquals("http://oa.internal", connectorCommandCaptor.getValue().baseUrl());

        ArgumentCaptor<String> toolCodeCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<ToolMetaUpsertCommand> toolCommandCaptor = ArgumentCaptor.forClass(ToolMetaUpsertCommand.class);
        verify(toolMetaManagementService, times(2)).upsertTool(
                eq("enterprise-default"),
                eq("prod"),
                eq("oa-core"),
                toolCodeCaptor.capture(),
                toolCommandCaptor.capture());

        List<String> toolCodes = toolCodeCaptor.getAllValues();
        assertEquals(List.of("gougu_oa.get_user", "gougu_oa.create_leave"), toolCodes);

        ToolMetaUpsertCommand queryTool = toolCommandCaptor.getAllValues().get(0);
        assertEquals("QUERY", queryTool.toolType());
        assertEquals("PLANNER", queryTool.visibility());
        assertEquals("COMPOSABLE", queryTool.invocationPolicy());
        assertEquals("READ", queryTool.capabilityType());
        assertEquals("/users/{userId}", queryTool.apiEndpoint());
        assertEquals("GET", queryTool.httpMethod());
        assertEquals("application/json", queryTool.contentType());

        Map<String, Object> querySchema = queryTool.parameterSchema();
        assertNotNull(querySchema);
        Map<String, Object> queryProperties = mapAt(querySchema, "properties");
        assertTrue(queryProperties.containsKey("path"));
        assertTrue(queryProperties.containsKey("query"));
        assertTrue(queryProperties.containsKey("headers"));
        assertEquals(List.of("path"), querySchema.get("required"));

        Map<String, Object> queryExecutionPlan = queryTool.executionPlan();
        Map<String, Object> querySteps = mapAt(queryExecutionPlan, "steps");
        Map<String, Object> invokeStep = mapAt(querySteps, "invoke");
        Map<String, Object> queryConfig = mapAt(invokeStep, "config");
        assertEquals(Map.of(
                "path", "${path}",
                "query", "${query}",
                "headers", "${headers}"), queryConfig.get("inputMapping"));
        assertEquals(Map.of("response", "$"), queryConfig.get("outputMapping"));

        ToolMetaUpsertCommand actionTool = toolCommandCaptor.getAllValues().get(1);
        assertEquals("ACTION", actionTool.toolType());
        assertEquals("USER", actionTool.visibility());
        assertEquals("DIRECT", actionTool.invocationPolicy());
        assertEquals("ACTION", actionTool.capabilityType());

        Map<String, Object> actionSchema = actionTool.parameterSchema();
        Map<String, Object> actionProperties = mapAt(actionSchema, "properties");
        assertTrue(actionProperties.containsKey("body"));
        assertEquals(List.of("body"), actionSchema.get("required"));

        Map<String, Object> actionExecutionPlan = actionTool.executionPlan();
        Map<String, Object> actionSteps = mapAt(actionExecutionPlan, "steps");
        Map<String, Object> actionInvokeStep = mapAt(actionSteps, "invoke");
        Map<String, Object> actionConfig = mapAt(actionInvokeStep, "config");
        assertEquals(Map.of("body", "${body}"), actionConfig.get("inputMapping"));
        assertEquals(Map.of("response", "$"), actionConfig.get("outputMapping"));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> mapAt(Map<String, Object> source, String key) {
        Object value = source.get(key);
        return objectMapper.convertValue(value, new TypeReference<Map<String, Object>>() {
        });
    }
}
