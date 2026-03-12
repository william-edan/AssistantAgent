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
package com.alibaba.assistant.agent.controlplane.action;

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

class ActionSpecManagementServiceTest {

    @Test
    void shouldFilterActionsByKeywordUnderConnector() {
        PlatformSpaceService platformSpaceService = mock(PlatformSpaceService.class);
        ConnectorService connectorService = mock(ConnectorService.class);
        ActionSpecService actionSpecService = mock(ActionSpecService.class);
        ActionSpecManagementService service = new ActionSpecManagementService(
                platformSpaceService,
                connectorService,
                actionSpecService,
                new ObjectMapper());

        PlatformSpace space = new PlatformSpace();
        space.setId(10L);
        space.setSpaceCode("enterprise-default");
        space.setEnvironment("prod");
        Connector connector = connector(21L, 10L, "oa-core", "prod");
        ActionSpec leaveAction = actionSpec(31L, 10L, 21L, "oa.leave.create", "medium", "write", "enabled");
        leaveAction.setOperationBindingJson("{\"method\":\"POST\",\"endpoint\":\"/leave/create\"}");
        leaveAction.setAllowedAuthProfilesJson("[\"oa-user\",\"oa-service\"]");
        leaveAction.setBindingStrategiesJson("[\"user_mapped\"]");
        leaveAction.setInputSchemaJson("{\"type\":\"object\"}");
        leaveAction.setOutputSchemaJson("{\"type\":\"object\"}");
        leaveAction.setIdempotencyPolicyJson("{\"mode\":\"client_token\"}");
        leaveAction.setObservabilityProfileJson("{\"level\":\"detailed\"}");
        leaveAction.setDefaultAuthProfileCode("oa-user");
        leaveAction.setApprovalPolicyId(5L);
        ActionSpec expenseAction = actionSpec(32L, 10L, 21L, "expense.create", "low", "write", "enabled");
        expenseAction.setOperationBindingJson("{\"method\":\"POST\",\"endpoint\":\"/expense/create\"}");
        expenseAction.setAllowedAuthProfilesJson("[\"oa-user\"]");
        expenseAction.setBindingStrategiesJson("[\"user_mapped\"]");
        expenseAction.setInputSchemaJson("{\"type\":\"object\"}");
        expenseAction.setOutputSchemaJson("{\"type\":\"object\"}");
        expenseAction.setIdempotencyPolicyJson("{\"mode\":\"none\"}");
        expenseAction.setObservabilityProfileJson("{\"level\":\"basic\"}");
        expenseAction.setDefaultAuthProfileCode("oa-user");
        expenseAction.setApprovalPolicyId(6L);

        when(platformSpaceService.findActiveByCode("enterprise-default", "prod")).thenReturn(Optional.of(space));
        when(connectorService.findLatestActiveByCodeAndEnvironment(10L, "prod", "oa-core")).thenReturn(Optional.of(connector));
        when(actionSpecService.listEnabledByConnector(21L)).thenReturn(List.of(leaveAction, expenseAction));

        List<ResolvedActionSpecManagementView> result = service.listActions("enterprise-default", "prod", "oa-core", "leave");

        assertEquals(1, result.size());
        assertEquals("oa.leave.create", result.get(0).actionCode());
        assertEquals("POST", result.get(0).operationBinding().get("method"));
        assertEquals("oa-user", result.get(0).defaultAuthProfileCode());
        assertEquals("client_token", ((Map<?, ?>) result.get(0).idempotencyPolicy()).get("mode"));
    }

    @Test
    void shouldGetActionByCodeUnderConnector() {
        PlatformSpaceService platformSpaceService = mock(PlatformSpaceService.class);
        ConnectorService connectorService = mock(ConnectorService.class);
        ActionSpecService actionSpecService = mock(ActionSpecService.class);
        ActionSpecManagementService service = new ActionSpecManagementService(
                platformSpaceService,
                connectorService,
                actionSpecService,
                new ObjectMapper());

        PlatformSpace space = new PlatformSpace();
        space.setId(10L);
        space.setSpaceCode("enterprise-default");
        space.setEnvironment("prod");
        Connector connector = connector(21L, 10L, "oa-core", "prod");
        ActionSpec action = actionSpec(31L, 10L, 21L, "oa.leave.create", "medium", "write", "enabled");
        action.setOperationBindingJson("{\"method\":\"POST\",\"endpoint\":\"/leave/create\"}");
        action.setAllowedAuthProfilesJson("[\"oa-user\",\"oa-service\"]");
        action.setBindingStrategiesJson("[\"user_mapped\"]");
        action.setInputSchemaJson("{\"type\":\"object\"}");
        action.setOutputSchemaJson("{\"type\":\"object\"}");
        action.setIdempotencyPolicyJson("{\"mode\":\"client_token\"}");
        action.setObservabilityProfileJson("{\"level\":\"detailed\"}");
        action.setDefaultAuthProfileCode("oa-user");
        action.setApprovalPolicyId(5L);

        when(platformSpaceService.findActiveByCode("enterprise-default", "prod")).thenReturn(Optional.of(space));
        when(connectorService.findLatestActiveByCodeAndEnvironment(10L, "prod", "oa-core")).thenReturn(Optional.of(connector));
        when(actionSpecService.listEnabledByConnector(21L)).thenReturn(List.of(action));

        Optional<ResolvedActionSpecManagementView> result = service.getAction("enterprise-default", "prod", "oa-core", "oa.leave.create");

        assertTrue(result.isPresent());
        assertEquals("oa.leave.create", result.get().actionCode());
        assertEquals("POST", result.get().operationBinding().get("method"));
    }
    @Test
    void shouldCreateActionWhenCodeDoesNotExist() {
        PlatformSpaceService platformSpaceService = mock(PlatformSpaceService.class);
        ConnectorService connectorService = mock(ConnectorService.class);
        ActionSpecService actionSpecService = mock(ActionSpecService.class);
        ActionSpecManagementService service = new ActionSpecManagementService(
                platformSpaceService,
                connectorService,
                actionSpecService,
                new ObjectMapper());

        PlatformSpace space = new PlatformSpace();
        space.setId(11L);
        space.setSpaceCode("enterprise-default");
        space.setEnvironment("prod");
        Connector connector = connector(22L, 11L, "oa-core", "prod");
        when(platformSpaceService.findActiveByCode("enterprise-default", "prod")).thenReturn(Optional.of(space));
        when(connectorService.findLatestActiveByCodeAndEnvironment(11L, "prod", "oa-core")).thenReturn(Optional.of(connector));
        when(actionSpecService.findLatestEnabledByCode(11L, "oa.leave.create")).thenReturn(Optional.empty());
        when(actionSpecService.save(any(ActionSpec.class))).thenReturn(true);

        Optional<ResolvedActionSpecManagementView> result = service.upsertAction(
                "enterprise-default",
                "prod",
                "oa-core",
                "oa.leave.create",
                new ActionSpecUpsertCommand(
                        Map.of("method", "POST", "endpoint", "/leave/create"),
                        List.of("oa-user"),
                        "oa-user",
                        List.of("user_mapped"),
                        Map.of("type", "object"),
                        Map.of("type", "object"),
                        Map.of("mode", "client_token"),
                        "medium",
                        5L,
                        "write",
                        Map.of("level", "detailed"),
                        "enabled"));

        assertTrue(result.isPresent());
        assertEquals("oa.leave.create", result.get().actionCode());
        assertEquals("POST", result.get().operationBinding().get("method"));
        assertEquals(List.of("oa-user"), result.get().allowedAuthProfiles());
    }

    @Test
    void shouldUpdateActionWhenCodeExists() {
        PlatformSpaceService platformSpaceService = mock(PlatformSpaceService.class);
        ConnectorService connectorService = mock(ConnectorService.class);
        ActionSpecService actionSpecService = mock(ActionSpecService.class);
        ActionSpecManagementService service = new ActionSpecManagementService(
                platformSpaceService,
                connectorService,
                actionSpecService,
                new ObjectMapper());

        PlatformSpace space = new PlatformSpace();
        space.setId(12L);
        space.setSpaceCode("enterprise-default");
        space.setEnvironment("test");
        Connector connector = connector(23L, 12L, "oa-core", "test");
        ActionSpec existing = actionSpec(41L, 12L, 23L, "oa.leave.create", "low", "read", "enabled");
        when(platformSpaceService.findActiveByCode("enterprise-default", "test")).thenReturn(Optional.of(space));
        when(connectorService.findLatestActiveByCodeAndEnvironment(12L, "test", "oa-core")).thenReturn(Optional.of(connector));
        when(actionSpecService.findLatestEnabledByCode(12L, "oa.leave.create")).thenReturn(Optional.of(existing));
        when(actionSpecService.updateById(any(ActionSpec.class))).thenReturn(true);

        Optional<ResolvedActionSpecManagementView> result = service.upsertAction(
                "enterprise-default",
                "test",
                "oa-core",
                "oa.leave.create",
                new ActionSpecUpsertCommand(
                        Map.of("method", "POST", "endpoint", "/leave/create"),
                        List.of("oa-user", "oa-service"),
                        "oa-service",
                        List.of("service_account"),
                        Map.of("type", "object"),
                        Map.of("type", "object"),
                        Map.of("mode", "dedupe"),
                        "high",
                        8L,
                        "write",
                        Map.of("level", "basic"),
                        "enabled"));

        assertTrue(result.isPresent());
        assertEquals("high", result.get().riskLevel());
        assertEquals("write", result.get().sideEffectLevel());
        assertEquals("oa-service", result.get().defaultAuthProfileCode());
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

    private ActionSpec actionSpec(
            Long id,
            Long spaceId,
            Long connectorId,
            String actionCode,
            String riskLevel,
            String sideEffectLevel,
            String status) {
        ActionSpec action = new ActionSpec();
        action.setId(id);
        action.setSpaceId(spaceId);
        action.setConnectorId(connectorId);
        action.setActionCode(actionCode);
        action.setRiskLevel(riskLevel);
        action.setSideEffectLevel(sideEffectLevel);
        action.setStatus(status);
        return action;
    }
}


