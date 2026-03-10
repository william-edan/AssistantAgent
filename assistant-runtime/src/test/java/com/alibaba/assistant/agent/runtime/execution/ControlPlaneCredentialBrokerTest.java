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
package com.alibaba.assistant.agent.runtime.execution;

import com.alibaba.assistant.agent.controlplane.connector.AuthProfile;
import com.alibaba.assistant.agent.controlplane.connector.AuthProfileService;
import com.alibaba.assistant.agent.controlplane.connector.Connector;
import com.alibaba.assistant.agent.controlplane.connector.ConnectorService;
import com.alibaba.assistant.agent.controlplane.identity.PrincipalBindingV2;
import com.alibaba.assistant.agent.controlplane.identity.PrincipalBindingV2Service;
import com.alibaba.assistant.agent.controlplane.identity.TokenBroker;
import com.alibaba.assistant.agent.controlplane.identity.TokenLease;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ControlPlaneCredentialBrokerTest {

    @Test
    void shouldResolveLeaseViaConnectorAndLegacyTokenBroker() {
        ConnectorService connectorService = mock(ConnectorService.class);
        AuthProfileService authProfileService = mock(AuthProfileService.class);
        PrincipalBindingV2Service principalBindingV2Service = mock(PrincipalBindingV2Service.class);
        TokenBroker legacyTokenBroker = mock(TokenBroker.class);
        ControlPlaneCredentialBroker broker = new ControlPlaneCredentialBroker(
                connectorService,
                authProfileService,
                principalBindingV2Service,
                legacyTokenBroker);

        Connector connector = new Connector();
        connector.setId(10L);
        connector.setSpaceId(1L);
        connector.setSystemCode("gougu_oa");
        connector.setStatus("active");
        connector.setBaseUrl("http://oa.internal");
        when(connectorService.getById(10L)).thenReturn(connector);

        AuthProfile authProfile = new AuthProfile();
        authProfile.setConnectorId(10L);
        authProfile.setAuthProfileCode("oa-user");
        authProfile.setAuthType("bearer");
        authProfile.setTokenHeaderName("Authorization");
        authProfile.setTokenHeaderPrefix("Bearer ");
        when(authProfileService.listActiveByConnector(10L)).thenReturn(List.of(authProfile));

        PrincipalBindingV2 binding = new PrincipalBindingV2();
        binding.setId(21L);
        when(principalBindingV2Service.findHighestPriorityActiveBinding(1L, 10L, "1001"))
                .thenReturn(Optional.of(binding));

        when(legacyTokenBroker.acquire("1001", "gougu_oa"))
                .thenReturn(Optional.of(new TokenLease(
                        "legacy-lease",
                        "token-123",
                        "gougu_oa",
                        "1001",
                        LocalDateTime.now().plusMinutes(30))));

        ResolvedCredentialLease lease = broker.resolve(new CredentialResolutionRequest(
                1L,
                10L,
                List.of("oa-user"),
                "1001",
                "local_user",
                List.of("leave.write"),
                "RUN-1",
                "submit_approval",
                "gougu_oa"));

        assertEquals("oa-user", lease.authProfileCode());
        assertEquals(21L, lease.principalBindingId());
        assertEquals(10L, lease.connectorId());
        assertEquals("BEARER", lease.credentialType());
        assertEquals("Bearer token-123", lease.headers().get("Authorization"));
        assertEquals("gougu_oa", lease.compatibilitySystemCode());
        assertEquals("http://oa.internal", lease.baseUrl());
        assertTrue(lease.expiresAt().isAfter(Instant.now()));
    }

    @Test
    void shouldFallbackToCredentialReferenceWhenLegacyTokenUnavailable() {
        ConnectorService connectorService = mock(ConnectorService.class);
        AuthProfileService authProfileService = mock(AuthProfileService.class);
        PrincipalBindingV2Service principalBindingV2Service = mock(PrincipalBindingV2Service.class);
        TokenBroker legacyTokenBroker = mock(TokenBroker.class);
        ControlPlaneCredentialBroker broker = new ControlPlaneCredentialBroker(
                connectorService,
                authProfileService,
                principalBindingV2Service,
                legacyTokenBroker);

        Connector connector = new Connector();
        connector.setId(20L);
        connector.setSpaceId(1L);
        connector.setStatus("active");
        connector.setBaseUrl("http://erp.internal");
        when(connectorService.getById(20L)).thenReturn(connector);

        AuthProfile authProfile = new AuthProfile();
        authProfile.setConnectorId(20L);
        authProfile.setAuthProfileCode("erp-service");
        authProfile.setAuthType("api_key");
        authProfile.setUsagePolicy("service_account");
        authProfile.setCredentialRef("vault://erp/service-account");
        authProfile.setTokenHeaderName("X-API-Key");
        authProfile.setTokenHeaderPrefix("");
        when(authProfileService.listActiveByConnector(20L)).thenReturn(List.of(authProfile));

        PrincipalBindingV2 binding = new PrincipalBindingV2();
        binding.setId(31L);
        when(principalBindingV2Service.findHighestPriorityActiveBinding(1L, 20L, "1001"))
                .thenReturn(Optional.of(binding));

        ResolvedCredentialLease lease = broker.resolve(new CredentialResolutionRequest(
                1L,
                20L,
                List.of("erp-service"),
                "1001",
                "local_user",
                List.of(),
                "RUN-2",
                "sync_expense",
                null));

        assertEquals("erp-service", lease.authProfileCode());
        assertEquals("vault://erp/service-account", lease.headers().get("X-API-Key"));
        assertEquals("API_KEY", lease.credentialType());
        assertEquals("http://erp.internal", lease.baseUrl());
    }
}
