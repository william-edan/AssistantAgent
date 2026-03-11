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

import com.alibaba.assistant.agent.controlplane.audit.AuditEvent;
import com.alibaba.assistant.agent.controlplane.audit.AuditEventService;
import com.alibaba.assistant.agent.controlplane.space.PlatformSpace;
import com.alibaba.assistant.agent.controlplane.space.PlatformSpaceService;
import com.alibaba.assistant.agent.execution.persistence.ApprovalRequest;
import com.alibaba.assistant.agent.execution.persistence.ApprovalRequestService;
import com.alibaba.assistant.agent.execution.persistence.ExecutionRun;
import com.alibaba.assistant.agent.execution.persistence.ExecutionRunService;
import com.alibaba.assistant.agent.execution.persistence.ExecutionStepService;
import com.alibaba.assistant.agent.runtime.registry.ArtifactPublicationLookupService;
import com.alibaba.assistant.agent.runtime.compiler.RuntimeArtifact;
import com.alibaba.assistant.agent.runtime.registry.PublishedToolDescriptor;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
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
        AuditEventService auditEventService = mock(AuditEventService.class);
        ExecutionApprovalService service = new ExecutionApprovalService(
                platformSpaceService,
                approvalRequestService,
                executionRunService,
                executionStepService,
                artifactPublicationLookupService,
                artifactRuntimeResumeService,
                auditEventService,
                new ObjectMapper());

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
        when(executionRunService.listBySpace(11L, null, null, null, null, null, null, null, 20)).thenReturn(List.of(run));
        when(approvalRequestService.listByRunIds(List.of("RUN-1"), "WAITING_APPROVAL", null, null, 20)).thenReturn(List.of(request));

        List<ExecutionApprovalRequestView> views = service.listRequests("finance-space", "prod", null, null, null, null, null, null, null, 20);

        assertEquals(1, views.size());
        assertEquals("REQ-1", views.get(0).requestId());
        assertEquals("WAITING_APPROVAL", views.get(0).status());
    }

    @Test
    void shouldReturnApprovedHistoryWhenIdentityAndRequestedTimeFiltersProvided() {
        PlatformSpaceService platformSpaceService = mock(PlatformSpaceService.class);
        ApprovalRequestService approvalRequestService = mock(ApprovalRequestService.class);
        ExecutionRunService executionRunService = mock(ExecutionRunService.class);
        ExecutionStepService executionStepService = mock(ExecutionStepService.class);
        ArtifactPublicationLookupService artifactPublicationLookupService = mock(ArtifactPublicationLookupService.class);
        ArtifactRuntimeResumeService artifactRuntimeResumeService = mock(ArtifactRuntimeResumeService.class);
        AuditEventService auditEventService = mock(AuditEventService.class);
        ExecutionApprovalService service = new ExecutionApprovalService(
                platformSpaceService,
                approvalRequestService,
                executionRunService,
                executionStepService,
                artifactPublicationLookupService,
                artifactRuntimeResumeService,
                auditEventService,
                new ObjectMapper());

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
        run.setThreadId("THREAD-2");
        ApprovalRequest request = new ApprovalRequest();
        request.setRequestId("REQ-2");
        request.setRunId("RUN-2");
        request.setStepId("submit_approval");
        request.setStatus("APPROVED");
        request.setApprovalChannel("manual");
        request.setRequestedAt(LocalDateTime.of(2026, 3, 11, 9, 0));
        request.setRespondedAt(LocalDateTime.of(2026, 3, 11, 9, 5));

        when(platformSpaceService.findActiveByCode("finance-space", "prod")).thenReturn(Optional.of(space));
        when(executionRunService.listBySpace(11L, "RUN-2", null, "oa.leave.apply", "u1001", "THREAD-2", null, null, 10))
                .thenReturn(List.of(run));
        when(approvalRequestService.listByRunIds(List.of("RUN-2"), "APPROVED", requestedAfter, requestedBefore, 10))
                .thenReturn(List.of(request));

        List<ExecutionApprovalRequestView> views = service.listRequests(
                "finance-space",
                "prod",
                "APPROVED",
                "RUN-2",
                "oa.leave.apply",
                "u1001",
                "THREAD-2",
                requestedAfter,
                requestedBefore,
                10);

        assertEquals(1, views.size());
        assertEquals("REQ-2", views.get(0).requestId());
        assertEquals("APPROVED", views.get(0).status());
        assertEquals("RUN-2", views.get(0).runId());
        assertEquals("oa.leave.apply", views.get(0).artifactCode());
        assertEquals("u1001", views.get(0).platformPrincipalId());
        assertEquals("THREAD-2", views.get(0).threadId());
        assertTrue(views.get(0).respondedAt() != null);
    }

    @Test
    void shouldRecordApproverPrincipalWhenApprovingPendingRequest() {
        PlatformSpaceService platformSpaceService = mock(PlatformSpaceService.class);
        ApprovalRequestService approvalRequestService = mock(ApprovalRequestService.class);
        ExecutionRunService executionRunService = mock(ExecutionRunService.class);
        ExecutionStepService executionStepService = mock(ExecutionStepService.class);
        ArtifactPublicationLookupService artifactPublicationLookupService = mock(ArtifactPublicationLookupService.class);
        ArtifactRuntimeResumeService artifactRuntimeResumeService = mock(ArtifactRuntimeResumeService.class);
        AuditEventService auditEventService = mock(AuditEventService.class);
        ExecutionApprovalService service = new ExecutionApprovalService(
                platformSpaceService,
                approvalRequestService,
                executionRunService,
                executionStepService,
                artifactPublicationLookupService,
                artifactRuntimeResumeService,
                auditEventService,
                new ObjectMapper());

        PlatformSpace space = new PlatformSpace();
        space.setId(11L);
        space.setSpaceCode("finance-space");
        space.setEnvironment("prod");
        ExecutionRun run = new ExecutionRun();
        run.setRunId("RUN-3");
        run.setArtifactCode("oa.leave.apply");
        run.setArtifactType("WORKFLOW");
        run.setSpaceId(11L);
        run.setStatus("WAITING_APPROVAL");
        run.setPausedStepId("submit_approval");
        run.setPlatformPrincipalId("u1001");
        ApprovalRequest request = new ApprovalRequest();
        request.setRequestId("REQ-3");
        request.setRunId("RUN-3");
        request.setStepId("submit_approval");
        request.setStatus("WAITING_APPROVAL");
        request.setApprovalChannel("manual");
        request.setRequestedAt(LocalDateTime.of(2026, 3, 11, 11, 30));
        ApprovalRequest approvedRequest = new ApprovalRequest();
        approvedRequest.setRequestId("REQ-3");
        approvedRequest.setRunId("RUN-3");
        approvedRequest.setStepId("submit_approval");
        approvedRequest.setStatus("APPROVED");
        approvedRequest.setApprovalChannel("manual");
        approvedRequest.setRequestedAt(LocalDateTime.of(2026, 3, 11, 11, 30));
        approvedRequest.setRespondedAt(LocalDateTime.of(2026, 3, 11, 11, 32));

        when(platformSpaceService.findActiveByCode("finance-space", "prod")).thenReturn(Optional.of(space));
        when(approvalRequestService.findLatestByRequestId("REQ-3")).thenReturn(Optional.of(request), Optional.of(approvedRequest));
        when(executionRunService.findLatestByRunId("RUN-3")).thenReturn(Optional.of(run), Optional.of(run));
        when(artifactPublicationLookupService.listPublishedArtifacts(org.mockito.ArgumentMatchers.anyMap()))
                .thenReturn(List.of(descriptor(11L, "oa.leave.apply")));
        when(artifactRuntimeResumeService.approveAndResume(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq("REQ-3")))
                .thenReturn(Map.of("success", true, "runId", "RUN-3"));

        Optional<ExecutionApprovalDecisionView> decision = service.approveRequest(
                "finance-space",
                "prod",
                "REQ-3",
                "u2001");

        assertTrue(decision.isPresent());
        assertEquals("u2001", approvedRequest.getApproverPrincipalId());
        assertEquals("u2001", decision.get().approverPrincipalId());
        ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditEventService).save(captor.capture());
        AuditEvent event = captor.getValue();
        assertEquals("APPROVAL_APPROVED", event.getEventType());
        assertEquals("RUN-3", event.getRunId());
        assertEquals("submit_approval", event.getStepId());
        assertEquals("u2001", event.getAssistantUid());
        assertEquals("APPROVED", event.getStatus());
    }

    @Test
    void shouldRecordApproverPrincipalWhenRejectingPendingRequest() {
        PlatformSpaceService platformSpaceService = mock(PlatformSpaceService.class);
        ApprovalRequestService approvalRequestService = mock(ApprovalRequestService.class);
        ExecutionRunService executionRunService = mock(ExecutionRunService.class);
        ExecutionStepService executionStepService = mock(ExecutionStepService.class);
        ArtifactPublicationLookupService artifactPublicationLookupService = mock(ArtifactPublicationLookupService.class);
        ArtifactRuntimeResumeService artifactRuntimeResumeService = mock(ArtifactRuntimeResumeService.class);
        AuditEventService auditEventService = mock(AuditEventService.class);
        ExecutionApprovalService service = new ExecutionApprovalService(
                platformSpaceService,
                approvalRequestService,
                executionRunService,
                executionStepService,
                artifactPublicationLookupService,
                artifactRuntimeResumeService,
                auditEventService,
                new ObjectMapper());

        PlatformSpace space = new PlatformSpace();
        space.setId(11L);
        space.setSpaceCode("finance-space");
        space.setEnvironment("prod");
        ExecutionRun run = new ExecutionRun();
        run.setRunId("RUN-4");
        run.setArtifactCode("oa.leave.apply");
        run.setArtifactType("WORKFLOW");
        run.setSpaceId(11L);
        run.setStatus("WAITING_APPROVAL");
        run.setPausedStepId("submit_approval");
        run.setPlatformPrincipalId("u1002");
        ApprovalRequest request = new ApprovalRequest();
        request.setId(88L);
        request.setRequestId("REQ-4");
        request.setRunId("RUN-4");
        request.setStepId("submit_approval");
        request.setStatus("WAITING_APPROVAL");
        request.setApprovalChannel("manual");
        request.setRequestedAt(LocalDateTime.of(2026, 3, 11, 12, 0));

        when(platformSpaceService.findActiveByCode("finance-space", "prod")).thenReturn(Optional.of(space));
        when(approvalRequestService.findLatestByRequestId("REQ-4")).thenReturn(Optional.of(request));
        when(executionRunService.findLatestByRunId("RUN-4")).thenReturn(Optional.of(run));
        when(executionStepService.findByRunIdAndStepId("RUN-4", "submit_approval")).thenReturn(Optional.empty());

        Optional<ExecutionApprovalDecisionView> decision = service.rejectRequest(
                "finance-space",
                "prod",
                "REQ-4",
                "u3001");

        assertTrue(decision.isPresent());
        assertEquals("u3001", request.getApproverPrincipalId());
        assertEquals("u3001", decision.get().approverPrincipalId());
        ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditEventService).save(captor.capture());
        AuditEvent event = captor.getValue();
        assertEquals("APPROVAL_REJECTED", event.getEventType());
        assertEquals("RUN-4", event.getRunId());
        assertEquals("submit_approval", event.getStepId());
        assertEquals("u3001", event.getAssistantUid());
        assertEquals("REJECTED", event.getStatus());
    }

    private PublishedToolDescriptor descriptor(Long spaceId, String artifactCode) {
        RuntimeArtifact artifact = new RuntimeArtifact(
                spaceId,
                artifactCode,
                RuntimeArtifact.ArtifactType.WORKFLOW,
                "请假申请",
                1,
                null,
                null,
                null,
                null,
                null,
                null,
                Map.of(),
                Map.of());
        return PublishedToolDescriptor.forArtifact(
                "artifact-catalog",
                "workflow:" + artifactCode,
                "请假申请",
                null,
                null,
                false,
                "gougu_oa",
                artifact);
    }
}

