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
import com.alibaba.assistant.agent.controlplane.space.PlatformSpace;
import com.alibaba.assistant.agent.controlplane.space.PlatformSpaceService;
import com.alibaba.assistant.agent.execution.persistence.ExecutionHistoryRunSummaryView;
import com.alibaba.assistant.agent.execution.persistence.ExecutionHistoryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.security.Principal;
import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class ExecutionRunListControllerTest {

    private MockMvc mockMvc;

    @Mock
    private ExecutionHistoryService executionHistoryService;

    @Mock
    private PlatformSpaceService platformSpaceService;

    @Mock
    private MigrationControlPlaneAuthorizationService authorizationService;

    @BeforeEach
    void setUp() {
        ExecutionRunListController controller = new ExecutionRunListController(
                executionHistoryService,
                platformSpaceService,
                authorizationService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void shouldListExecutionRunsForSpaceWhenAuthorized() throws Exception {
        PlatformSpace space = new PlatformSpace();
        space.setId(11L);
        space.setSpaceCode("finance-space");
        space.setEnvironment("test");
        when(platformSpaceService.findActiveByCode("finance-space", "test")).thenReturn(java.util.Optional.of(space));
        when(authorizationService.canViewSpaceCatalog(any(AuthenticatedUserContext.class), eq("finance-space"), eq("test")))
                .thenReturn(true);
        when(executionHistoryService.listRuns(11L, null, "WAITING_APPROVAL", "oa.leave.apply", null, null, null, null, 10))
                .thenReturn(List.of(new ExecutionHistoryRunSummaryView(
                        "RUN-1",
                        "oa.leave.apply",
                        "WORKFLOW",
                        11L,
                        "u1001",
                        "T-1",
                        "WAITING_APPROVAL",
                        "submit_approval",
                        "RUN-1:submit_approval",
                        LocalDateTime.of(2026, 3, 11, 10, 0),
                        null)));

        mockMvc.perform(get("/api/controlplane/spaces/finance-space/execution-runs")
                        .param("environment", "test")
                        .param("status", "WAITING_APPROVAL")
                        .param("artifactCode", "oa.leave.apply")
                        .param("limit", "10")
                        .principal(authenticatedPrincipal()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.runs.length()").value(1))
                .andExpect(jsonPath("$.data.runs[0].runId").value("RUN-1"))
                .andExpect(jsonPath("$.data.runs[0].status").value("WAITING_APPROVAL"))
                .andExpect(jsonPath("$.data.runs[0].spaceCode").value("finance-space"))
                .andExpect(jsonPath("$.data.runs[0].approvalRequestId").value("RUN-1:submit_approval"));
    }

    @Test
    void shouldPassIdentityThreadAndTimeFiltersToExecutionRunList() throws Exception {
        PlatformSpace space = new PlatformSpace();
        space.setId(11L);
        space.setSpaceCode("finance-space");
        space.setEnvironment("prod");
        LocalDateTime startedAfter = LocalDateTime.of(2026, 3, 11, 12, 0);
        LocalDateTime startedBefore = LocalDateTime.of(2026, 3, 11, 13, 0);
        when(platformSpaceService.findActiveByCode("finance-space", "prod")).thenReturn(java.util.Optional.of(space));
        when(authorizationService.canViewSpaceCatalog(any(AuthenticatedUserContext.class), eq("finance-space"), eq("prod")))
                .thenReturn(true);
        when(executionHistoryService.listRuns(
                11L,
                "RUN-2",
                "COMPLETED",
                "oa.leave.apply",
                "u2002",
                "THREAD-9",
                startedAfter,
                startedBefore,
                5))
                .thenReturn(List.of(new ExecutionHistoryRunSummaryView(
                        "RUN-2",
                        "oa.leave.apply",
                        "WORKFLOW",
                        11L,
                        "u2002",
                        "THREAD-9",
                        "COMPLETED",
                        null,
                        null,
                        LocalDateTime.of(2026, 3, 11, 12, 15),
                        LocalDateTime.of(2026, 3, 11, 12, 18))));

        mockMvc.perform(get("/api/controlplane/spaces/finance-space/execution-runs")
                        .param("runId", "RUN-2")
                        .param("status", "COMPLETED")
                        .param("artifactCode", "oa.leave.apply")
                        .param("platformPrincipalId", "u2002")
                        .param("threadId", "THREAD-9")
                        .param("startedAfter", "2026-03-11T12:00:00")
                        .param("startedBefore", "2026-03-11T13:00:00")
                        .param("limit", "5")
                        .principal(authenticatedPrincipal()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.runs[0].runId").value("RUN-2"))
                .andExpect(jsonPath("$.data.runs[0].platformPrincipalId").value("u2002"))
                .andExpect(jsonPath("$.data.runs[0].threadId").value("THREAD-9"));
    }

    @Test
    void shouldReturnForbiddenWhenExecutionRunListScopeDenied() throws Exception {
        PlatformSpace space = new PlatformSpace();
        space.setId(11L);
        space.setSpaceCode("finance-space");
        space.setEnvironment("prod");
        when(platformSpaceService.findActiveByCode("finance-space", "prod")).thenReturn(java.util.Optional.of(space));
        when(authorizationService.canViewSpaceCatalog(any(AuthenticatedUserContext.class), eq("finance-space"), eq("prod")))
                .thenReturn(false);

        mockMvc.perform(get("/api/controlplane/spaces/finance-space/execution-runs")
                        .principal(authenticatedPrincipal()))
                .andExpect(status().isForbidden());

        verify(executionHistoryService, never()).listRuns(11L, null, null, null, null, null, null, null, 20);
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
