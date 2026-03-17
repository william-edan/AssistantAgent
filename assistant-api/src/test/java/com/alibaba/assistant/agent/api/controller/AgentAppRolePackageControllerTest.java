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
import com.alibaba.assistant.agent.controlplane.rolepackage.ResolvedRolePackageManagementView;
import com.alibaba.assistant.agent.controlplane.rolepackage.RolePackageManagementService;
import com.alibaba.assistant.agent.controlplane.rolepackage.RolePackageUpsertCommand;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class AgentAppRolePackageControllerTest {

    private MockMvc mockMvc;

    @Mock
    private RolePackageManagementService rolePackageManagementService;

    @Mock
    private MigrationControlPlaneAuthorizationService authorizationService;

    @BeforeEach
    void setUp() {
        AgentAppRolePackageController controller = new AgentAppRolePackageController(
                rolePackageManagementService,
                authorizationService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void shouldUpsertAndPublishRolePackageViaAgentAppScopedApi() throws Exception {
        when(authorizationService.canManageAgentAppRolePackages(
                any(AuthenticatedUserContext.class), eq("enterprise-default"), eq("test"), eq("finance-agent")))
                .thenReturn(true);
        when(rolePackageManagementService.upsertRolePackage(
                eq("enterprise-default"),
                eq("test"),
                eq("finance-agent"),
                eq("digital-admin"),
                any(RolePackageUpsertCommand.class)))
                .thenReturn(Optional.of(rolePackageView("DRAFT")));
        when(rolePackageManagementService.publishRolePackage(
                "enterprise-default",
                "test",
                "finance-agent",
                "digital-admin",
                "v1"))
                .thenReturn(Optional.of(rolePackageView("PUBLISHED")));

        mockMvc.perform(put("/api/controlplane/spaces/enterprise-default/agent-apps/finance-agent/role-packages/digital-admin")
                        .param("environment", "test")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "displayName": "数字行政助理",
                                  "persona": "负责审批、排期和通知。",
                                  "version": "v1",
                                  "status": "draft",
                                  "scenarios": [
                                    {
                                      "scenarioCode": "leave-approval",
                                      "displayName": "请假审批",
                                      "description": "处理请假审批",
                                      "routingHints": {"intent": "leave"}
                                    }
                                  ],
                                  "toolScopes": [
                                    {
                                      "toolCode": "gougu_oa.leave_application",
                                      "scopeMode": "required"
                                    }
                                  ]
                                }
                                """)
                        .principal(authenticatedPrincipal()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.roleCode").value("digital-admin"))
                .andExpect(jsonPath("$.data.status").value("DRAFT"));

        mockMvc.perform(post("/api/controlplane/spaces/enterprise-default/agent-apps/finance-agent/role-packages/digital-admin/publish")
                        .param("environment", "test")
                        .param("version", "v1")
                        .principal(authenticatedPrincipal()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.roleCode").value("digital-admin"))
                .andExpect(jsonPath("$.data.status").value("PUBLISHED"));

        ArgumentCaptor<RolePackageUpsertCommand> commandCaptor = ArgumentCaptor.forClass(RolePackageUpsertCommand.class);
        verify(rolePackageManagementService).upsertRolePackage(
                eq("enterprise-default"),
                eq("test"),
                eq("finance-agent"),
                eq("digital-admin"),
                commandCaptor.capture());
        assertEquals("v1", commandCaptor.getValue().version());
        assertEquals(1, commandCaptor.getValue().scenarios().size());
        assertEquals(1, commandCaptor.getValue().toolScopes().size());
    }

    private ResolvedRolePackageManagementView rolePackageView(String status) {
        return new ResolvedRolePackageManagementView(
                101L,
                "enterprise-default",
                "test",
                "finance-agent",
                "digital-admin",
                "数字行政助理",
                "负责审批、排期和通知。",
                "v1",
                status,
                List.of(new ResolvedRolePackageManagementView.RoleScenarioView(
                        "leave-approval",
                        "请假审批",
                        "处理请假审批",
                        Map.of("intent", "leave"))),
                List.of(new ResolvedRolePackageManagementView.RoleToolScopeView(
                        null,
                        "gougu_oa.leave_application",
                        "REQUIRED")),
                List.of(),
                List.of());
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
