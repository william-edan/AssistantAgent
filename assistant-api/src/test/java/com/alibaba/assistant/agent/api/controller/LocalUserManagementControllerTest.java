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
import com.alibaba.assistant.agent.controlplane.identity.LocalUserManagementService;
import com.alibaba.assistant.agent.controlplane.identity.LocalUserUpsertCommand;
import com.alibaba.assistant.agent.controlplane.identity.ResolvedLocalUserManagementView;
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
class LocalUserManagementControllerTest {

    @Mock
    private LocalUserManagementService localUserManagementService;

    @Mock
    private MigrationControlPlaneAuthorizationService authorizationService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        LocalUserManagementController controller = new LocalUserManagementController(
                localUserManagementService,
                authorizationService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new ControlPlaneErrorResponseAdvice())
                .build();
    }

    @Test
    void shouldListManagedLocalUsers() throws Exception {
        when(authorizationService.canManageLocalUserControlPlaneAccessPolicy(any(AuthenticatedUserContext.class), eq("enterprise-default"), eq("prod")))
                .thenReturn(true);
        when(localUserManagementService.listLocalUsers("enterprise-default", "prod", "admin"))
                .thenReturn(List.of(new ResolvedLocalUserManagementView(
                        1001L,
                        "enterprise-default",
                        "prod",
                        "admin",
                        "管理员",
                        1L,
                        "gougu_oa",
                        "active")));

        mockMvc.perform(get("/api/controlplane/spaces/enterprise-default/local-users")
                        .param("keyword", "admin")
                        .principal(authenticatedPrincipal()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.localUsers.length()").value(1))
                .andExpect(jsonPath("$.data.localUsers[0].localUserId").value(1001))
                .andExpect(jsonPath("$.data.localUsers[0].username").value("admin"))
                .andExpect(jsonPath("$.data.localUsers[0].status").value("ACTIVE"));
    }

    @Test
    void shouldGetManagedLocalUser() throws Exception {
        when(authorizationService.canManageLocalUserControlPlaneAccessPolicy(any(AuthenticatedUserContext.class), eq("enterprise-default"), eq("prod")))
                .thenReturn(true);
        when(localUserManagementService.getLocalUser("enterprise-default", "prod", "admin", "gougu_oa"))
                .thenReturn(Optional.of(new ResolvedLocalUserManagementView(
                        1001L,
                        "enterprise-default",
                        "prod",
                        "admin",
                        "管理员",
                        1L,
                        "gougu_oa",
                        "active")));

        mockMvc.perform(get("/api/controlplane/spaces/enterprise-default/local-users/admin")
                        .param("systemCode", "gougu_oa")
                        .principal(authenticatedPrincipal()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.localUserId").value(1001))
                .andExpect(jsonPath("$.data.systemCode").value("gougu_oa"));
    }

    @Test
    void shouldUpsertManagedLocalUser() throws Exception {
        when(authorizationService.canManageLocalUserControlPlaneAccessPolicy(any(AuthenticatedUserContext.class), eq("enterprise-default"), eq("prod")))
                .thenReturn(true);
        when(localUserManagementService.upsertLocalUser(
                eq("enterprise-default"),
                eq("prod"),
                eq("admin"),
                any(LocalUserUpsertCommand.class)))
                .thenReturn(Optional.of(new ResolvedLocalUserManagementView(
                        1001L,
                        "enterprise-default",
                        "prod",
                        "admin",
                        "管理员",
                        1L,
                        "gougu_oa",
                        "active")));

        mockMvc.perform(put("/api/controlplane/spaces/enterprise-default/local-users/admin")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "displayName": "管理员",
                                  "password": "admin123",
                                  "tenantId": 1,
                                  "systemCode": "gougu_oa",
                                  "status": "active"
                                }
                                """)
                        .principal(authenticatedPrincipal()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.localUserId").value(1001))
                .andExpect(jsonPath("$.data.username").value("admin"));

        ArgumentCaptor<LocalUserUpsertCommand> commandCaptor = ArgumentCaptor.forClass(LocalUserUpsertCommand.class);
        verify(localUserManagementService).upsertLocalUser(
                eq("enterprise-default"),
                eq("prod"),
                eq("admin"),
                commandCaptor.capture());
        assertEquals("admin123", commandCaptor.getValue().password());
        assertEquals("gougu_oa", commandCaptor.getValue().systemCode());
    }

    @Test
    void shouldReturnNormalizedBadRequestWhenLocalUserUpsertFails() throws Exception {
        when(authorizationService.canManageLocalUserControlPlaneAccessPolicy(any(AuthenticatedUserContext.class), eq("enterprise-default"), eq("prod")))
                .thenReturn(true);
        when(localUserManagementService.upsertLocalUser(
                eq("enterprise-default"),
                eq("prod"),
                eq("admin"),
                any(LocalUserUpsertCommand.class)))
                .thenReturn(Optional.empty());

        mockMvc.perform(put("/api/controlplane/spaces/enterprise-default/local-users/admin")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "displayName": "管理员"
                                }
                                """)
                        .principal(authenticatedPrincipal()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.msg").value("local_user_upsert_failed"));
    }
    @Test
    void shouldReturnForbiddenWhenLocalUserManageScopeDenied() throws Exception {
        when(authorizationService.canManageLocalUserControlPlaneAccessPolicy(any(AuthenticatedUserContext.class), eq("enterprise-default"), eq("prod")))
                .thenReturn(false);

        mockMvc.perform(get("/api/controlplane/spaces/enterprise-default/local-users")
                        .principal(authenticatedPrincipal()))
                .andExpect(status().isForbidden());

        verify(localUserManagementService, never()).listLocalUsers("enterprise-default", "prod", null);
    }

    private Principal authenticatedPrincipal() {
        return new UsernamePasswordAuthenticationToken(authenticatedUser(), "token-space-admin", List.of());
    }

    private AuthenticatedUserContext authenticatedUser() {
        return new AuthenticatedUserContext(
                "1001",
                1L,
                "gougu_oa",
                "assistant-ui",
                "token-space-admin",
                "admin",
                "管理员",
                List.of("assistant_space_admin"),
                List.of("assistant:chat", "assistant:controlplane"));
    }
}



