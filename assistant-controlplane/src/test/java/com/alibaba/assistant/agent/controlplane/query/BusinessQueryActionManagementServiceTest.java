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

class BusinessQueryActionManagementServiceTest {

    @Test
    void shouldListEnabledQueryActionsUnderConnector() {
        PlatformSpaceService platformSpaceService = mock(PlatformSpaceService.class);
        ConnectorService connectorService = mock(ConnectorService.class);
        BusinessQueryActionService businessQueryActionService = mock(BusinessQueryActionService.class);
        BusinessQueryActionManagementService service = new BusinessQueryActionManagementService(
                platformSpaceService,
                connectorService,
                businessQueryActionService,
                new ObjectMapper());

        PlatformSpace space = new PlatformSpace();
        space.setId(10L);
        space.setSpaceCode("enterprise-default");
        space.setEnvironment("prod");
        Connector connector = connector(21L, 10L, "oa-core", "prod");
        BusinessQueryAction queryAction = queryAction(31L, 10L, 21L, "leave.balance", "low", "enabled");
        queryAction.setOperationBindingJson("{\"method\":\"GET\",\"endpoint\":\"/leave/balance\"}");
        queryAction.setAllowedAuthProfilesJson("[\"oa-user\",\"oa-service\"]");
        queryAction.setBindingStrategiesJson("[\"user_mapped\"]");
        queryAction.setInputSchemaJson("{\"type\":\"object\"}");
        queryAction.setOutputSchemaJson("{\"type\":\"object\"}");
        queryAction.setResultVisibilityPolicyJson("{\"scope\":\"requester\"}");

        when(platformSpaceService.findActiveByCode("enterprise-default", "prod")).thenReturn(Optional.of(space));
        when(connectorService.findLatestActiveByCodeAndEnvironment(10L, "prod", "oa-core")).thenReturn(Optional.of(connector));
        when(businessQueryActionService.listEnabledByConnector(21L)).thenReturn(List.of(queryAction));

        List<ResolvedBusinessQueryActionManagementView> result = service.listQueryActions(
                "enterprise-default", "prod", "oa-core");

        assertEquals(1, result.size());
        assertEquals("leave.balance", result.get(0).queryActionCode());
        assertEquals("GET", result.get(0).operationBinding().get("method"));
        assertEquals(List.of("oa-user", "oa-service"), result.get(0).allowedAuthProfiles());
        assertEquals("requester", result.get(0).resultVisibilityPolicy().get("scope"));
    }

    @Test
    void shouldCreateQueryActionWhenCodeDoesNotExist() {
        PlatformSpaceService platformSpaceService = mock(PlatformSpaceService.class);
        ConnectorService connectorService = mock(ConnectorService.class);
        BusinessQueryActionService businessQueryActionService = mock(BusinessQueryActionService.class);
        BusinessQueryActionManagementService service = new BusinessQueryActionManagementService(
                platformSpaceService,
                connectorService,
                businessQueryActionService,
                new ObjectMapper());

        PlatformSpace space = new PlatformSpace();
        space.setId(11L);
        space.setSpaceCode("enterprise-default");
        space.setEnvironment("prod");
        Connector connector = connector(22L, 11L, "oa-core", "prod");
        when(platformSpaceService.findActiveByCode("enterprise-default", "prod")).thenReturn(Optional.of(space));
        when(connectorService.findLatestActiveByCodeAndEnvironment(11L, "prod", "oa-core")).thenReturn(Optional.of(connector));
        when(businessQueryActionService.findLatestEnabledByCode(11L, "leave.balance")).thenReturn(Optional.empty());
        when(businessQueryActionService.save(any(BusinessQueryAction.class))).thenReturn(true);

        Optional<ResolvedBusinessQueryActionManagementView> result = service.upsertQueryAction(
                "enterprise-default",
                "prod",
                "oa-core",
                "leave.balance",
                new BusinessQueryActionUpsertCommand(
                        Map.of("method", "GET", "endpoint", "/leave/balance"),
                        List.of("oa-user"),
                        List.of("user_mapped"),
                        Map.of("type", "object"),
                        Map.of("type", "object"),
                        "low",
                        Map.of("scope", "requester"),
                        "enabled"));

        assertTrue(result.isPresent());
        assertEquals("leave.balance", result.get().queryActionCode());
        assertEquals("GET", result.get().operationBinding().get("method"));
        assertEquals(List.of("oa-user"), result.get().allowedAuthProfiles());
    }

    @Test
    void shouldUpdateQueryActionWhenCodeExists() {
        PlatformSpaceService platformSpaceService = mock(PlatformSpaceService.class);
        ConnectorService connectorService = mock(ConnectorService.class);
        BusinessQueryActionService businessQueryActionService = mock(BusinessQueryActionService.class);
        BusinessQueryActionManagementService service = new BusinessQueryActionManagementService(
                platformSpaceService,
                connectorService,
                businessQueryActionService,
                new ObjectMapper());

        PlatformSpace space = new PlatformSpace();
        space.setId(12L);
        space.setSpaceCode("enterprise-default");
        space.setEnvironment("test");
        Connector connector = connector(23L, 12L, "oa-core", "test");
        BusinessQueryAction existing = queryAction(41L, 12L, 23L, "leave.balance", "low", "enabled");
        when(platformSpaceService.findActiveByCode("enterprise-default", "test")).thenReturn(Optional.of(space));
        when(connectorService.findLatestActiveByCodeAndEnvironment(12L, "test", "oa-core")).thenReturn(Optional.of(connector));
        when(businessQueryActionService.findLatestEnabledByCode(12L, "leave.balance")).thenReturn(Optional.of(existing));
        when(businessQueryActionService.updateById(any(BusinessQueryAction.class))).thenReturn(true);

        Optional<ResolvedBusinessQueryActionManagementView> result = service.upsertQueryAction(
                "enterprise-default",
                "test",
                "oa-core",
                "leave.balance",
                new BusinessQueryActionUpsertCommand(
                        Map.of("method", "GET", "endpoint", "/leave/balance"),
                        List.of("oa-user", "oa-service"),
                        List.of("service_account"),
                        Map.of("type", "object"),
                        Map.of("type", "object"),
                        "medium",
                        Map.of("scope", "tenant"),
                        "enabled"));

        assertTrue(result.isPresent());
        assertEquals("medium", result.get().riskLevel());
        assertEquals("tenant", result.get().resultVisibilityPolicy().get("scope"));
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

    private BusinessQueryAction queryAction(
            Long id,
            Long spaceId,
            Long connectorId,
            String queryActionCode,
            String riskLevel,
            String status) {
        BusinessQueryAction queryAction = new BusinessQueryAction();
        queryAction.setId(id);
        queryAction.setSpaceId(spaceId);
        queryAction.setConnectorId(connectorId);
        queryAction.setQueryActionCode(queryActionCode);
        queryAction.setRiskLevel(riskLevel);
        queryAction.setStatus(status);
        return queryAction;
    }
}
