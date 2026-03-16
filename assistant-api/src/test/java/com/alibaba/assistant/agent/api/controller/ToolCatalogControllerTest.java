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
import com.alibaba.assistant.agent.controlplane.toolregistry.ResolvedToolMetaDetailView;
import com.alibaba.assistant.agent.controlplane.toolregistry.ToolMetaCatalogService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.security.Principal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class ToolCatalogControllerTest {

    private MockMvc mockMvc;

    @Mock
    private ToolMetaCatalogService toolMetaCatalogService;

    @Mock
    private MigrationControlPlaneAuthorizationService authorizationService;

    @BeforeEach
    void setUp() {
        ToolCatalogController controller = new ToolCatalogController(toolMetaCatalogService, authorizationService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void shouldGetToolDetail() throws Exception {
        when(authorizationService.canViewSpaceCatalog(any(AuthenticatedUserContext.class), eq("finance-space"), eq("prod")))
                .thenReturn(true);
        when(toolMetaCatalogService.getToolDetail("finance-space", "prod", "gougu_oa.leave_application"))
                .thenReturn(Optional.of(new ResolvedToolMetaDetailView(
                        "finance-space",
                        "prod",
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
                        "enabled",
                        1)));

        mockMvc.perform(get("/api/controlplane/spaces/finance-space/tools/gougu_oa.leave_application")
                        .principal(authenticatedPrincipal()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.toolCode").value("gougu_oa.leave_application"))
                .andExpect(jsonPath("$.data.toolType").value("ACTION"))
                .andExpect(jsonPath("$.data.visibility").value("USER"))
                .andExpect(jsonPath("$.data.apiEndpoint").value("/home/leaves/add"));
    }

    @Test
    void shouldRejectToolDetailWhenUnauthorized() throws Exception {
        when(authorizationService.canViewSpaceCatalog(any(AuthenticatedUserContext.class), eq("finance-space"), eq("prod")))
                .thenReturn(false);

        mockMvc.perform(get("/api/controlplane/spaces/finance-space/tools/gougu_oa.leave_application")
                        .principal(authenticatedPrincipal()))
                .andExpect(status().isForbidden());

        verify(toolMetaCatalogService, never()).getToolDetail("finance-space", "prod", "gougu_oa.leave_application");
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
