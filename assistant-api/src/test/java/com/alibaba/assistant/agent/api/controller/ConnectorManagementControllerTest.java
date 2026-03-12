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
import com.alibaba.assistant.agent.controlplane.connector.ConnectorManagementService;
import com.alibaba.assistant.agent.controlplane.connector.ConnectorUpsertCommand;
import com.alibaba.assistant.agent.controlplane.connector.ResolvedConnectorView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.security.Principal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class ConnectorManagementControllerTest {

    private MockMvc mockMvc;

    @Mock
    private ConnectorManagementService connectorManagementService;

    @Mock
    private MigrationControlPlaneAuthorizationService authorizationService;

    @BeforeEach
    void setUp() {
        ConnectorManagementController controller =
                new ConnectorManagementController(connectorManagementService, authorizationService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void shouldListManagedConnectors() throws Exception {
        when(authorizationService.canManageSpaceCatalog(any(AuthenticatedUserContext.class), eq("enterprise-default"), eq("prod")))
                .thenReturn(true);
        when(connectorManagementService.listConnectors("enterprise-default", "prod", "finance"))
                .thenReturn(List.of(new ResolvedConnectorView(
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

        mockMvc.perform(get("/api/controlplane/spaces/enterprise-default/connectors")
                        .param("keyword", "finance")
                        .principal(authenticatedPrincipal()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.connectors.length()").value(1))
                .andExpect(jsonPath("$.data.connectors[0].connectorCode").value("oa-core"))
                .andExpect(jsonPath("$.data.connectors[0].networkZone").value("INTRANET"));
    }

    @Test
    void shouldGetManagedConnector() throws Exception {
        when(authorizationService.canManageSpaceCatalog(any(AuthenticatedUserContext.class), eq("enterprise-default"), eq("prod")))
                .thenReturn(true);
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
                        "http://oa.internal",
                        "active",
                        2)));

        mockMvc.perform(get("/api/controlplane/spaces/enterprise-default/connectors/oa-core")
                        .principal(authenticatedPrincipal()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.connectorCode").value("oa-core"))
                .andExpect(jsonPath("$.data.networkZone").value("INTRANET"));
    }
    @Test
    void shouldUpsertConnector() throws Exception {
        when(authorizationService.canManageSpaceCatalog(any(AuthenticatedUserContext.class), eq("enterprise-default"), eq("test")))
                .thenReturn(true);
        when(connectorManagementService.upsertConnector(
                eq("enterprise-default"),
                eq("test"),
                eq("oa-core"),
                any(ConnectorUpsertCommand.class)))
                .thenReturn(Optional.of(new ResolvedConnectorView(
                        12L,
                        "enterprise-default",
                        "test",
                        "oa-core",
                        "gougu_oa",
                        "OA Core",
                        "openapi",
                        "dmz",
                        "http://oa.test",
                        "active",
                        1)));

        mockMvc.perform(put("/api/controlplane/spaces/enterprise-default/connectors/oa-core")
                        .param("environment", "test")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "systemCode": "gougu_oa",
                                  "displayName": "OA Core",
                                  "protocolType": "openapi",
                                  "networkZone": "dmz",
                                  "baseUrl": "http://oa.test",
                                  "status": "active"
                                }
                                """)
                        .principal(authenticatedPrincipal()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.connectorCode").value("oa-core"))
                .andExpect(jsonPath("$.data.environment").value("test"))
                .andExpect(jsonPath("$.data.networkZone").value("DMZ"))
                .andExpect(jsonPath("$.data.version").value(1));

        ArgumentCaptor<ConnectorUpsertCommand> commandCaptor = ArgumentCaptor.forClass(ConnectorUpsertCommand.class);
        verify(connectorManagementService).upsertConnector(
                eq("enterprise-default"),
                eq("test"),
                eq("oa-core"),
                commandCaptor.capture());
        assertEquals("dmz", commandCaptor.getValue().networkZone());
    }

    @Test
    void shouldReturnForbiddenWhenManageScopeDenied() throws Exception {
        when(authorizationService.canManageSpaceCatalog(any(AuthenticatedUserContext.class), eq("enterprise-default"), eq("prod")))
                .thenReturn(false);

        mockMvc.perform(get("/api/controlplane/spaces/enterprise-default/connectors")
                        .principal(authenticatedPrincipal()))
                .andExpect(status().isForbidden());

        verify(connectorManagementService, never()).listConnectors("enterprise-default", "prod", null);
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





