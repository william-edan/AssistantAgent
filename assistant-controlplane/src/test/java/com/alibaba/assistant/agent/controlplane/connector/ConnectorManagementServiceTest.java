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

import com.alibaba.assistant.agent.controlplane.space.PlatformSpace;
import com.alibaba.assistant.agent.controlplane.space.PlatformSpaceService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ConnectorManagementServiceTest {

    @Test
    void shouldFilterConnectorsByKeywordUnderSpaceEnvironment() {
        PlatformSpaceService platformSpaceService = mock(PlatformSpaceService.class);
        ConnectorService connectorService = mock(ConnectorService.class);
        ConnectorManagementService service = new ConnectorManagementService(platformSpaceService, connectorService);

        PlatformSpace space = new PlatformSpace();
        space.setId(10L);
        space.setSpaceCode("enterprise-default");
        space.setEnvironment("prod");
        when(platformSpaceService.findActiveByCode("enterprise-default", "prod")).thenReturn(Optional.of(space));
        Connector financeConnector = connector("finance-core", "finance_oa", "Finance Core", "openapi", "intranet", "http://finance.internal", "active", 3);
        Connector hrConnector = connector("hr-core", "hr_oa", "HR Core", "openapi", "dmz", "http://hr.internal", "active", 4);
        when(connectorService.listLatestActiveBySpace(10L, "prod")).thenReturn(List.of(financeConnector, hrConnector));

        List<ResolvedConnectorView> result = service.listConnectors("enterprise-default", "prod", "finance");

        assertEquals(1, result.size());
        assertEquals("finance-core", result.get(0).connectorCode());
        assertEquals("finance_oa", result.get(0).systemCode());
    }

    @Test
    void shouldGetConnectorByCodeUnderSpaceEnvironment() {
        PlatformSpaceService platformSpaceService = mock(PlatformSpaceService.class);
        ConnectorService connectorService = mock(ConnectorService.class);
        ConnectorManagementService service = new ConnectorManagementService(platformSpaceService, connectorService);

        PlatformSpace space = new PlatformSpace();
        space.setId(10L);
        space.setSpaceCode("enterprise-default");
        space.setEnvironment("prod");
        when(platformSpaceService.findActiveByCode("enterprise-default", "prod")).thenReturn(Optional.of(space));
        Connector connector = connector("oa-core", "gougu_oa", "OA Core", "openapi", "intranet", "http://oa.internal", "active", 3);
        when(connectorService.findLatestActiveByCodeAndEnvironment(10L, "prod", "oa-core")).thenReturn(Optional.of(connector));

        Optional<ResolvedConnectorView> result = service.getConnector("enterprise-default", "prod", "oa-core");

        assertTrue(result.isPresent());
        assertEquals("oa-core", result.get().connectorCode());
        assertEquals("intranet", result.get().networkZone());
    }
    @Test
    void shouldCreateConnectorWhenCodeDoesNotExist() {
        PlatformSpaceService platformSpaceService = mock(PlatformSpaceService.class);
        ConnectorService connectorService = mock(ConnectorService.class);
        ConnectorManagementService service = new ConnectorManagementService(platformSpaceService, connectorService);

        PlatformSpace space = new PlatformSpace();
        space.setId(11L);
        space.setSpaceCode("enterprise-default");
        space.setEnvironment("prod");
        when(platformSpaceService.findActiveByCode("enterprise-default", "prod")).thenReturn(Optional.of(space));
        when(connectorService.findLatestActiveByCodeAndEnvironment(11L, "prod", "oa-core")).thenReturn(Optional.empty());
        when(connectorService.save(any(Connector.class))).thenReturn(true);

        Optional<ResolvedConnectorView> result = service.upsertConnector(
                "enterprise-default",
                "prod",
                "oa-core",
                new ConnectorUpsertCommand(
                        "gougu_oa",
                        "OA Core",
                        "openapi",
                        "intranet",
                        "http://oa.internal",
                        "active"));

        assertTrue(result.isPresent());
        assertEquals("oa-core", result.get().connectorCode());
        assertEquals(1, result.get().version());
    }

    @Test
    void shouldUpdateConnectorAndBumpVersionWhenCodeExists() {
        PlatformSpaceService platformSpaceService = mock(PlatformSpaceService.class);
        ConnectorService connectorService = mock(ConnectorService.class);
        ConnectorManagementService service = new ConnectorManagementService(platformSpaceService, connectorService);

        PlatformSpace space = new PlatformSpace();
        space.setId(12L);
        space.setSpaceCode("enterprise-default");
        space.setEnvironment("prod");
        when(platformSpaceService.findActiveByCode("enterprise-default", "prod")).thenReturn(Optional.of(space));
        Connector existing = connector("oa-core", "gougu_oa", "Old Name", "openapi", "intranet", "http://old.internal", "active", 4);
        existing.setId(99L);
        existing.setSpaceId(12L);
        existing.setEnvironment("prod");
        when(connectorService.findLatestActiveByCodeAndEnvironment(12L, "prod", "oa-core")).thenReturn(Optional.of(existing));
        when(connectorService.updateById(any(Connector.class))).thenReturn(true);

        Optional<ResolvedConnectorView> result = service.upsertConnector(
                "enterprise-default",
                "prod",
                "oa-core",
                new ConnectorUpsertCommand(
                        "gougu_oa",
                        "New Name",
                        "openapi",
                        "dmz",
                        "http://new.internal",
                        "active"));

        assertTrue(result.isPresent());
        assertEquals("New Name", result.get().displayName());
        assertEquals("dmz", result.get().networkZone());
        assertEquals(5, result.get().version());
    }

    private Connector connector(
            String connectorCode,
            String systemCode,
            String displayName,
            String protocolType,
            String networkZone,
            String baseUrl,
            String status,
            int version) {
        Connector connector = new Connector();
        connector.setConnectorCode(connectorCode);
        connector.setSystemCode(systemCode);
        connector.setDisplayName(displayName);
        connector.setProtocolType(protocolType);
        connector.setNetworkZone(networkZone);
        connector.setBaseUrl(baseUrl);
        connector.setStatus(status);
        connector.setVersion(version);
        return connector;
    }
}


