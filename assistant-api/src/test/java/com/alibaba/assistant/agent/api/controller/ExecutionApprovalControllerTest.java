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
import com.alibaba.assistant.agent.runtime.execution.ExecutionApprovalDecisionView;
import com.alibaba.assistant.agent.runtime.execution.ExecutionApprovalRequestView;
import com.alibaba.assistant.agent.runtime.execution.ExecutionApprovalService;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class ExecutionApprovalControllerTest {

    private MockMvc mockMvc;

    @Mock
    private ExecutionApprovalService executionApprovalService;

    @Mock
    private MigrationControlPlaneAuthorizationService authorizationService;

    @BeforeEach
    void setUp() {
        ExecutionApprovalController controller = new ExecutionApprovalController(
                executionApprovalService,
                authorizationService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void shouldListPendingApprovalRequestsWhenAuthorized() throws Exception {
        when(authorizationService.canManageSpaceExecutionApprovals(
                any(AuthenticatedUserContext.class), eq("finance-space"), eq("prod")))
                .thenReturn(true);
        when(executionApprovalService.listPendingRequests("finance-space", "prod"))
                .thenReturn(List.of(new ExecutionApprovalRequestView(
                        "REQ-1",
                        "RUN-1",
                        "oa.leave.apply",
                        "WORKFLOW",
                        11L,
                        "finance-space",
                        "prod",
                        "submit_approval",
                        "WAITING_APPROVAL",
                        "manual",
                        "u2001",
                        "submit_approval",
                        "u1001",
                        "T-1",
                        LocalDateTime.of(2026, 3, 10, 23, 0),
                        null)));

        mockMvc.perform(get("/api/controlplane/spaces/finance-space/approval-requests")
                        .principal(authenticatedPrincipal()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.requests.length()").value(1))
                .andExpect(jsonPath("$.data.requests[0].requestId").value("REQ-1"))
                .andExpect(jsonPath("$.data.requests[0].runId").value("RUN-1"))
                .andExpect(jsonPath("$.data.requests[0].spaceCode").value("finance-space"))
                .andExpect(jsonPath("$.data.requests[0].status").value("WAITING_APPROVAL"));
    }

    @Test
    void shouldApprovePendingRequestWhenAuthorized() throws Exception {
        when(authorizationService.canManageSpaceExecutionApprovals(
                any(AuthenticatedUserContext.class), eq("finance-space"), eq("prod")))
                .thenReturn(true);
        when(executionApprovalService.approveRequest(
                eq("finance-space"),
                eq("prod"),
                eq("REQ-1"),
                eq("1001")))
                .thenReturn(Optional.of(new ExecutionApprovalDecisionView(
                        "REQ-1",
                        "RUN-1",
                        "oa.leave.apply",
                        "WORKFLOW",
                        11L,
                        "finance-space",
                        "prod",
                        "submit_approval",
                        "APPROVED",
                        "COMPLETED",
                        "manual",
                        "u2001",
                        "u1001",
                        LocalDateTime.of(2026, 3, 10, 23, 0),
                        LocalDateTime.of(2026, 3, 10, 23, 2))));

        mockMvc.perform(post("/api/controlplane/spaces/finance-space/approval-requests/REQ-1/approve")
                        .principal(authenticatedPrincipal()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.requestId").value("REQ-1"))
                .andExpect(jsonPath("$.data.status").value("APPROVED"))
                .andExpect(jsonPath("$.data.runStatus").value("COMPLETED"));
    }

    @Test
    void shouldRejectPendingRequestWhenAuthorized() throws Exception {
        when(authorizationService.canManageSpaceExecutionApprovals(
                any(AuthenticatedUserContext.class), eq("finance-space"), eq("prod")))
                .thenReturn(true);
        when(executionApprovalService.rejectRequest(
                eq("finance-space"),
                eq("prod"),
                eq("REQ-1"),
                eq("1001")))
                .thenReturn(Optional.of(new ExecutionApprovalDecisionView(
                        "REQ-1",
                        "RUN-1",
                        "oa.leave.apply",
                        "WORKFLOW",
                        11L,
                        "finance-space",
                        "prod",
                        "submit_approval",
                        "REJECTED",
                        "CANCELLED",
                        "manual",
                        "u2001",
                        "u1001",
                        LocalDateTime.of(2026, 3, 10, 23, 0),
                        LocalDateTime.of(2026, 3, 10, 23, 1))));

        mockMvc.perform(post("/api/controlplane/spaces/finance-space/approval-requests/REQ-1/reject")
                        .principal(authenticatedPrincipal()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.requestId").value("REQ-1"))
                .andExpect(jsonPath("$.data.status").value("REJECTED"))
                .andExpect(jsonPath("$.data.runStatus").value("CANCELLED"));
    }

    @Test
    void shouldReturnForbiddenWhenApprovalScopeDenied() throws Exception {
        when(authorizationService.canManageSpaceExecutionApprovals(
                any(AuthenticatedUserContext.class), eq("finance-space"), eq("prod")))
                .thenReturn(false);

        mockMvc.perform(get("/api/controlplane/spaces/finance-space/approval-requests")
                        .principal(authenticatedPrincipal()))
                .andExpect(status().isForbidden());

        verify(executionApprovalService, never()).listPendingRequests("finance-space", "prod");
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
