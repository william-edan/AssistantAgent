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
import com.alibaba.assistant.agent.controlplane.action.ActionSpecManagementService;
import com.alibaba.assistant.agent.controlplane.action.ActionSpecUpsertCommand;
import com.alibaba.assistant.agent.controlplane.action.ResolvedActionSpecManagementView;
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
class ActionSpecManagementControllerTest {

    private MockMvc mockMvc;

    @Mock
    private ActionSpecManagementService actionSpecManagementService;

    @Mock
    private MigrationControlPlaneAuthorizationService authorizationService;

    @BeforeEach
    void setUp() {
        ActionSpecManagementController controller =
                new ActionSpecManagementController(actionSpecManagementService, authorizationService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void shouldListManagedActions() throws Exception {
        when(authorizationService.canManageSpaceCatalog(any(AuthenticatedUserContext.class), eq("enterprise-default"), eq("prod")))
                .thenReturn(true);
        when(actionSpecManagementService.listActions("enterprise-default", "prod", "oa-core"))
                .thenReturn(List.of(new ResolvedActionSpecManagementView(
                        31L,
                        "enterprise-default",
                        "prod",
                        "oa-core",
                        "oa.leave.create",
                        Map.of("method", "POST", "endpoint", "/leave/create"),
                        List.of("oa-user", "oa-service"),
                        "oa-user",
                        List.of("user_mapped"),
                        Map.of("type", "object"),
                        Map.of("type", "object"),
                        Map.of("mode", "client_token"),
                        "medium",
                        5L,
                        "write",
                        Map.of("level", "detailed"),
                        "enabled")));

        mockMvc.perform(get("/api/controlplane/spaces/enterprise-default/connectors/oa-core/actions/manage")
                        .principal(authenticatedPrincipal()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.actions.length()").value(1))
                .andExpect(jsonPath("$.data.actions[0].actionCode").value("oa.leave.create"))
                .andExpect(jsonPath("$.data.actions[0].operationBinding.method").value("POST"))
                .andExpect(jsonPath("$.data.actions[0].allowedAuthProfiles[1]").value("oa-service"));
    }

    @Test
    void shouldUpsertManagedAction() throws Exception {
        when(authorizationService.canManageSpaceCatalog(any(AuthenticatedUserContext.class), eq("enterprise-default"), eq("test")))
                .thenReturn(true);
        when(actionSpecManagementService.upsertAction(
                eq("enterprise-default"),
                eq("test"),
                eq("oa-core"),
                eq("oa.leave.create"),
                any(ActionSpecUpsertCommand.class)))
                .thenReturn(Optional.of(new ResolvedActionSpecManagementView(
                        41L,
                        "enterprise-default",
                        "test",
                        "oa-core",
                        "oa.leave.create",
                        Map.of("method", "POST", "endpoint", "/leave/create"),
                        List.of("oa-user"),
                        "oa-user",
                        List.of("user_mapped"),
                        Map.of("type", "object"),
                        Map.of("type", "object"),
                        Map.of("mode", "client_token"),
                        "high",
                        8L,
                        "write",
                        Map.of("level", "basic"),
                        "enabled")));

        mockMvc.perform(put("/api/controlplane/spaces/enterprise-default/connectors/oa-core/actions/oa.leave.create")
                        .param("environment", "test")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "operationBinding": {"method": "POST", "endpoint": "/leave/create"},
                                  "allowedAuthProfiles": ["oa-user"],
                                  "defaultAuthProfileCode": "oa-user",
                                  "bindingStrategies": ["user_mapped"],
                                  "inputSchema": {"type": "object"},
                                  "outputSchema": {"type": "object"},
                                  "idempotencyPolicy": {"mode": "client_token"},
                                  "riskLevel": "high",
                                  "approvalPolicyId": 8,
                                  "sideEffectLevel": "write",
                                  "observabilityProfile": {"level": "basic"},
                                  "status": "enabled"
                                }
                                """)
                        .principal(authenticatedPrincipal()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.actionCode").value("oa.leave.create"))
                .andExpect(jsonPath("$.data.riskLevel").value("HIGH"))
                .andExpect(jsonPath("$.data.sideEffectLevel").value("WRITE"));

        ArgumentCaptor<ActionSpecUpsertCommand> commandCaptor = ArgumentCaptor.forClass(ActionSpecUpsertCommand.class);
        verify(actionSpecManagementService).upsertAction(
                eq("enterprise-default"),
                eq("test"),
                eq("oa-core"),
                eq("oa.leave.create"),
                commandCaptor.capture());
        assertEquals(List.of("oa-user"), commandCaptor.getValue().allowedAuthProfiles());
        assertEquals("POST", commandCaptor.getValue().operationBinding().get("method"));
    }

    @Test
    void shouldReturnForbiddenWhenManageScopeDenied() throws Exception {
        when(authorizationService.canManageSpaceCatalog(any(AuthenticatedUserContext.class), eq("enterprise-default"), eq("prod")))
                .thenReturn(false);

        mockMvc.perform(get("/api/controlplane/spaces/enterprise-default/connectors/oa-core/actions/manage")
                        .principal(authenticatedPrincipal()))
                .andExpect(status().isForbidden());

        verify(actionSpecManagementService, never()).listActions("enterprise-default", "prod", "oa-core");
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
