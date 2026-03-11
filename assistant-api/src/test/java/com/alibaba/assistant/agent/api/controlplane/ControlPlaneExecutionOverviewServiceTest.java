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
package com.alibaba.assistant.agent.api.controlplane;

import com.alibaba.assistant.agent.controlplane.space.PlatformSpace;
import com.alibaba.assistant.agent.controlplane.space.PlatformSpaceService;
import com.alibaba.assistant.agent.execution.persistence.ExecutionHistoryRunSummaryView;
import com.alibaba.assistant.agent.execution.persistence.ExecutionHistoryService;
import com.alibaba.assistant.agent.runtime.execution.ExecutionApprovalRequestView;
import com.alibaba.assistant.agent.runtime.execution.ExecutionApprovalService;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ControlPlaneExecutionOverviewServiceTest {

    @Test
    void shouldAggregateRecentRunsAndPendingApprovalsWhenApprovalAccessGranted() {
        PlatformSpaceService platformSpaceService = mock(PlatformSpaceService.class);
        ExecutionHistoryService executionHistoryService = mock(ExecutionHistoryService.class);
        ExecutionApprovalService executionApprovalService = mock(ExecutionApprovalService.class);
        ControlPlaneExecutionOverviewService service = new ControlPlaneExecutionOverviewService(
                platformSpaceService,
                executionHistoryService,
                executionApprovalService);

        PlatformSpace space = new PlatformSpace();
        space.setId(11L);
        space.setSpaceCode("finance-space");
        space.setEnvironment("prod");
        when(platformSpaceService.findActiveByCode("finance-space", "prod")).thenReturn(Optional.of(space));
        when(executionHistoryService.listRuns(11L, null, null, null, null, null, null, null, 3))
                .thenReturn(List.of(new ExecutionHistoryRunSummaryView(
                        "RUN-1",
                        "oa.leave.apply",
                        "WORKFLOW",
                        11L,
                        "u1001",
                        "THREAD-1",
                        "WAITING_APPROVAL",
                        "submit_approval",
                        "REQ-1",
                        LocalDateTime.of(2026, 3, 11, 9, 0),
                        null)));
        when(executionApprovalService.listRequests("finance-space", "prod", "WAITING_APPROVAL", null, null, null, null, null, null, 2))
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
                        "manager-1",
                        "submit_approval",
                        "u1001",
                        "THREAD-1",
                        LocalDateTime.of(2026, 3, 11, 9, 5),
                        null)));

        Optional<ControlPlaneExecutionOverview> overviewOptional =
                service.getOverview("finance-space", "prod", 3, 2, true);

        assertTrue(overviewOptional.isPresent());
        ControlPlaneExecutionOverview overview = overviewOptional.get();
        assertEquals("finance-space", overview.spaceCode());
        assertEquals("prod", overview.environment());
        assertEquals(1, overview.summary().recentRunCount());
        assertEquals(1, overview.summary().pendingApprovalCount());
        assertTrue(overview.summary().approvalAccess());
        assertEquals(1, overview.recentRuns().size());
        assertEquals(1, overview.pendingApprovals().size());
    }

    @Test
    void shouldReturnOverviewWithoutPendingApprovalsWhenApprovalAccessDenied() {
        PlatformSpaceService platformSpaceService = mock(PlatformSpaceService.class);
        ExecutionHistoryService executionHistoryService = mock(ExecutionHistoryService.class);
        ExecutionApprovalService executionApprovalService = mock(ExecutionApprovalService.class);
        ControlPlaneExecutionOverviewService service = new ControlPlaneExecutionOverviewService(
                platformSpaceService,
                executionHistoryService,
                executionApprovalService);

        PlatformSpace space = new PlatformSpace();
        space.setId(11L);
        space.setSpaceCode("finance-space");
        space.setEnvironment("test");
        when(platformSpaceService.findActiveByCode("finance-space", "test")).thenReturn(Optional.of(space));
        when(executionHistoryService.listRuns(11L, null, null, null, null, null, null, null, 5)).thenReturn(List.of());

        Optional<ControlPlaneExecutionOverview> overviewOptional =
                service.getOverview("finance-space", "test", null, null, false);

        assertTrue(overviewOptional.isPresent());
        ControlPlaneExecutionOverview overview = overviewOptional.get();
        assertEquals(0, overview.summary().pendingApprovalCount());
        assertFalse(overview.summary().approvalAccess());
        assertTrue(overview.pendingApprovals().isEmpty());
        verify(executionApprovalService, never()).listRequests("finance-space", "test", "WAITING_APPROVAL", null, null, null, null, null, null, 5);
    }
}
