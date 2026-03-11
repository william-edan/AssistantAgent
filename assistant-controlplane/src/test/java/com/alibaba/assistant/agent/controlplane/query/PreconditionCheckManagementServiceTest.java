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
package com.alibaba.assistant.agent.controlplane.query;

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

class PreconditionCheckManagementServiceTest {

    @Test
    void shouldListEnabledChecksUnderConnector() {
        PlatformSpaceService platformSpaceService = mock(PlatformSpaceService.class);
        ConnectorService connectorService = mock(ConnectorService.class);
        PreconditionCheckService preconditionCheckService = mock(PreconditionCheckService.class);
        PreconditionCheckManagementService service = new PreconditionCheckManagementService(
                platformSpaceService,
                connectorService,
                preconditionCheckService,
                new ObjectMapper());

        PlatformSpace space = new PlatformSpace();
        space.setId(10L);
        space.setSpaceCode("enterprise-default");
        space.setEnvironment("prod");
        Connector connector = connector(21L, 10L, "oa-core", "prod");
        PreconditionCheck check = check(31L, 10L, 21L, "leave.window.open", "enabled");
        check.setOperationBindingJson("{\"method\":\"GET\",\"endpoint\":\"/leave/window\"}");
        check.setAllowedAuthProfilesJson("[\"oa-user\",\"oa-service\"]");
        check.setBindingStrategiesJson("[\"user_mapped\"]");
        check.setInputSchemaJson("{\"type\":\"object\"}");
        check.setCheckExpressionJson("{\"op\":\"eq\",\"left\":\"$.open\",\"right\":true}");
        check.setFailurePolicyJson("{\"mode\":\"block\"}");

        when(platformSpaceService.findActiveByCode("enterprise-default", "prod")).thenReturn(Optional.of(space));
        when(connectorService.findLatestActiveByCodeAndEnvironment(10L, "prod", "oa-core")).thenReturn(Optional.of(connector));
        when(preconditionCheckService.listEnabledByConnector(21L)).thenReturn(List.of(check));

        List<ResolvedPreconditionCheckManagementView> result = service.listChecks(
                "enterprise-default", "prod", "oa-core");

        assertEquals(1, result.size());
        assertEquals("leave.window.open", result.get(0).checkCode());
        assertEquals("GET", result.get(0).operationBinding().get("method"));
        assertEquals(List.of("oa-user", "oa-service"), result.get(0).allowedAuthProfiles());
        assertEquals("block", result.get(0).failurePolicy().get("mode"));
    }

    @Test
    void shouldCreateCheckWhenCodeDoesNotExist() {
        PlatformSpaceService platformSpaceService = mock(PlatformSpaceService.class);
        ConnectorService connectorService = mock(ConnectorService.class);
        PreconditionCheckService preconditionCheckService = mock(PreconditionCheckService.class);
        PreconditionCheckManagementService service = new PreconditionCheckManagementService(
                platformSpaceService,
                connectorService,
                preconditionCheckService,
                new ObjectMapper());

        PlatformSpace space = new PlatformSpace();
        space.setId(11L);
        space.setSpaceCode("enterprise-default");
        space.setEnvironment("prod");
        Connector connector = connector(22L, 11L, "oa-core", "prod");
        when(platformSpaceService.findActiveByCode("enterprise-default", "prod")).thenReturn(Optional.of(space));
        when(connectorService.findLatestActiveByCodeAndEnvironment(11L, "prod", "oa-core")).thenReturn(Optional.of(connector));
        when(preconditionCheckService.findLatestEnabledByCode(11L, "leave.window.open")).thenReturn(Optional.empty());
        when(preconditionCheckService.save(any(PreconditionCheck.class))).thenReturn(true);

        Optional<ResolvedPreconditionCheckManagementView> result = service.upsertCheck(
                "enterprise-default",
                "prod",
                "oa-core",
                "leave.window.open",
                new PreconditionCheckUpsertCommand(
                        Map.of("method", "GET", "endpoint", "/leave/window"),
                        List.of("oa-user"),
                        List.of("user_mapped"),
                        Map.of("type", "object"),
                        Map.of("op", "eq", "left", "$.open", "right", true),
                        Map.of("mode", "block"),
                        "enabled"));

        assertTrue(result.isPresent());
        assertEquals("leave.window.open", result.get().checkCode());
        assertEquals("GET", result.get().operationBinding().get("method"));
        assertEquals(List.of("oa-user"), result.get().allowedAuthProfiles());
    }

    @Test
    void shouldUpdateCheckWhenCodeExists() {
        PlatformSpaceService platformSpaceService = mock(PlatformSpaceService.class);
        ConnectorService connectorService = mock(ConnectorService.class);
        PreconditionCheckService preconditionCheckService = mock(PreconditionCheckService.class);
        PreconditionCheckManagementService service = new PreconditionCheckManagementService(
                platformSpaceService,
                connectorService,
                preconditionCheckService,
                new ObjectMapper());

        PlatformSpace space = new PlatformSpace();
        space.setId(12L);
        space.setSpaceCode("enterprise-default");
        space.setEnvironment("test");
        Connector connector = connector(23L, 12L, "oa-core", "test");
        PreconditionCheck existing = check(41L, 12L, 23L, "leave.window.open", "enabled");
        when(platformSpaceService.findActiveByCode("enterprise-default", "test")).thenReturn(Optional.of(space));
        when(connectorService.findLatestActiveByCodeAndEnvironment(12L, "test", "oa-core")).thenReturn(Optional.of(connector));
        when(preconditionCheckService.findLatestEnabledByCode(12L, "leave.window.open")).thenReturn(Optional.of(existing));
        when(preconditionCheckService.updateById(any(PreconditionCheck.class))).thenReturn(true);

        Optional<ResolvedPreconditionCheckManagementView> result = service.upsertCheck(
                "enterprise-default",
                "test",
                "oa-core",
                "leave.window.open",
                new PreconditionCheckUpsertCommand(
                        Map.of("method", "GET", "endpoint", "/leave/window"),
                        List.of("oa-user", "oa-service"),
                        List.of("service_account"),
                        Map.of("type", "object"),
                        Map.of("op", "gte", "left", "$.remainingDays", "right", 1),
                        Map.of("mode", "warn"),
                        "enabled"));

        assertTrue(result.isPresent());
        assertEquals("warn", result.get().failurePolicy().get("mode"));
        assertEquals("gte", result.get().checkExpression().get("op"));
    }

    private Connector connector(Long id, Long spaceId, String connectorCode, String environment) {
        Connector connector = new Connector();
        connector.setId(id);
        connector.setSpaceId(spaceId);
        connector.setConnectorCode(connectorCode);
        connector.setEnvironment(environment);
        connector.setStatus("active");
        return connector;
    }

    private PreconditionCheck check(Long id, Long spaceId, Long connectorId, String checkCode, String status) {
        PreconditionCheck check = new PreconditionCheck();
        check.setId(id);
        check.setSpaceId(spaceId);
        check.setConnectorId(connectorId);
        check.setCheckCode(checkCode);
        check.setStatus(status);
        return check;
    }
}
