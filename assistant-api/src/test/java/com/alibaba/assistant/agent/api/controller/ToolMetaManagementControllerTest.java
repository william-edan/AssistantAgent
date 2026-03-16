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
import com.alibaba.assistant.agent.controlplane.toolregistry.ResolvedToolMetaManagementView;
import com.alibaba.assistant.agent.controlplane.toolregistry.ToolMetaManagementService;
import com.alibaba.assistant.agent.controlplane.toolregistry.ToolMetaUpsertCommand;
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
class ToolMetaManagementControllerTest {

    private MockMvc mockMvc;

    @Mock
    private ToolMetaManagementService toolMetaManagementService;

    @Mock
    private MigrationControlPlaneAuthorizationService authorizationService;

    @BeforeEach
    void setUp() {
        ToolMetaManagementController controller =
                new ToolMetaManagementController(toolMetaManagementService, authorizationService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void shouldListManagedTools() throws Exception {
        when(authorizationService.canManageSpaceCatalog(any(AuthenticatedUserContext.class), eq("enterprise-default"), eq("prod")))
                .thenReturn(true);
        when(toolMetaManagementService.listTools("enterprise-default", "prod", "oa-core", "leave"))
                .thenReturn(List.of(toolView()));

        mockMvc.perform(get("/api/controlplane/spaces/enterprise-default/connectors/oa-core/tools/manage")
                        .param("keyword", "leave")
                        .principal(authenticatedPrincipal()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.tools.length()").value(1))
                .andExpect(jsonPath("$.data.tools[0].toolCode").value("gougu_oa.leave_application"))
                .andExpect(jsonPath("$.data.tools[0].toolType").value("ACTION"))
                .andExpect(jsonPath("$.data.tools[0].visibility").value("USER"));
    }

    @Test
    void shouldGetManagedTool() throws Exception {
        when(authorizationService.canManageSpaceCatalog(any(AuthenticatedUserContext.class), eq("enterprise-default"), eq("prod")))
                .thenReturn(true);
        when(toolMetaManagementService.getTool("enterprise-default", "prod", "oa-core", "gougu_oa.leave_application"))
                .thenReturn(Optional.of(toolView()));

        mockMvc.perform(get("/api/controlplane/spaces/enterprise-default/connectors/oa-core/tools/gougu_oa.leave_application")
                        .principal(authenticatedPrincipal()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.toolCode").value("gougu_oa.leave_application"))
                .andExpect(jsonPath("$.data.systemCode").value("gougu_oa"))
                .andExpect(jsonPath("$.data.invocationPolicy").value("DIRECT"));
    }

    @Test
    void shouldUpsertManagedTool() throws Exception {
        when(authorizationService.canManageSpaceCatalog(any(AuthenticatedUserContext.class), eq("enterprise-default"), eq("test")))
                .thenReturn(true);
        when(toolMetaManagementService.upsertTool(
                eq("enterprise-default"),
                eq("test"),
                eq("oa-core"),
                eq("gougu_oa.leave_application"),
                any(ToolMetaUpsertCommand.class)))
                .thenReturn(Optional.of(toolView()));

        mockMvc.perform(put("/api/controlplane/spaces/enterprise-default/connectors/oa-core/tools/gougu_oa.leave_application")
                        .param("environment", "test")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  \"toolName\": \"请假审批\",
                                  \"toolType\": \"ACTION\",
                                  \"visibility\": \"USER\",
                                  \"invocationPolicy\": \"DIRECT\",
                                  \"executionMode\": \"SYNC\",
                                  \"parameterSchema\": {\"type\": \"object\", \"properties\": {}},
                                  \"apiEndpoint\": \"/home/leaves/add\",
                                  \"httpMethod\": \"POST\",
                                  \"contentType\": \"application/x-www-form-urlencoded\",
                                  \"riskLevel\": \"high\",
                                  \"requiresAuth\": true,
                                  \"requiresConfirm\": true,
                                  \"capabilityType\": \"ACTION\",
                                  \"status\": \"enabled\"
                                }
                                """)
                        .principal(authenticatedPrincipal()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.toolCode").value("gougu_oa.leave_application"))
                .andExpect(jsonPath("$.data.riskLevel").value("LOW"))
                .andExpect(jsonPath("$.data.executionMode").value("SYNC"));

        ArgumentCaptor<ToolMetaUpsertCommand> commandCaptor = ArgumentCaptor.forClass(ToolMetaUpsertCommand.class);
        verify(toolMetaManagementService).upsertTool(
                eq("enterprise-default"),
                eq("test"),
                eq("oa-core"),
                eq("gougu_oa.leave_application"),
                commandCaptor.capture());
        assertEquals("ACTION", commandCaptor.getValue().toolType());
        assertEquals("USER", commandCaptor.getValue().visibility());
        assertEquals("DIRECT", commandCaptor.getValue().invocationPolicy());
        assertEquals("/home/leaves/add", commandCaptor.getValue().apiEndpoint());
    }

    @Test
    void shouldReturnForbiddenWhenManageScopeDenied() throws Exception {
        when(authorizationService.canManageSpaceCatalog(any(AuthenticatedUserContext.class), eq("enterprise-default"), eq("prod")))
                .thenReturn(false);

        mockMvc.perform(get("/api/controlplane/spaces/enterprise-default/connectors/oa-core/tools/manage")
                        .principal(authenticatedPrincipal()))
                .andExpect(status().isForbidden());

        verify(toolMetaManagementService, never()).listTools("enterprise-default", "prod", "oa-core", null);
    }

    private ResolvedToolMetaManagementView toolView() {
        return new ResolvedToolMetaManagementView(
                31L,
                "enterprise-default",
                "prod",
                "oa-core",
                "gougu_oa.leave_application",
                "请假审批",
                "提交请假申请",
                "gougu_oa",
                "ACTION",
                "USER",
                "DIRECT",
                "SYNC",
                "/home/leaves/add",
                "POST",
                "application/x-www-form-urlencoded",
                Map.of("type", "object"),
                Map.of("steps", Map.of("invoke", Map.of("type", "HTTP"))),
                Map.of("toolType", "ACTION", "visibility", "USER", "invocationPolicy", "DIRECT", "executionMode", "SYNC"),
                "LOW",
                true,
                true,
                "ACTION",
                1,
                "enabled");
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
