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

class ReferenceResolverManagementServiceTest {

    @Test
    void shouldListEnabledResolversUnderConnector() {
        PlatformSpaceService platformSpaceService = mock(PlatformSpaceService.class);
        ConnectorService connectorService = mock(ConnectorService.class);
        ReferenceResolverService referenceResolverService = mock(ReferenceResolverService.class);
        ReferenceResolverManagementService service = new ReferenceResolverManagementService(
                platformSpaceService,
                connectorService,
                referenceResolverService,
                new ObjectMapper());

        PlatformSpace space = new PlatformSpace();
        space.setId(10L);
        space.setSpaceCode("enterprise-default");
        space.setEnvironment("prod");
        Connector connector = connector(21L, 10L, "oa-core", "prod");
        ReferenceResolver resolver = resolver(31L, 10L, 21L, "leave.types", "internal", "enabled");
        resolver.setOperationBindingJson("{\"method\":\"GET\",\"endpoint\":\"/leave/types\"}");
        resolver.setAllowedAuthProfilesJson("[\"oa-user\",\"oa-service\"]");
        resolver.setInputSchemaJson("{\"type\":\"object\"}");
        resolver.setOutputSchemaJson("{\"type\":\"array\"}");
        resolver.setCachePolicyJson("{\"ttlSeconds\":300}");
        resolver.setStalenessPolicyJson("{\"mode\":\"allow_stale\"}");

        when(platformSpaceService.findActiveByCode("enterprise-default", "prod")).thenReturn(Optional.of(space));
        when(connectorService.findLatestActiveByCodeAndEnvironment(10L, "prod", "oa-core")).thenReturn(Optional.of(connector));
        when(referenceResolverService.listEnabledByConnector(21L)).thenReturn(List.of(resolver));

        List<ResolvedReferenceResolverManagementView> result = service.listResolvers(
                "enterprise-default", "prod", "oa-core");

        assertEquals(1, result.size());
        assertEquals("leave.types", result.get(0).resolverCode());
        assertEquals("GET", result.get(0).operationBinding().get("method"));
        assertEquals(List.of("oa-user", "oa-service"), result.get(0).allowedAuthProfiles());
        assertEquals(300, ((Number) result.get(0).cachePolicy().get("ttlSeconds")).intValue());
    }

    @Test
    void shouldCreateResolverWhenCodeDoesNotExist() {
        PlatformSpaceService platformSpaceService = mock(PlatformSpaceService.class);
        ConnectorService connectorService = mock(ConnectorService.class);
        ReferenceResolverService referenceResolverService = mock(ReferenceResolverService.class);
        ReferenceResolverManagementService service = new ReferenceResolverManagementService(
                platformSpaceService,
                connectorService,
                referenceResolverService,
                new ObjectMapper());

        PlatformSpace space = new PlatformSpace();
        space.setId(11L);
        space.setSpaceCode("enterprise-default");
        space.setEnvironment("prod");
        Connector connector = connector(22L, 11L, "oa-core", "prod");
        when(platformSpaceService.findActiveByCode("enterprise-default", "prod")).thenReturn(Optional.of(space));
        when(connectorService.findLatestActiveByCodeAndEnvironment(11L, "prod", "oa-core")).thenReturn(Optional.of(connector));
        when(referenceResolverService.findLatestEnabledByCode(11L, "leave.types")).thenReturn(Optional.empty());
        when(referenceResolverService.save(any(ReferenceResolver.class))).thenReturn(true);

        Optional<ResolvedReferenceResolverManagementView> result = service.upsertResolver(
                "enterprise-default",
                "prod",
                "oa-core",
                "leave.types",
                new ReferenceResolverUpsertCommand(
                        Map.of("method", "GET", "endpoint", "/leave/types"),
                        List.of("oa-user"),
                        Map.of("type", "object"),
                        Map.of("type", "array"),
                        Map.of("ttlSeconds", 120),
                        Map.of("mode", "allow_stale"),
                        "internal",
                        "enabled"));

        assertTrue(result.isPresent());
        assertEquals("leave.types", result.get().resolverCode());
        assertEquals("GET", result.get().operationBinding().get("method"));
        assertEquals(List.of("oa-user"), result.get().allowedAuthProfiles());
    }

    @Test
    void shouldUpdateResolverWhenCodeExists() {
        PlatformSpaceService platformSpaceService = mock(PlatformSpaceService.class);
        ConnectorService connectorService = mock(ConnectorService.class);
        ReferenceResolverService referenceResolverService = mock(ReferenceResolverService.class);
        ReferenceResolverManagementService service = new ReferenceResolverManagementService(
                platformSpaceService,
                connectorService,
                referenceResolverService,
                new ObjectMapper());

        PlatformSpace space = new PlatformSpace();
        space.setId(12L);
        space.setSpaceCode("enterprise-default");
        space.setEnvironment("test");
        Connector connector = connector(23L, 12L, "oa-core", "test");
        ReferenceResolver existing = resolver(41L, 12L, 23L, "leave.types", "internal", "enabled");
        when(platformSpaceService.findActiveByCode("enterprise-default", "test")).thenReturn(Optional.of(space));
        when(connectorService.findLatestActiveByCodeAndEnvironment(12L, "test", "oa-core")).thenReturn(Optional.of(connector));
        when(referenceResolverService.findLatestEnabledByCode(12L, "leave.types")).thenReturn(Optional.of(existing));
        when(referenceResolverService.updateById(any(ReferenceResolver.class))).thenReturn(true);

        Optional<ResolvedReferenceResolverManagementView> result = service.upsertResolver(
                "enterprise-default",
                "test",
                "oa-core",
                "leave.types",
                new ReferenceResolverUpsertCommand(
                        Map.of("method", "GET", "endpoint", "/leave/types"),
                        List.of("oa-user", "oa-service"),
                        Map.of("type", "object"),
                        Map.of("type", "array"),
                        Map.of("ttlSeconds", 30),
                        Map.of("mode", "strict"),
                        "tenant",
                        "enabled"));

        assertTrue(result.isPresent());
        assertEquals("tenant", result.get().visibility());
        assertEquals(30, ((Number) result.get().cachePolicy().get("ttlSeconds")).intValue());
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

    private ReferenceResolver resolver(
            Long id,
            Long spaceId,
            Long connectorId,
            String resolverCode,
            String visibility,
            String status) {
        ReferenceResolver resolver = new ReferenceResolver();
        resolver.setId(id);
        resolver.setSpaceId(spaceId);
        resolver.setConnectorId(connectorId);
        resolver.setResolverCode(resolverCode);
        resolver.setVisibility(visibility);
        resolver.setStatus(status);
        return resolver;
    }
}
