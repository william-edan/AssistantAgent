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
import com.alibaba.assistant.agent.controlplane.catalog.ResolvedWorkflowDetailView;
import com.alibaba.assistant.agent.controlplane.catalog.ResolvedWorkflowStepDetailView;
import com.alibaba.assistant.agent.controlplane.catalog.WorkflowCatalogService;
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
class WorkflowCatalogControllerTest {

    private MockMvc mockMvc;

    @Mock
    private WorkflowCatalogService workflowCatalogService;

    @Mock
    private MigrationControlPlaneAuthorizationService authorizationService;

    @BeforeEach
    void setUp() {
        WorkflowCatalogController controller = new WorkflowCatalogController(workflowCatalogService, authorizationService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void shouldGetWorkflowDetail() throws Exception {
        when(authorizationService.canViewSpaceCatalog(any(AuthenticatedUserContext.class), eq("finance-space"), eq("prod")))
                .thenReturn(true);
        when(workflowCatalogService.getWorkflowDetail("finance-space", "prod", "oa.leave.apply"))
                .thenReturn(Optional.of(new ResolvedWorkflowDetailView(
                        "finance-space",
                        "prod",
                        "oa.leave.apply",
                        "请假申请",
                        61L,
                        "enabled",
                        5,
                        List.of(new ResolvedWorkflowStepDetailView(
                                "create_leave",
                                "创建请假记录",
                                "HTTP",
                                21L,
                                "/home/leaves/add",
                                1,
                                null,
                                null)))));

        mockMvc.perform(get("/api/controlplane/spaces/finance-space/workflows/oa.leave.apply")
                        .principal(authenticatedPrincipal()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.workflowCode").value("oa.leave.apply"))
                .andExpect(jsonPath("$.data.displayName").value("请假申请"))
                .andExpect(jsonPath("$.data.steps[0].stepId").value("create_leave"))
                .andExpect(jsonPath("$.data.steps[0].connectorId").value(21));
    }

    @Test
    void shouldRejectWorkflowDetailWhenUnauthorized() throws Exception {
        when(authorizationService.canViewSpaceCatalog(any(AuthenticatedUserContext.class), eq("finance-space"), eq("prod")))
                .thenReturn(false);

        mockMvc.perform(get("/api/controlplane/spaces/finance-space/workflows/oa.leave.apply")
                        .principal(authenticatedPrincipal()))
                .andExpect(status().isForbidden());

        verify(workflowCatalogService, never()).getWorkflowDetail("finance-space", "prod", "oa.leave.apply");
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
