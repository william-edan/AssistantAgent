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

import com.alibaba.assistant.agent.api.controlplane.ControlPlaneExecutionOverview;
import com.alibaba.assistant.agent.api.controlplane.ControlPlaneExecutionOverviewService;
import com.alibaba.assistant.agent.api.security.AuthenticatedUserContext;
import com.alibaba.assistant.agent.api.security.MigrationControlPlaneAuthorizationService;
import com.alibaba.assistant.agent.controlplane.catalog.ControlPlaneCatalogOverview;
import com.alibaba.assistant.agent.controlplane.catalog.ControlPlaneCatalogService;
import com.alibaba.assistant.agent.controlplane.catalog.ResolvedAgentAppSummaryView;
import com.alibaba.assistant.agent.controlplane.catalog.ResolvedConnectorSummaryView;
import com.alibaba.assistant.agent.controlplane.catalog.ResolvedPlatformSpaceView;
import com.alibaba.assistant.agent.controlplane.toolregistry.ResolvedToolMetaSummaryView;
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
class ControlPlaneCatalogControllerTest {

    private MockMvc mockMvc;

    @Mock
    private ControlPlaneCatalogService controlPlaneCatalogService;

    @Mock
    private ControlPlaneExecutionOverviewService controlPlaneExecutionOverviewService;

    @Mock
    private MigrationControlPlaneAuthorizationService authorizationService;

    @BeforeEach
    void setUp() {
        ControlPlaneCatalogController controller = new ControlPlaneCatalogController(
                controlPlaneCatalogService,
                controlPlaneExecutionOverviewService,
                authorizationService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void shouldListOnlyAuthorizedSpaces() throws Exception {
        when(controlPlaneCatalogService.listSpaces("prod", "finance")).thenReturn(List.of(
                new ResolvedPlatformSpaceView(11L, "finance-space", "Finance Space", "prod", "active"),
                new ResolvedPlatformSpaceView(12L, "hr-space", "HR Space", "prod", "active")));
        when(authorizationService.canViewSpaceCatalog(any(AuthenticatedUserContext.class), eq("finance-space"), eq("prod")))
                .thenReturn(true);
        when(authorizationService.canViewSpaceCatalog(any(AuthenticatedUserContext.class), eq("hr-space"), eq("prod")))
                .thenReturn(false);

        mockMvc.perform(get("/api/controlplane/spaces")
                        .param("environment", "prod")
                        .param("keyword", "finance")
                        .principal(authenticatedPrincipal()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.spaces.length()").value(1))
                .andExpect(jsonPath("$.data.spaces[0].spaceCode").value("finance-space"))
                .andExpect(jsonPath("$.data.spaces[0].spaceName").value("Finance Space"));
    }

    @Test
    void shouldGetSpaceCatalogOverview() throws Exception {
        when(authorizationService.canViewSpaceCatalog(any(AuthenticatedUserContext.class), eq("finance-space"), eq("prod")))
                .thenReturn(true);
        when(controlPlaneCatalogService.getOverview("finance-space", "prod", "oa")).thenReturn(Optional.of(
                new ControlPlaneCatalogOverview(
                        new ResolvedPlatformSpaceView(11L, "finance-space", "Finance Space", "prod", "active"),
                        List.of(new ResolvedConnectorSummaryView(21L, "oa-core", "gougu_oa", "OA Core", "openapi", "active", 2)),
                        List.of(new ResolvedAgentAppSummaryView(31L, "finance-agent", "Finance Agent", "active")),
                        List.of(new ResolvedToolMetaSummaryView(
                                41L,
                                "gougu_oa.leave_application",
                                "请假审批",
                                "gougu_oa",
                                "ACTION",
                                "USER",
                                "DIRECT",
                                "SYNC",
                                "LOW",
                                true,
                                "enabled",
                                3)))));

        mockMvc.perform(get("/api/controlplane/spaces/finance-space/catalog-overview")
                        .param("environment", "prod")
                        .param("keyword", "oa")
                        .principal(authenticatedPrincipal()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.space.spaceCode").value("finance-space"))
                .andExpect(jsonPath("$.data.connectors.length()").value(1))
                .andExpect(jsonPath("$.data.connectors[0].connectorCode").value("oa-core"))
                .andExpect(jsonPath("$.data.agentApps[0].agentAppCode").value("finance-agent"))
                .andExpect(jsonPath("$.data.tools[0].toolCode").value("gougu_oa.leave_application"))
                .andExpect(jsonPath("$.data.tools[0].toolType").value("ACTION"))
                .andExpect(jsonPath("$.data.tools[0].visibility").value("USER"));
    }

    @Test
    void shouldRejectCatalogOverviewWhenUnauthorized() throws Exception {
        when(authorizationService.canViewSpaceCatalog(any(AuthenticatedUserContext.class), eq("finance-space"), eq("prod")))
                .thenReturn(false);

        mockMvc.perform(get("/api/controlplane/spaces/finance-space/catalog-overview")
                        .principal(authenticatedPrincipal()))
                .andExpect(status().isForbidden());

        verify(controlPlaneCatalogService, never()).getOverview("finance-space", "prod", null);
    }

    @Test
    void shouldGetExecutionOverviewWhenApprovalAccessGranted() throws Exception {
        when(authorizationService.canViewSpaceCatalog(any(AuthenticatedUserContext.class), eq("finance-space"), eq("prod")))
                .thenReturn(true);
        when(authorizationService.canManageSpaceExecutionApprovals(any(AuthenticatedUserContext.class), eq("finance-space"), eq("prod")))
                .thenReturn(true);
        when(controlPlaneExecutionOverviewService.getOverview("finance-space", "prod", 3, 2, true))
                .thenReturn(Optional.of(new ControlPlaneExecutionOverview(
                        "finance-space",
                        "prod",
                        new ControlPlaneExecutionOverview.Summary(1, 1, true),
                        List.of(),
                        List.of())));

        mockMvc.perform(get("/api/controlplane/spaces/finance-space/execution-overview")
                        .param("recentRunLimit", "3")
                        .param("pendingApprovalLimit", "2")
                        .principal(authenticatedPrincipal()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.spaceCode").value("finance-space"))
                .andExpect(jsonPath("$.data.summary.recentRunCount").value(1))
                .andExpect(jsonPath("$.data.summary.pendingApprovalCount").value(1))
                .andExpect(jsonPath("$.data.summary.approvalAccess").value(true));
    }

    @Test
    void shouldReturnExecutionOverviewWithoutPendingApprovalsWhenApprovalAccessDenied() throws Exception {
        when(authorizationService.canViewSpaceCatalog(any(AuthenticatedUserContext.class), eq("finance-space"), eq("prod")))
                .thenReturn(true);
        when(authorizationService.canManageSpaceExecutionApprovals(any(AuthenticatedUserContext.class), eq("finance-space"), eq("prod")))
                .thenReturn(false);
        when(controlPlaneExecutionOverviewService.getOverview("finance-space", "prod", null, null, false))
                .thenReturn(Optional.of(new ControlPlaneExecutionOverview(
                        "finance-space",
                        "prod",
                        new ControlPlaneExecutionOverview.Summary(0, 0, false),
                        List.of(),
                        List.of())));

        mockMvc.perform(get("/api/controlplane/spaces/finance-space/execution-overview")
                        .principal(authenticatedPrincipal()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.summary.approvalAccess").value(false))
                .andExpect(jsonPath("$.data.pendingApprovals.length()").value(0));
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
