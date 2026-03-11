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
package com.alibaba.assistant.agent.api.controller;

import com.alibaba.assistant.agent.api.security.AuthenticatedUserContext;
import com.alibaba.assistant.agent.api.security.MigrationControlPlaneAuthorizationService;
import com.alibaba.assistant.agent.controlplane.connector.ConnectorCatalogService;
import com.alibaba.assistant.agent.controlplane.connector.ResolvedAuthProfileView;
import com.alibaba.assistant.agent.controlplane.connector.ResolvedConnectorView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.security.Principal;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class ConnectorCatalogControllerTest {

    private MockMvc mockMvc;

    @Mock
    private ConnectorCatalogService connectorCatalogService;

    @Mock
    private MigrationControlPlaneAuthorizationService authorizationService;

    @BeforeEach
    void setUp() {
        ConnectorCatalogController controller = new ConnectorCatalogController(connectorCatalogService, authorizationService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void shouldGetConnectorDetail() throws Exception {
        when(authorizationService.canViewSpaceCatalog(any(AuthenticatedUserContext.class), eq("enterprise-default"), eq("test")))
                .thenReturn(true);
        when(connectorCatalogService.getConnector("enterprise-default", "test", "oa-core"))
                .thenReturn(Optional.of(new ResolvedConnectorView(
                        11L,
                        "enterprise-default",
                        "test",
                        "oa-core",
                        "gougu_oa",
                        "OA Core",
                        "openapi",
                        "intranet",
                        "http://oa.internal",
                        "active",
                        2)));

        mockMvc.perform(get("/api/controlplane/spaces/enterprise-default/connectors/oa-core")
                        .param("environment", "test")
                        .principal(authenticatedPrincipal()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.spaceCode").value("enterprise-default"))
                .andExpect(jsonPath("$.data.environment").value("test"))
                .andExpect(jsonPath("$.data.connectorCode").value("oa-core"))
                .andExpect(jsonPath("$.data.systemCode").value("gougu_oa"))
                .andExpect(jsonPath("$.data.displayName").value("OA Core"))
                .andExpect(jsonPath("$.data.protocolType").value("OPENAPI"))
                .andExpect(jsonPath("$.data.networkZone").value("INTRANET"))
                .andExpect(jsonPath("$.data.baseUrl").value("http://oa.internal"))
                .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.version").value(2));
    }

    @Test
    void shouldListConnectorAuthProfiles() throws Exception {
        when(authorizationService.canViewSpaceCatalog(any(AuthenticatedUserContext.class), eq("enterprise-default"), eq("prod")))
                .thenReturn(true);
        when(connectorCatalogService.listAuthProfiles("enterprise-default", "prod", "oa-core"))
                .thenReturn(List.of(
                        new ResolvedAuthProfileView(
                                31L,
                                "enterprise-default",
                                "prod",
                                "oa-core",
                                "oa-user",
                                "bearer",
                                "user_mapped",
                                "Authorization",
                                "Bearer ",
                                "active"),
                        new ResolvedAuthProfileView(
                                32L,
                                "enterprise-default",
                                "prod",
                                "oa-core",
                                "oa-service",
                                "api_key",
                                "service_account",
                                "X-API-Key",
                                "",
                                "active")));

        mockMvc.perform(get("/api/controlplane/spaces/enterprise-default/connectors/oa-core/auth-profiles")
                        .principal(authenticatedPrincipal()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.connectorCode").value("oa-core"))
                .andExpect(jsonPath("$.data.authProfiles.length()").value(2))
                .andExpect(jsonPath("$.data.authProfiles[0].authProfileCode").value("oa-user"))
                .andExpect(jsonPath("$.data.authProfiles[0].authType").value("BEARER"))
                .andExpect(jsonPath("$.data.authProfiles[1].usagePolicy").value("SERVICE_ACCOUNT"));
    }

    @Test
    void shouldReturnForbiddenWhenCatalogScopeDenied() throws Exception {
        when(authorizationService.canViewSpaceCatalog(any(AuthenticatedUserContext.class), eq("enterprise-default"), eq("prod")))
                .thenReturn(false);

        mockMvc.perform(get("/api/controlplane/spaces/enterprise-default/connectors/oa-core")
                        .principal(authenticatedPrincipal()))
                .andExpect(status().isForbidden());

        verify(connectorCatalogService, never()).getConnector("enterprise-default", "prod", "oa-core");
    }

    private Principal authenticatedPrincipal() {
        return new UsernamePasswordAuthenticationToken(authenticatedUser(), "token-controlplane", List.of());
    }

    private AuthenticatedUserContext authenticatedUser() {
        return new AuthenticatedUserContext(
                "1001",
                1L,
                "gougu_oa",
                "assistant-ui",
                "token-controlplane",
                "admin",
                "管理员",
                List.of("assistant_user", "assistant_controlplane_admin"),
                List.of("assistant:chat", "assistant:controlplane"));
    }
}
