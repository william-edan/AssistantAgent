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
package com.alibaba.assistant.agent.controlplane.toolregistry;

import com.alibaba.assistant.agent.controlplane.connector.Connector;
import com.alibaba.assistant.agent.controlplane.connector.ConnectorService;
import com.alibaba.assistant.agent.controlplane.space.PlatformSpace;
import com.alibaba.assistant.agent.controlplane.space.PlatformSpaceService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ToolMetaManagementServiceTest {

    @Test
    void shouldFilterToolsByKeywordUnderConnector() {
        PlatformSpaceService platformSpaceService = mock(PlatformSpaceService.class);
        ConnectorService connectorService = mock(ConnectorService.class);
        ToolMetaService toolMetaService = mock(ToolMetaService.class);
        ToolMetaManagementService service = new ToolMetaManagementService(
                platformSpaceService,
                connectorService,
                toolMetaService,
                new ToolMetaControlPlaneMapper(new ObjectMapper()));

        PlatformSpace space = new PlatformSpace();
        space.setId(10L);
        space.setSpaceCode("enterprise-default");
        space.setEnvironment("prod");
        Connector connector = connector(21L, 10L, "oa-core", "gougu_oa", "prod");
        ToolMeta leaveTool = tool("gougu_oa.leave_application", "请假审批", "gougu_oa", "/home/leaves/add");
        ToolMeta meetingTool = tool("gougu_oa.meeting_room_booking", "会议室预定", "gougu_oa", "/adm/meeting/add");

        when(platformSpaceService.findActiveByCode("enterprise-default", "prod")).thenReturn(Optional.of(space));
        when(connectorService.findLatestActiveByCodeAndEnvironment(10L, "prod", "oa-core")).thenReturn(Optional.of(connector));
        when(toolMetaService.listEnabledByTenantAndSystem("default", "gougu_oa")).thenReturn(List.of(leaveTool, meetingTool));

        List<ResolvedToolMetaManagementView> result = service.listTools("enterprise-default", "prod", "oa-core", "请假");

        assertEquals(1, result.size());
        assertEquals("gougu_oa.leave_application", result.get(0).toolCode());
        assertEquals("ACTION", result.get(0).toolType());
        assertEquals("USER", result.get(0).visibility());
        assertEquals("DIRECT", result.get(0).invocationPolicy());
    }

    @Test
    void shouldGetToolByCodeUnderConnector() {
        PlatformSpaceService platformSpaceService = mock(PlatformSpaceService.class);
        ConnectorService connectorService = mock(ConnectorService.class);
        ToolMetaService toolMetaService = mock(ToolMetaService.class);
        ToolMetaManagementService service = new ToolMetaManagementService(
                platformSpaceService,
                connectorService,
                toolMetaService,
                new ToolMetaControlPlaneMapper(new ObjectMapper()));

        PlatformSpace space = new PlatformSpace();
        space.setId(10L);
        space.setSpaceCode("enterprise-default");
        space.setEnvironment("prod");
        Connector connector = connector(21L, 10L, "oa-core", "gougu_oa", "prod");
        ToolMeta tool = tool("gougu_oa.leave_application", "请假审批", "gougu_oa", "/home/leaves/add");

        when(platformSpaceService.findActiveByCode("enterprise-default", "prod")).thenReturn(Optional.of(space));
        when(connectorService.findLatestActiveByCodeAndEnvironment(10L, "prod", "oa-core")).thenReturn(Optional.of(connector));
        when(toolMetaService.findLatestEnabledByToolCode("default", "gougu_oa.leave_application")).thenReturn(Optional.of(tool));

        Optional<ResolvedToolMetaManagementView> result =
                service.getTool("enterprise-default", "prod", "oa-core", "gougu_oa.leave_application");

        assertTrue(result.isPresent());
        assertEquals("gougu_oa.leave_application", result.get().toolCode());
        assertEquals("gougu_oa", result.get().systemCode());
        assertEquals("SYNC", result.get().executionMode());
    }

    @Test
    void shouldBuildDefaultQueryExecutionPlanWhenMissing() {
        PlatformSpaceService platformSpaceService = mock(PlatformSpaceService.class);
        ConnectorService connectorService = mock(ConnectorService.class);
        ToolMetaService toolMetaService = mock(ToolMetaService.class);
        ToolMetaManagementService service = new ToolMetaManagementService(
                platformSpaceService,
                connectorService,
                toolMetaService,
                new ToolMetaControlPlaneMapper(new ObjectMapper()));

        PlatformSpace space = new PlatformSpace();
        space.setId(11L);
        space.setSpaceCode("enterprise-default");
        space.setEnvironment("prod");
        Connector connector = connector(22L, 11L, "oa-core", "gougu_oa", "prod");
        when(platformSpaceService.findActiveByCode("enterprise-default", "prod")).thenReturn(Optional.of(space));
        when(connectorService.findLatestActiveByCodeAndEnvironment(11L, "prod", "oa-core")).thenReturn(Optional.of(connector));
        when(toolMetaService.findLatestEnabledByToolCode("default", "gougu_oa.user_directory")).thenReturn(Optional.empty());
        when(toolMetaService.save(any(ToolMeta.class))).thenReturn(true);

        Optional<ResolvedToolMetaManagementView> result = service.upsertTool(
                "enterprise-default",
                "prod",
                "oa-core",
                "gougu_oa.user_directory",
                new ToolMetaUpsertCommand(
                        "员工目录",
                        "查询员工目录",
                        "QUERY",
                        null,
                        null,
                        null,
                        Map.of("type", "object", "properties", Map.of()),
                        null,
                        Map.of("toolType", "QUERY"),
                        "/api/oa_integration/get_all_users",
                        "GET",
                        "application/json",
                        "low",
                        true,
                        false,
                        "READ",
                        1,
                        "enabled"));

        assertTrue(result.isPresent());
        assertEquals("QUERY", result.get().toolType());
        assertEquals("PLANNER", result.get().visibility());
        assertEquals("COMPOSABLE", result.get().invocationPolicy());
        assertEquals("SYNC", result.get().executionMode());
        assertEquals("GET", result.get().httpMethod());
        assertEquals("application/json", result.get().contentType());
        assertEquals("invoke", ((List<?>) result.get().executionPlan().get("entry")).get(0));
    }

    @Test
    void shouldForceDependencyOnlyToolsToInternalVisibility() {
        PlatformSpaceService platformSpaceService = mock(PlatformSpaceService.class);
        ConnectorService connectorService = mock(ConnectorService.class);
        ToolMetaService toolMetaService = mock(ToolMetaService.class);
        ToolMetaManagementService service = new ToolMetaManagementService(
                platformSpaceService,
                connectorService,
                toolMetaService,
                new ToolMetaControlPlaneMapper(new ObjectMapper()));

        PlatformSpace space = new PlatformSpace();
        space.setId(11L);
        space.setSpaceCode("enterprise-default");
        space.setEnvironment("prod");
        Connector connector = connector(22L, 11L, "oa-core", "gougu_oa", "prod");
        when(platformSpaceService.findActiveByCode("enterprise-default", "prod")).thenReturn(Optional.of(space));
        when(connectorService.findLatestActiveByCodeAndEnvironment(11L, "prod", "oa-core")).thenReturn(Optional.of(connector));
        when(toolMetaService.findLatestEnabledByToolCode("default", "gougu_oa.manager_resolver")).thenReturn(Optional.empty());
        when(toolMetaService.save(any(ToolMeta.class))).thenReturn(true);

        Optional<ResolvedToolMetaManagementView> result = service.upsertTool(
                "enterprise-default",
                "prod",
                "oa-core",
                "gougu_oa.manager_resolver",
                new ToolMetaUpsertCommand(
                        "直属领导解析器",
                        "内部审批人解析",
                        "QUERY",
                        "USER",
                        "DEPENDENCY_ONLY",
                        null,
                        Map.of("type", "object", "properties", Map.of()),
                        null,
                        Map.of(),
                        "/api/oa_integration/get_department_users_tree",
                        "GET",
                        "application/json",
                        "low",
                        true,
                        false,
                        "READ",
                        1,
                        "enabled"));

        assertTrue(result.isPresent());
        assertEquals("INTERNAL", result.get().visibility());
        assertEquals("DEPENDENCY_ONLY", result.get().invocationPolicy());
    }

    private Connector connector(Long id, Long spaceId, String connectorCode, String systemCode, String environment) {
        Connector connector = new Connector();
        connector.setId(id);
        connector.setSpaceId(spaceId);
        connector.setConnectorCode(connectorCode);
        connector.setSystemCode(systemCode);
        connector.setEnvironment(environment);
        connector.setStatus("active");
        return connector;
    }

    private ToolMeta tool(String toolCode, String toolName, String systemCode, String endpoint) {
        ToolMeta toolMeta = new ToolMeta();
        toolMeta.setId(1L);
        toolMeta.setTenantId("default");
        toolMeta.setToolCode(toolCode);
        toolMeta.setToolName(toolName);
        toolMeta.setSystemCode(systemCode);
        toolMeta.setApiEndpoint(endpoint);
        toolMeta.setHttpMethod("POST");
        toolMeta.setContentType("application/json");
        toolMeta.setParameterSchema("{\"type\":\"object\",\"properties\":{}}");
        toolMeta.setExecutionPlan("{\"version\":\"2.0\",\"entry\":[\"invoke\"],\"terminal\":[\"invoke\"],\"steps\":{\"invoke\":{\"stepId\":\"invoke\",\"type\":\"HTTP\",\"config\":{\"method\":\"POST\",\"endpoint\":\"" + endpoint + "\"}}}}");
        toolMeta.setInteractionPolicy("{\"toolType\":\"ACTION\",\"visibility\":\"USER\",\"invocationPolicy\":\"DIRECT\",\"executionMode\":\"SYNC\"}");
        toolMeta.setRiskLevel("LOW");
        toolMeta.setRequiresAuth(true);
        toolMeta.setRequiresConfirm(false);
        toolMeta.setCapabilityType("ACTION");
        toolMeta.setVersion(1);
        toolMeta.setStatus("enabled");
        return toolMeta;
    }
}
