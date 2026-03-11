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
package com.alibaba.assistant.agent.runtime.execution;

import com.alibaba.assistant.agent.controlplane.space.PlatformSpace;
import com.alibaba.assistant.agent.controlplane.space.PlatformSpaceService;
import com.alibaba.assistant.agent.execution.persistence.ApprovalRequest;
import com.alibaba.assistant.agent.execution.persistence.ApprovalRequestService;
import com.alibaba.assistant.agent.execution.persistence.ExecutionRun;
import com.alibaba.assistant.agent.execution.persistence.ExecutionRunService;
import com.alibaba.assistant.agent.execution.persistence.ExecutionStepService;
import com.alibaba.assistant.agent.runtime.registry.ArtifactPublicationLookupService;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ExecutionApprovalServiceTest {

    @Test
    void shouldDefaultToWaitingApprovalWhenStatusFilterMissing() {
        PlatformSpaceService platformSpaceService = mock(PlatformSpaceService.class);
        ApprovalRequestService approvalRequestService = mock(ApprovalRequestService.class);
        ExecutionRunService executionRunService = mock(ExecutionRunService.class);
        ExecutionStepService executionStepService = mock(ExecutionStepService.class);
        ArtifactPublicationLookupService artifactPublicationLookupService = mock(ArtifactPublicationLookupService.class);
        ArtifactRuntimeResumeService artifactRuntimeResumeService = mock(ArtifactRuntimeResumeService.class);
        ExecutionApprovalService service = new ExecutionApprovalService(
                platformSpaceService,
                approvalRequestService,
                executionRunService,
                executionStepService,
                artifactPublicationLookupService,
                artifactRuntimeResumeService);

        PlatformSpace space = new PlatformSpace();
        space.setId(11L);
        space.setSpaceCode("finance-space");
        space.setEnvironment("prod");
        ExecutionRun run = new ExecutionRun();
        run.setRunId("RUN-1");
        run.setArtifactCode("oa.leave.apply");
        run.setArtifactType("WORKFLOW");
        run.setSpaceId(11L);
        run.setStatus("WAITING_APPROVAL");
        run.setPausedStepId("submit_approval");
        run.setPlatformPrincipalId("u1001");
        run.setThreadId("T-1");
        ApprovalRequest request = new ApprovalRequest();
        request.setRequestId("REQ-1");
        request.setRunId("RUN-1");
        request.setStepId("submit_approval");
        request.setStatus("WAITING_APPROVAL");
        request.setApprovalChannel("manual");
        request.setRequestedAt(LocalDateTime.of(2026, 3, 11, 11, 0));

        when(platformSpaceService.findActiveByCode("finance-space", "prod")).thenReturn(Optional.of(space));
        when(executionRunService.listLatestBySpace(11L, null, 20)).thenReturn(List.of(run));
        when(approvalRequestService.listByRunIds(List.of("RUN-1"), "WAITING_APPROVAL", null, null, 20)).thenReturn(List.of(request));

        List<ExecutionApprovalRequestView> views = service.listRequests("finance-space", "prod", null, null, null, null, 20);

        assertEquals(1, views.size());
        assertEquals("REQ-1", views.get(0).requestId());
        assertEquals("WAITING_APPROVAL", views.get(0).status());
    }

    @Test
    void shouldReturnApprovedHistoryWhenStatusRunAndRequestedTimeFiltersProvided() {
        PlatformSpaceService platformSpaceService = mock(PlatformSpaceService.class);
        ApprovalRequestService approvalRequestService = mock(ApprovalRequestService.class);
        ExecutionRunService executionRunService = mock(ExecutionRunService.class);
        ExecutionStepService executionStepService = mock(ExecutionStepService.class);
        ArtifactPublicationLookupService artifactPublicationLookupService = mock(ArtifactPublicationLookupService.class);
        ArtifactRuntimeResumeService artifactRuntimeResumeService = mock(ArtifactRuntimeResumeService.class);
        ExecutionApprovalService service = new ExecutionApprovalService(
                platformSpaceService,
                approvalRequestService,
                executionRunService,
                executionStepService,
                artifactPublicationLookupService,
                artifactRuntimeResumeService);

        LocalDateTime requestedAfter = LocalDateTime.of(2026, 3, 11, 8, 0);
        LocalDateTime requestedBefore = LocalDateTime.of(2026, 3, 11, 10, 0);
        PlatformSpace space = new PlatformSpace();
        space.setId(11L);
        space.setSpaceCode("finance-space");
        space.setEnvironment("prod");
        ExecutionRun run = new ExecutionRun();
        run.setRunId("RUN-2");
        run.setArtifactCode("oa.leave.apply");
        run.setArtifactType("WORKFLOW");
        run.setSpaceId(11L);
        run.setStatus("COMPLETED");
        run.setPausedStepId("submit_approval");
        run.setPlatformPrincipalId("u1001");
        run.setThreadId("T-2");
        ApprovalRequest request = new ApprovalRequest();
        request.setRequestId("REQ-2");
        request.setRunId("RUN-2");
        request.setStepId("submit_approval");
        request.setStatus("APPROVED");
        request.setApprovalChannel("manual");
        request.setRequestedAt(LocalDateTime.of(2026, 3, 11, 9, 0));
        request.setRespondedAt(LocalDateTime.of(2026, 3, 11, 9, 5));

        when(platformSpaceService.findActiveByCode("finance-space", "prod")).thenReturn(Optional.of(space));
        when(executionRunService.listLatestBySpace(11L, "RUN-2", 10)).thenReturn(List.of(run));
        when(approvalRequestService.listByRunIds(List.of("RUN-2"), "APPROVED", requestedAfter, requestedBefore, 10))
                .thenReturn(List.of(request));

        List<ExecutionApprovalRequestView> views = service.listRequests(
                "finance-space",
                "prod",
                "APPROVED",
                "RUN-2",
                requestedAfter,
                requestedBefore,
                10);

        assertEquals(1, views.size());
        assertEquals("REQ-2", views.get(0).requestId());
        assertEquals("APPROVED", views.get(0).status());
        assertEquals("RUN-2", views.get(0).runId());
        assertTrue(views.get(0).respondedAt() != null);
    }
}

