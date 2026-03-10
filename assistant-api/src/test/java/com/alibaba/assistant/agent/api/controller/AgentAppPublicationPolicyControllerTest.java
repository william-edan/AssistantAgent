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
import com.alibaba.assistant.agent.controlplane.agentapp.AgentAppPublicationPolicyService;
import com.alibaba.assistant.agent.controlplane.agentapp.AgentAppPublicationSourcePolicy;
import com.alibaba.assistant.agent.controlplane.agentapp.ResolvedAgentAppPublicationSourcePolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
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
class AgentAppPublicationPolicyControllerTest {

    private MockMvc mockMvc;

    @Mock
    private AgentAppPublicationPolicyService publicationPolicyService;

    @Mock
    private MigrationControlPlaneAuthorizationService authorizationService;

    @BeforeEach
    void setUp() {
        AgentAppPublicationPolicyController controller = new AgentAppPublicationPolicyController(
                publicationPolicyService,
                authorizationService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void shouldGetPublicationSourcePolicy() throws Exception {
        when(authorizationService.canManageAgentAppPublicationPolicy(
                any(AuthenticatedUserContext.class), eq("enterprise-default"), eq("test"), eq("finance-agent")))
                .thenReturn(true);
        when(publicationPolicyService.getPublicationSourcePolicy(eq("enterprise-default"), eq("test"), eq("finance-agent")))
                .thenReturn(Optional.of(new ResolvedAgentAppPublicationSourcePolicy(
                        9L,
                        "enterprise-default",
                        "test",
                        7L,
                        "finance-agent",
                        new AgentAppPublicationSourcePolicy(
                                "exclusive",
                                List.of("artifact-catalog"),
                                List.of("legacy-bridge")))));

        mockMvc.perform(get("/api/controlplane/spaces/enterprise-default/agent-apps/finance-agent/publication-source-policy")
                        .param("environment", "test")
                        .principal(authenticatedPrincipal()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.spaceCode").value("enterprise-default"))
                .andExpect(jsonPath("$.data.environment").value("test"))
                .andExpect(jsonPath("$.data.agentAppCode").value("finance-agent"))
                .andExpect(jsonPath("$.data.sourceSelectionMode").value("EXCLUSIVE"))
                .andExpect(jsonPath("$.data.allowedSourceIds[0]").value("artifact-catalog"))
                .andExpect(jsonPath("$.data.blockedSourceIds[0]").value("legacy-bridge"));
    }

    @Test
    void shouldReplacePublicationSourcePolicy() throws Exception {
        AgentAppPublicationSourcePolicy updatedPolicy = new AgentAppPublicationSourcePolicy(
                "exclusive",
                List.of("artifact-catalog", "mcp-gateway"),
                List.of("legacy-bridge"));
        when(authorizationService.canManageAgentAppPublicationPolicy(
                any(AuthenticatedUserContext.class), eq("enterprise-default"), eq("prod"), eq("finance-agent")))
                .thenReturn(true);
        when(publicationPolicyService.replacePublicationSourcePolicy(
                eq("enterprise-default"),
                eq("prod"),
                eq("finance-agent"),
                eq(updatedPolicy)))
                .thenReturn(Optional.of(new ResolvedAgentAppPublicationSourcePolicy(
                        9L,
                        "enterprise-default",
                        "prod",
                        7L,
                        "finance-agent",
                        updatedPolicy)));

        mockMvc.perform(put("/api/controlplane/spaces/enterprise-default/agent-apps/finance-agent/publication-source-policy")
                        .principal(authenticatedPrincipal())
                        .contentType("application/json")
                        .content("""
                                {
                                  "sourceSelectionMode": "exclusive",
                                  "allowedSourceIds": ["artifact-catalog", "mcp-gateway"],
                                  "blockedSourceIds": ["legacy-bridge"]
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.sourceSelectionMode").value("EXCLUSIVE"))
                .andExpect(jsonPath("$.data.allowedSourceIds[1]").value("mcp-gateway"));

        ArgumentCaptor<AgentAppPublicationSourcePolicy> policyCaptor = ArgumentCaptor.forClass(AgentAppPublicationSourcePolicy.class);
        verify(publicationPolicyService).replacePublicationSourcePolicy(
                eq("enterprise-default"),
                eq("prod"),
                eq("finance-agent"),
                policyCaptor.capture());
        assertEquals("EXCLUSIVE", policyCaptor.getValue().sourceSelectionMode());
        assertEquals(List.of("artifact-catalog", "mcp-gateway"), policyCaptor.getValue().allowedSourceIds());
        assertEquals(List.of("legacy-bridge"), policyCaptor.getValue().blockedSourceIds());
    }

    @Test
    void shouldNormalizePublicationSourcePolicyRequestLists() throws Exception {
        AgentAppPublicationSourcePolicy normalizedPolicy = new AgentAppPublicationSourcePolicy(
                "exclusive",
                List.of("artifact-catalog", "mcp-gateway"),
                List.of("legacy-bridge"));
        when(authorizationService.canManageAgentAppPublicationPolicy(
                any(AuthenticatedUserContext.class), eq("enterprise-default"), eq("prod"), eq("finance-agent")))
                .thenReturn(true);
        when(publicationPolicyService.replacePublicationSourcePolicy(
                eq("enterprise-default"),
                eq("prod"),
                eq("finance-agent"),
                eq(normalizedPolicy)))
                .thenReturn(Optional.of(new ResolvedAgentAppPublicationSourcePolicy(
                        9L,
                        "enterprise-default",
                        "prod",
                        7L,
                        "finance-agent",
                        normalizedPolicy)));

        mockMvc.perform(put("/api/controlplane/spaces/enterprise-default/agent-apps/finance-agent/publication-source-policy")
                        .principal(authenticatedPrincipal())
                        .contentType("application/json")
                        .content("""
                                {
                                  "sourceSelectionMode": " exclusive ",
                                  "allowedSourceIds": [" artifact-catalog ", "", "mcp-gateway", "   "],
                                  "blockedSourceIds": [" legacy-bridge ", null, ""]
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.sourceSelectionMode").value("EXCLUSIVE"))
                .andExpect(jsonPath("$.data.allowedSourceIds.length()").value(2))
                .andExpect(jsonPath("$.data.allowedSourceIds[0]").value("artifact-catalog"))
                .andExpect(jsonPath("$.data.blockedSourceIds.length()").value(1))
                .andExpect(jsonPath("$.data.blockedSourceIds[0]").value("legacy-bridge"));
    }

    @Test
    void shouldReturnForbiddenWhenPublicationPolicyScopeDenied() throws Exception {
        when(authorizationService.canManageAgentAppPublicationPolicy(
                any(AuthenticatedUserContext.class), eq("enterprise-default"), eq("prod"), eq("finance-agent")))
                .thenReturn(false);

        mockMvc.perform(get("/api/controlplane/spaces/enterprise-default/agent-apps/finance-agent/publication-source-policy")
                        .principal(authenticatedPrincipal()))
                .andExpect(status().isForbidden());

        verify(publicationPolicyService, never()).getPublicationSourcePolicy(eq("enterprise-default"), eq("prod"), eq("finance-agent"));
    }

    @Test
    void shouldReturnNotFoundWhenResolvedAgentAppMissing() throws Exception {
        when(authorizationService.canManageAgentAppPublicationPolicy(
                any(AuthenticatedUserContext.class), eq("enterprise-default"), eq("prod"), eq("finance-agent")))
                .thenReturn(true);
        when(publicationPolicyService.getPublicationSourcePolicy(eq("enterprise-default"), eq("prod"), eq("finance-agent")))
                .thenReturn(Optional.empty());

        mockMvc.perform(get("/api/controlplane/spaces/enterprise-default/agent-apps/finance-agent/publication-source-policy")
                        .principal(authenticatedPrincipal()))
                .andExpect(status().isNotFound());
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
