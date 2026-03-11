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
import com.alibaba.assistant.agent.controlplane.connector.AuthProfileManagementService;
import com.alibaba.assistant.agent.controlplane.connector.AuthProfileUpsertCommand;
import com.alibaba.assistant.agent.controlplane.connector.ResolvedAuthProfileManagementView;
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
import java.util.Map;
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
class AuthProfileManagementControllerTest {

    private MockMvc mockMvc;

    @Mock
    private AuthProfileManagementService authProfileManagementService;

    @Mock
    private MigrationControlPlaneAuthorizationService authorizationService;

    @BeforeEach
    void setUp() {
        AuthProfileManagementController controller =
                new AuthProfileManagementController(authProfileManagementService, authorizationService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void shouldListManagedAuthProfiles() throws Exception {
        when(authorizationService.canManageSpaceCatalog(any(AuthenticatedUserContext.class), eq("enterprise-default"), eq("prod")))
                .thenReturn(true);
        when(authProfileManagementService.listAuthProfiles("enterprise-default", "prod", "oa-core"))
                .thenReturn(List.of(new ResolvedAuthProfileManagementView(
                        31L,
                        "enterprise-default",
                        "prod",
                        "oa-core",
                        "oa-user",
                        "bearer",
                        "user_mapped",
                        "https://idp/token",
                        "Authorization",
                        "Bearer ",
                        "oa-api",
                        List.of("read", "write"),
                        "vault://oa-user",
                        Map.of("refreshBeforeSeconds", 60),
                        "active")));

        mockMvc.perform(get("/api/controlplane/spaces/enterprise-default/connectors/oa-core/auth-profiles/manage")
                        .principal(authenticatedPrincipal()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.authProfiles.length()").value(1))
                .andExpect(jsonPath("$.data.authProfiles[0].authProfileCode").value("oa-user"))
                .andExpect(jsonPath("$.data.authProfiles[0].scopes[1]").value("write"))
                .andExpect(jsonPath("$.data.authProfiles[0].credentialRef").value("vault://oa-user"));
    }

    @Test
    void shouldUpsertManagedAuthProfile() throws Exception {
        when(authorizationService.canManageSpaceCatalog(any(AuthenticatedUserContext.class), eq("enterprise-default"), eq("test")))
                .thenReturn(true);
        when(authProfileManagementService.upsertAuthProfile(
                eq("enterprise-default"),
                eq("test"),
                eq("oa-core"),
                eq("oa-service"),
                any(AuthProfileUpsertCommand.class)))
                .thenReturn(Optional.of(new ResolvedAuthProfileManagementView(
                        41L,
                        "enterprise-default",
                        "test",
                        "oa-core",
                        "oa-service",
                        "api_key",
                        "service_account",
                        null,
                        "X-API-Key",
                        "",
                        null,
                        List.of("sync"),
                        "vault://oa-service",
                        Map.of("rotate", true),
                        "active")));

        mockMvc.perform(put("/api/controlplane/spaces/enterprise-default/connectors/oa-core/auth-profiles/oa-service")
                        .param("environment", "test")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "authType": "api_key",
                                  "usagePolicy": "service_account",
                                  "tokenHeaderName": "X-API-Key",
                                  "tokenHeaderPrefix": "",
                                  "scopes": ["sync"],
                                  "credentialRef": "vault://oa-service",
                                  "refreshPolicy": {"rotate": true},
                                  "status": "active"
                                }
                                """)
                        .principal(authenticatedPrincipal()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.authProfileCode").value("oa-service"))
                .andExpect(jsonPath("$.data.authType").value("API_KEY"))
                .andExpect(jsonPath("$.data.scopes[0]").value("sync"));

        ArgumentCaptor<AuthProfileUpsertCommand> commandCaptor = ArgumentCaptor.forClass(AuthProfileUpsertCommand.class);
        verify(authProfileManagementService).upsertAuthProfile(
                eq("enterprise-default"),
                eq("test"),
                eq("oa-core"),
                eq("oa-service"),
                commandCaptor.capture());
        assertEquals(List.of("sync"), commandCaptor.getValue().scopes());
    }

    @Test
    void shouldReturnForbiddenWhenManageScopeDenied() throws Exception {
        when(authorizationService.canManageSpaceCatalog(any(AuthenticatedUserContext.class), eq("enterprise-default"), eq("prod")))
                .thenReturn(false);

        mockMvc.perform(get("/api/controlplane/spaces/enterprise-default/connectors/oa-core/auth-profiles/manage")
                        .principal(authenticatedPrincipal()))
                .andExpect(status().isForbidden());

        verify(authProfileManagementService, never()).listAuthProfiles("enterprise-default", "prod", "oa-core");
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
