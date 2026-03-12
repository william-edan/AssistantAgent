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
import com.alibaba.assistant.agent.controlplane.interaction.InteractionSpecManagementService;
import com.alibaba.assistant.agent.controlplane.interaction.InteractionSpecUpsertCommand;
import com.alibaba.assistant.agent.controlplane.interaction.ResolvedInteractionSpecManagementView;
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
class InteractionSpecManagementControllerTest {

    private MockMvc mockMvc;

    @Mock
    private InteractionSpecManagementService interactionSpecManagementService;

    @Mock
    private MigrationControlPlaneAuthorizationService authorizationService;

    @BeforeEach
    void setUp() {
        InteractionSpecManagementController controller =
                new InteractionSpecManagementController(interactionSpecManagementService, authorizationService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void shouldListManagedInteractions() throws Exception {
        when(authorizationService.canManageSpaceCatalog(any(AuthenticatedUserContext.class), eq("enterprise-default"), eq("prod")))
                .thenReturn(true);
        when(interactionSpecManagementService.listInteractions("enterprise-default", "prod", "leave"))
                .thenReturn(List.of(new ResolvedInteractionSpecManagementView(
                        31L,
                        "enterprise-default",
                        "prod",
                        "leave.apply.form",
                        Map.of("slots", List.of(Map.of("name", "reason"))),
                        Map.of("mode", "batch"),
                        Map.of("duration", "date_diff"),
                        Map.of("sections", List.of("core")),
                        Map.of("required", true),
                        Map.of("allowEdit", true),
                        "enabled")));

        mockMvc.perform(get("/api/controlplane/spaces/enterprise-default/interactions/manage")
                        .param("keyword", "leave")
                        .principal(authenticatedPrincipal()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.interactions.length()").value(1))
                .andExpect(jsonPath("$.data.interactions[0].interactionCode").value("leave.apply.form"))
                .andExpect(jsonPath("$.data.interactions[0].askStrategy.mode").value("batch"))
                .andExpect(jsonPath("$.data.interactions[0].confirmationPolicy.required").value(true));
    }

    @Test
    void shouldGetManagedInteraction() throws Exception {
        when(authorizationService.canManageSpaceCatalog(any(AuthenticatedUserContext.class), eq("enterprise-default"), eq("prod")))
                .thenReturn(true);
        when(interactionSpecManagementService.getInteraction("enterprise-default", "prod", "leave.apply.form"))
                .thenReturn(Optional.of(new ResolvedInteractionSpecManagementView(
                        31L,
                        "enterprise-default",
                        "prod",
                        "leave.apply.form",
                        Map.of("slots", List.of(Map.of("name", "reason"))),
                        Map.of("mode", "batch"),
                        Map.of("duration", "date_diff"),
                        Map.of("sections", List.of("core")),
                        Map.of("required", true),
                        Map.of("allowEdit", true),
                        "enabled")));

        mockMvc.perform(get("/api/controlplane/spaces/enterprise-default/interactions/leave.apply.form")
                        .principal(authenticatedPrincipal()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.interactionCode").value("leave.apply.form"))
                .andExpect(jsonPath("$.data.askStrategy.mode").value("batch"));
    }
    @Test
    void shouldUpsertManagedInteraction() throws Exception {
        when(authorizationService.canManageSpaceCatalog(any(AuthenticatedUserContext.class), eq("enterprise-default"), eq("test")))
                .thenReturn(true);
        when(interactionSpecManagementService.upsertInteraction(
                eq("enterprise-default"),
                eq("test"),
                eq("leave.apply.form"),
                any(InteractionSpecUpsertCommand.class)))
                .thenReturn(Optional.of(new ResolvedInteractionSpecManagementView(
                        41L,
                        "enterprise-default",
                        "test",
                        "leave.apply.form",
                        Map.of("slots", List.of(Map.of("name", "types"))),
                        Map.of("mode", "progressive"),
                        Map.of("duration", "recompute"),
                        Map.of("sections", List.of("core", "secondary")),
                        Map.of("required", false),
                        Map.of("allowEdit", false),
                        "enabled")));

        mockMvc.perform(put("/api/controlplane/spaces/enterprise-default/interactions/leave.apply.form")
                        .param("environment", "test")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "slotSchema": {"slots": [{"name": "types"}]},
                                  "askStrategy": {"mode": "progressive"},
                                  "autoFillRules": {"duration": "recompute"},
                                  "summaryLayout": {"sections": ["core", "secondary"]},
                                  "confirmationPolicy": {"required": false},
                                  "editPolicy": {"allowEdit": false},
                                  "status": "enabled"
                                }
                                """)
                        .principal(authenticatedPrincipal()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.interactionCode").value("leave.apply.form"))
                .andExpect(jsonPath("$.data.askStrategy.mode").value("progressive"))
                .andExpect(jsonPath("$.data.editPolicy.allowEdit").value(false));

        ArgumentCaptor<InteractionSpecUpsertCommand> commandCaptor = ArgumentCaptor.forClass(InteractionSpecUpsertCommand.class);
        verify(interactionSpecManagementService).upsertInteraction(
                eq("enterprise-default"),
                eq("test"),
                eq("leave.apply.form"),
                commandCaptor.capture());
        assertEquals("progressive", commandCaptor.getValue().askStrategy().get("mode"));
    }

    @Test
    void shouldReturnForbiddenWhenManageScopeDenied() throws Exception {
        when(authorizationService.canManageSpaceCatalog(any(AuthenticatedUserContext.class), eq("enterprise-default"), eq("prod")))
                .thenReturn(false);

        mockMvc.perform(get("/api/controlplane/spaces/enterprise-default/interactions/manage")
                        .principal(authenticatedPrincipal()))
                .andExpect(status().isForbidden());

        verify(interactionSpecManagementService, never()).listInteractions("enterprise-default", "prod", null);
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





