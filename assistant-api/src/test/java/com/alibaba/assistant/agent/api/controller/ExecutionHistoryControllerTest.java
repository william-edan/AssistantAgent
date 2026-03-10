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
import com.alibaba.assistant.agent.execution.persistence.ExecutionHistoryDetailView;
import com.alibaba.assistant.agent.execution.persistence.ExecutionHistoryService;
import com.alibaba.assistant.agent.execution.persistence.ExecutionStepView;
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
class ExecutionHistoryControllerTest {

    private MockMvc mockMvc;

    @Mock
    private ExecutionHistoryService executionHistoryService;

    @Mock
    private PlatformSpaceService platformSpaceService;

    @Mock
    private MigrationControlPlaneAuthorizationService authorizationService;

    @BeforeEach
    void setUp() {
        ExecutionHistoryController controller = new ExecutionHistoryController(
                executionHistoryService,
                platformSpaceService,
                authorizationService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void shouldGetExecutionRunDetailWhenAuthorized() throws Exception {
        PlatformSpace space = new PlatformSpace();
        space.setId(11L);
        space.setSpaceCode("finance-space");
        space.setEnvironment("prod");
        when(executionHistoryService.findDetailByRunId("RUN-1")).thenReturn(Optional.of(new ExecutionHistoryDetailView(
                "RUN-1",
                "oa.leave.apply",
                "WORKFLOW",
                11L,
                "u1001",
                "T-1",
                "COMPLETED",
                LocalDateTime.of(2026, 3, 10, 12, 0),
                LocalDateTime.of(2026, 3, 10, 12, 1),
                List.of(new ExecutionStepView(
                        "create_leave",
                        "创建请假记录",
                        21L,
                        "oa_user_delegated",
                        "COMPLETED",
                        null,
                        LocalDateTime.of(2026, 3, 10, 12, 0),
                        LocalDateTime.of(2026, 3, 10, 12, 0, 10))))));
        when(platformSpaceService.getById(11L)).thenReturn(space);
        when(authorizationService.canViewSpaceCatalog(any(AuthenticatedUserContext.class), eq("finance-space"), eq("prod")))
                .thenReturn(true);

        mockMvc.perform(get("/api/controlplane/execution-runs/RUN-1")
                        .principal(authenticatedPrincipal()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.runId").value("RUN-1"))
                .andExpect(jsonPath("$.data.spaceCode").value("finance-space"))
                .andExpect(jsonPath("$.data.steps.length()").value(1))
                .andExpect(jsonPath("$.data.steps[0].stepId").value("create_leave"));
    }

    @Test
    void shouldRejectExecutionRunDetailWhenUnauthorized() throws Exception {
        PlatformSpace space = new PlatformSpace();
        space.setId(11L);
        space.setSpaceCode("finance-space");
        space.setEnvironment("prod");
        when(executionHistoryService.findDetailByRunId("RUN-1")).thenReturn(Optional.of(new ExecutionHistoryDetailView(
                "RUN-1",
                "oa.leave.apply",
                "WORKFLOW",
                11L,
                "u1001",
                "T-1",
                "COMPLETED",
                LocalDateTime.of(2026, 3, 10, 12, 0),
                LocalDateTime.of(2026, 3, 10, 12, 1),
                List.of())));
        when(platformSpaceService.getById(11L)).thenReturn(space);
        when(authorizationService.canViewSpaceCatalog(any(AuthenticatedUserContext.class), eq("finance-space"), eq("prod")))
                .thenReturn(false);

        mockMvc.perform(get("/api/controlplane/execution-runs/RUN-1")
                        .principal(authenticatedPrincipal()))
                .andExpect(status().isForbidden());

        verify(executionHistoryService).findDetailByRunId("RUN-1");
    }

    @Test
    void shouldReturnNotFoundWhenExecutionRunMissing() throws Exception {
        when(executionHistoryService.findDetailByRunId("RUN-404")).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/controlplane/execution-runs/RUN-404")
                        .principal(authenticatedPrincipal()))
                .andExpect(status().isNotFound());

        verify(platformSpaceService, never()).getById(any());
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
