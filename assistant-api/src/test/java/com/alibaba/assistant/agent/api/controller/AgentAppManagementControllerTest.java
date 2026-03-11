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
import com.alibaba.assistant.agent.controlplane.agentapp.AgentAppManagementService;
import com.alibaba.assistant.agent.controlplane.agentapp.AgentAppUpsertCommand;
import com.alibaba.assistant.agent.controlplane.agentapp.ResolvedAgentAppManagementView;
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
class AgentAppManagementControllerTest {

    private MockMvc mockMvc;

    @Mock
    private AgentAppManagementService agentAppManagementService;

    @Mock
    private MigrationControlPlaneAuthorizationService authorizationService;

    @BeforeEach
    void setUp() {
        AgentAppManagementController controller =
                new AgentAppManagementController(agentAppManagementService, authorizationService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void shouldListManagedAgentApps() throws Exception {
        when(authorizationService.canManageSpaceCatalog(any(AuthenticatedUserContext.class), eq("enterprise-default"), eq("prod")))
                .thenReturn(true);
        when(agentAppManagementService.listAgentApps("enterprise-default", "prod"))
                .thenReturn(List.of(new ResolvedAgentAppManagementView(
                        31L,
                        "enterprise-default",
                        "prod",
                        "finance-agent",
                        "Finance Agent",
                        Map.of("mode", "strict"),
                        Map.of("retention", "short"),
                        Map.of("required", true),
                        "active")));

        mockMvc.perform(get("/api/controlplane/spaces/enterprise-default/agent-apps/manage")
                        .principal(authenticatedPrincipal()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.agentApps.length()").value(1))
                .andExpect(jsonPath("$.data.agentApps[0].agentAppCode").value("finance-agent"))
                .andExpect(jsonPath("$.data.agentApps[0].promptPolicy.mode").value("strict"))
                .andExpect(jsonPath("$.data.agentApps[0].approvalStrategy.required").value(true));
    }

    @Test
    void shouldUpsertManagedAgentApp() throws Exception {
        when(authorizationService.canManageSpaceCatalog(any(AuthenticatedUserContext.class), eq("enterprise-default"), eq("test")))
                .thenReturn(true);
        when(agentAppManagementService.upsertAgentApp(
                eq("enterprise-default"),
                eq("test"),
                eq("finance-agent"),
                any(AgentAppUpsertCommand.class)))
                .thenReturn(Optional.of(new ResolvedAgentAppManagementView(
                        41L,
                        "enterprise-default",
                        "test",
                        "finance-agent",
                        "Finance Agent",
                        Map.of("mode", "strict"),
                        Map.of("retention", "long"),
                        Map.of("required", false),
                        "active")));

        mockMvc.perform(put("/api/controlplane/spaces/enterprise-default/agent-apps/finance-agent")
                        .param("environment", "test")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  \"displayName\": \"Finance Agent\",
                                  \"promptPolicy\": {\"mode\": \"strict\"},
                                  \"memoryPolicy\": {\"retention\": \"long\"},
                                  \"approvalStrategy\": {\"required\": false},
                                  \"status\": \"active\"
                                }
                                """)
                        .principal(authenticatedPrincipal()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.agentAppCode").value("finance-agent"))
                .andExpect(jsonPath("$.data.promptPolicy.mode").value("strict"))
                .andExpect(jsonPath("$.data.memoryPolicy.retention").value("long"));

        ArgumentCaptor<AgentAppUpsertCommand> commandCaptor = ArgumentCaptor.forClass(AgentAppUpsertCommand.class);
        verify(agentAppManagementService).upsertAgentApp(
                eq("enterprise-default"),
                eq("test"),
                eq("finance-agent"),
                commandCaptor.capture());
        assertEquals("Finance Agent", commandCaptor.getValue().displayName());
        assertEquals("strict", commandCaptor.getValue().promptPolicy().get("mode"));
    }

    @Test
    void shouldReturnForbiddenWhenManageScopeDenied() throws Exception {
        when(authorizationService.canManageSpaceCatalog(any(AuthenticatedUserContext.class), eq("enterprise-default"), eq("prod")))
                .thenReturn(false);

        mockMvc.perform(get("/api/controlplane/spaces/enterprise-default/agent-apps/manage")
                        .principal(authenticatedPrincipal()))
                .andExpect(status().isForbidden());

        verify(agentAppManagementService, never()).listAgentApps("enterprise-default", "prod");
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
