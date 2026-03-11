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
import com.alibaba.assistant.agent.controlplane.query.BusinessQueryActionManagementService;
import com.alibaba.assistant.agent.controlplane.query.BusinessQueryActionUpsertCommand;
import com.alibaba.assistant.agent.controlplane.query.ResolvedBusinessQueryActionManagementView;
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
class BusinessQueryActionManagementControllerTest {

    private MockMvc mockMvc;

    @Mock
    private BusinessQueryActionManagementService businessQueryActionManagementService;

    @Mock
    private MigrationControlPlaneAuthorizationService authorizationService;

    @BeforeEach
    void setUp() {
        BusinessQueryActionManagementController controller =
                new BusinessQueryActionManagementController(businessQueryActionManagementService, authorizationService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void shouldListManagedQueryActions() throws Exception {
        when(authorizationService.canManageSpaceCatalog(any(AuthenticatedUserContext.class), eq("enterprise-default"), eq("prod")))
                .thenReturn(true);
        when(businessQueryActionManagementService.listQueryActions("enterprise-default", "prod", "oa-core"))
                .thenReturn(List.of(new ResolvedBusinessQueryActionManagementView(
                        31L,
                        "enterprise-default",
                        "prod",
                        "oa-core",
                        "leave.balance",
                        Map.of("method", "GET", "endpoint", "/leave/balance"),
                        List.of("oa-user", "oa-service"),
                        List.of("user_mapped"),
                        Map.of("type", "object"),
                        Map.of("type", "object"),
                        "low",
                        Map.of("scope", "requester"),
                        "enabled")));

        mockMvc.perform(get("/api/controlplane/spaces/enterprise-default/connectors/oa-core/business-query-actions/manage")
                        .principal(authenticatedPrincipal()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.queryActions.length()").value(1))
                .andExpect(jsonPath("$.data.queryActions[0].queryActionCode").value("leave.balance"))
                .andExpect(jsonPath("$.data.queryActions[0].operationBinding.method").value("GET"))
                .andExpect(jsonPath("$.data.queryActions[0].riskLevel").value("LOW"));
    }

    @Test
    void shouldUpsertManagedQueryAction() throws Exception {
        when(authorizationService.canManageSpaceCatalog(any(AuthenticatedUserContext.class), eq("enterprise-default"), eq("test")))
                .thenReturn(true);
        when(businessQueryActionManagementService.upsertQueryAction(
                eq("enterprise-default"),
                eq("test"),
                eq("oa-core"),
                eq("leave.balance"),
                any(BusinessQueryActionUpsertCommand.class)))
                .thenReturn(Optional.of(new ResolvedBusinessQueryActionManagementView(
                        41L,
                        "enterprise-default",
                        "test",
                        "oa-core",
                        "leave.balance",
                        Map.of("method", "GET", "endpoint", "/leave/balance"),
                        List.of("oa-user"),
                        List.of("user_mapped"),
                        Map.of("type", "object"),
                        Map.of("type", "object"),
                        "medium",
                        Map.of("scope", "tenant"),
                        "enabled")));

        mockMvc.perform(put("/api/controlplane/spaces/enterprise-default/connectors/oa-core/business-query-actions/leave.balance")
                        .param("environment", "test")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "operationBinding": {"method": "GET", "endpoint": "/leave/balance"},
                                  "allowedAuthProfiles": ["oa-user"],
                                  "bindingStrategies": ["user_mapped"],
                                  "inputSchema": {"type": "object"},
                                  "outputSchema": {"type": "object"},
                                  "riskLevel": "medium",
                                  "resultVisibilityPolicy": {"scope": "tenant"},
                                  "status": "enabled"
                                }
                                """)
                        .principal(authenticatedPrincipal()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.queryActionCode").value("leave.balance"))
                .andExpect(jsonPath("$.data.riskLevel").value("MEDIUM"))
                .andExpect(jsonPath("$.data.status").value("ENABLED"));

        ArgumentCaptor<BusinessQueryActionUpsertCommand> commandCaptor =
                ArgumentCaptor.forClass(BusinessQueryActionUpsertCommand.class);
        verify(businessQueryActionManagementService).upsertQueryAction(
                eq("enterprise-default"),
                eq("test"),
                eq("oa-core"),
                eq("leave.balance"),
                commandCaptor.capture());
        assertEquals("GET", commandCaptor.getValue().operationBinding().get("method"));
        assertEquals(List.of("oa-user"), commandCaptor.getValue().allowedAuthProfiles());
    }

    @Test
    void shouldReturnForbiddenWhenManageScopeDenied() throws Exception {
        when(authorizationService.canManageSpaceCatalog(any(AuthenticatedUserContext.class), eq("enterprise-default"), eq("prod")))
                .thenReturn(false);

        mockMvc.perform(get("/api/controlplane/spaces/enterprise-default/connectors/oa-core/business-query-actions/manage")
                        .principal(authenticatedPrincipal()))
                .andExpect(status().isForbidden());

        verify(businessQueryActionManagementService, never()).listQueryActions("enterprise-default", "prod", "oa-core");
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
