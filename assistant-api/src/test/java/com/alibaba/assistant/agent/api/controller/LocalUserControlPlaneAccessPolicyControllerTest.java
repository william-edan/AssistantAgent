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
import com.alibaba.assistant.agent.controlplane.identity.LocalUserControlPlaneAccessPolicy;
import com.alibaba.assistant.agent.controlplane.identity.LocalUserControlPlaneAccessPolicyService;
import com.alibaba.assistant.agent.controlplane.identity.ResolvedLocalUserControlPlaneAccessPolicy;
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
class LocalUserControlPlaneAccessPolicyControllerTest {

    @Mock
    private LocalUserControlPlaneAccessPolicyService policyService;

    @Mock
    private MigrationControlPlaneAuthorizationService authorizationService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        LocalUserControlPlaneAccessPolicyController controller = new LocalUserControlPlaneAccessPolicyController(
                policyService,
                authorizationService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void shouldGetLocalUserControlPlaneAccessPolicy() throws Exception {
        when(authorizationService.canManageLocalUserControlPlaneAccessPolicy(any(AuthenticatedUserContext.class), eq("enterprise-default"), eq("prod")))
                .thenReturn(true);
        when(policyService.getPolicy("enterprise-default", "prod", 1001L))
                .thenReturn(Optional.of(new ResolvedLocalUserControlPlaneAccessPolicy(
                        1001L,
                        "admin",
                        "管理员",
                        9L,
                        "enterprise-default",
                        "prod",
                        new LocalUserControlPlaneAccessPolicy(true, List.of("finance-agent")))));

        mockMvc.perform(get("/api/controlplane/spaces/enterprise-default/local-users/1001/controlplane-access-policy")
                        .principal(authenticatedPrincipal()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.localUserId").value(1001))
                .andExpect(jsonPath("$.data.spaceAdmin").value(true))
                .andExpect(jsonPath("$.data.agentAppAdminCodes[0]").value("finance-agent"));
    }

    @Test
    void shouldReplaceLocalUserControlPlaneAccessPolicy() throws Exception {
        when(authorizationService.canManageLocalUserControlPlaneAccessPolicy(any(AuthenticatedUserContext.class), eq("enterprise-default"), eq("prod")))
                .thenReturn(true);
        when(policyService.replacePolicy(
                eq("enterprise-default"),
                eq("prod"),
                eq(1001L),
                eq(new LocalUserControlPlaneAccessPolicy(true, List.of("finance-agent", "hr-agent")))))
                .thenReturn(Optional.of(new ResolvedLocalUserControlPlaneAccessPolicy(
                        1001L,
                        "admin",
                        "管理员",
                        9L,
                        "enterprise-default",
                        "prod",
                        new LocalUserControlPlaneAccessPolicy(true, List.of("finance-agent", "hr-agent")))));

        mockMvc.perform(put("/api/controlplane/spaces/enterprise-default/local-users/1001/controlplane-access-policy")
                        .principal(authenticatedPrincipal())
                        .contentType("application/json")
                        .content("""
                                {
                                  "spaceAdmin": true,
                                  "agentAppAdminCodes": [" finance-agent ", "hr-agent", "finance-agent", ""]
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.spaceAdmin").value(true))
                .andExpect(jsonPath("$.data.agentAppAdminCodes.length()").value(2));

        ArgumentCaptor<LocalUserControlPlaneAccessPolicy> policyCaptor = ArgumentCaptor.forClass(LocalUserControlPlaneAccessPolicy.class);
        verify(policyService).replacePolicy(eq("enterprise-default"), eq("prod"), eq(1001L), policyCaptor.capture());
        assertEquals(List.of("finance-agent", "hr-agent"), policyCaptor.getValue().agentAppAdminCodes());
    }

    @Test
    void shouldReturnForbiddenWhenScopeDenied() throws Exception {
        when(authorizationService.canManageLocalUserControlPlaneAccessPolicy(any(AuthenticatedUserContext.class), eq("enterprise-default"), eq("prod")))
                .thenReturn(false);

        mockMvc.perform(get("/api/controlplane/spaces/enterprise-default/local-users/1001/controlplane-access-policy")
                        .principal(authenticatedPrincipal()))
                .andExpect(status().isForbidden());

        verify(policyService, never()).getPolicy(eq("enterprise-default"), eq("prod"), eq(1001L));
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
                List.of("assistant:chat"));
    }
}
