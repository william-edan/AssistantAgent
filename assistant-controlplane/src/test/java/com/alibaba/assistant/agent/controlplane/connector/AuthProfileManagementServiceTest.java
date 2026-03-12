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

class AuthProfileManagementServiceTest {

    @Test
    void shouldFilterAuthProfilesByKeywordUnderConnector() {
        PlatformSpaceService platformSpaceService = mock(PlatformSpaceService.class);
        ConnectorService connectorService = mock(ConnectorService.class);
        AuthProfileService authProfileService = mock(AuthProfileService.class);
        AuthProfileManagementService service = new AuthProfileManagementService(
                platformSpaceService,
                connectorService,
                authProfileService,
                new ObjectMapper());

        PlatformSpace space = new PlatformSpace();
        space.setId(10L);
        space.setSpaceCode("enterprise-default");
        space.setEnvironment("prod");
        Connector connector = connector(21L, 10L, "oa-core", "prod");
        AuthProfile userProfile = authProfile(31L, 10L, 21L, "oa-user", "bearer", "user_mapped", "active");
        userProfile.setScopesJson("[\"read\",\"write\"]");
        userProfile.setCredentialRef("vault://oa-user");
        AuthProfile serviceProfile = authProfile(32L, 10L, 21L, "finance-service", "api_key", "service_account", "active");
        serviceProfile.setScopesJson("[\"sync\"]");
        serviceProfile.setCredentialRef("vault://finance-service");

        when(platformSpaceService.findActiveByCode("enterprise-default", "prod")).thenReturn(Optional.of(space));
        when(connectorService.findLatestActiveByCodeAndEnvironment(10L, "prod", "oa-core")).thenReturn(Optional.of(connector));
        when(authProfileService.listActiveByConnector(21L)).thenReturn(List.of(userProfile, serviceProfile));

        List<ResolvedAuthProfileManagementView> result = service.listAuthProfiles("enterprise-default", "prod", "oa-core", "service");

        assertEquals(1, result.size());
        assertEquals("finance-service", result.get(0).authProfileCode());
        assertEquals("api_key", result.get(0).authType());
        assertEquals("vault://finance-service", result.get(0).credentialRef());
    }

    @Test
    void shouldGetAuthProfileByCodeUnderConnector() {
        PlatformSpaceService platformSpaceService = mock(PlatformSpaceService.class);
        ConnectorService connectorService = mock(ConnectorService.class);
        AuthProfileService authProfileService = mock(AuthProfileService.class);
        AuthProfileManagementService service = new AuthProfileManagementService(
                platformSpaceService,
                connectorService,
                authProfileService,
                new ObjectMapper());

        PlatformSpace space = new PlatformSpace();
        space.setId(10L);
        space.setSpaceCode("enterprise-default");
        space.setEnvironment("prod");
        Connector connector = connector(21L, 10L, "oa-core", "prod");
        AuthProfile profile = authProfile(31L, 10L, 21L, "oa-user", "bearer", "user_mapped", "active");
        profile.setScopesJson("[\"read\",\"write\"]");
        profile.setCredentialRef("vault://oa-user");

        when(platformSpaceService.findActiveByCode("enterprise-default", "prod")).thenReturn(Optional.of(space));
        when(connectorService.findLatestActiveByCodeAndEnvironment(10L, "prod", "oa-core")).thenReturn(Optional.of(connector));
        when(authProfileService.findLatestActiveByCode(21L, "oa-user")).thenReturn(Optional.of(profile));

        Optional<ResolvedAuthProfileManagementView> result = service.getAuthProfile("enterprise-default", "prod", "oa-core", "oa-user");

        assertTrue(result.isPresent());
        assertEquals("oa-user", result.get().authProfileCode());
        assertEquals("vault://oa-user", result.get().credentialRef());
    }
    @Test
    void shouldCreateAuthProfileWhenCodeDoesNotExist() {
        PlatformSpaceService platformSpaceService = mock(PlatformSpaceService.class);
        ConnectorService connectorService = mock(ConnectorService.class);
        AuthProfileService authProfileService = mock(AuthProfileService.class);
        AuthProfileManagementService service = new AuthProfileManagementService(
                platformSpaceService,
                connectorService,
                authProfileService,
                new ObjectMapper());

        PlatformSpace space = new PlatformSpace();
        space.setId(11L);
        space.setSpaceCode("enterprise-default");
        space.setEnvironment("prod");
        Connector connector = connector(22L, 11L, "oa-core", "prod");
        when(platformSpaceService.findActiveByCode("enterprise-default", "prod")).thenReturn(Optional.of(space));
        when(connectorService.findLatestActiveByCodeAndEnvironment(11L, "prod", "oa-core")).thenReturn(Optional.of(connector));
        when(authProfileService.findLatestActiveByCode(22L, "oa-user")).thenReturn(Optional.empty());
        when(authProfileService.save(any(AuthProfile.class))).thenReturn(true);

        Optional<ResolvedAuthProfileManagementView> result = service.upsertAuthProfile(
                "enterprise-default",
                "prod",
                "oa-core",
                "oa-user",
                new AuthProfileUpsertCommand(
                        "bearer",
                        "user_mapped",
                        "https://idp/token",
                        "Authorization",
                        "Bearer ",
                        "oa-api",
                        List.of("read", "write"),
                        "vault://oa-user",
                        Map.of("refreshBeforeSeconds", 60),
                        "active"));

        assertTrue(result.isPresent());
        assertEquals("oa-user", result.get().authProfileCode());
        assertEquals(List.of("read", "write"), result.get().scopes());
        assertEquals("vault://oa-user", result.get().credentialRef());
    }

    @Test
    void shouldUpdateAuthProfileWhenCodeExists() {
        PlatformSpaceService platformSpaceService = mock(PlatformSpaceService.class);
        ConnectorService connectorService = mock(ConnectorService.class);
        AuthProfileService authProfileService = mock(AuthProfileService.class);
        AuthProfileManagementService service = new AuthProfileManagementService(
                platformSpaceService,
                connectorService,
                authProfileService,
                new ObjectMapper());

        PlatformSpace space = new PlatformSpace();
        space.setId(12L);
        space.setSpaceCode("enterprise-default");
        space.setEnvironment("prod");
        Connector connector = connector(23L, 12L, "oa-core", "prod");
        AuthProfile existing = authProfile(41L, 12L, 23L, "oa-user", "bearer", "user_mapped", "active");
        existing.setTokenHeaderName("Authorization");
        existing.setTokenHeaderPrefix("Bearer ");
        existing.setScopesJson("[\"read\"]");
        when(platformSpaceService.findActiveByCode("enterprise-default", "prod")).thenReturn(Optional.of(space));
        when(connectorService.findLatestActiveByCodeAndEnvironment(12L, "prod", "oa-core")).thenReturn(Optional.of(connector));
        when(authProfileService.findLatestActiveByCode(23L, "oa-user")).thenReturn(Optional.of(existing));
        when(authProfileService.updateById(any(AuthProfile.class))).thenReturn(true);

        Optional<ResolvedAuthProfileManagementView> result = service.upsertAuthProfile(
                "enterprise-default",
                "prod",
                "oa-core",
                "oa-user",
                new AuthProfileUpsertCommand(
                        "api_key",
                        "service_account",
                        null,
                        "X-API-Key",
                        "",
                        null,
                        List.of("sync"),
                        "vault://oa-service",
                        Map.of("rotate", true),
                        "active"));

        assertTrue(result.isPresent());
        assertEquals("api_key", result.get().authType());
        assertEquals("service_account", result.get().usagePolicy());
        assertEquals(List.of("sync"), result.get().scopes());
        assertEquals("vault://oa-service", result.get().credentialRef());
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

    private AuthProfile authProfile(Long id, Long spaceId, Long connectorId, String code, String authType, String usagePolicy, String status) {
        AuthProfile profile = new AuthProfile();
        profile.setId(id);
        profile.setSpaceId(spaceId);
        profile.setConnectorId(connectorId);
        profile.setAuthProfileCode(code);
        profile.setAuthType(authType);
        profile.setUsagePolicy(usagePolicy);
        profile.setStatus(status);
        return profile;
    }
}


