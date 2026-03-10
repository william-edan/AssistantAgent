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

import com.alibaba.assistant.agent.execution.flow.FlowContext;
import com.alibaba.assistant.agent.execution.persistence.ApprovalRequest;
import com.alibaba.assistant.agent.execution.persistence.ApprovalRequestService;
import com.alibaba.assistant.agent.execution.persistence.ExecutionRun;
import com.alibaba.assistant.agent.execution.persistence.ExecutionRunService;
import com.alibaba.assistant.agent.runtime.compiler.RuntimeArtifact;
import com.alibaba.assistant.agent.runtime.registry.PublishedToolDescriptor;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ArtifactRuntimeResumeServiceTest {

    @Test
    void shouldRestoreContextAndApprovePausedStepBeforeResume() {
        ExecutionRunService executionRunService = mock(ExecutionRunService.class);
        ApprovalRequestService approvalRequestService = mock(ApprovalRequestService.class);
        ArtifactRuntimeExecutor artifactRuntimeExecutor = mock(ArtifactRuntimeExecutor.class);
        ArtifactRuntimeResumeService resumeService = new ArtifactRuntimeResumeService(
                executionRunService,
                approvalRequestService,
                artifactRuntimeExecutor,
                new ObjectMapper());

        PublishedToolDescriptor descriptor = descriptor();
        ExecutionRun run = new ExecutionRun();
        run.setRunId("RUN-1");
        run.setPlatformPrincipalId("u1");
        run.setThreadId("T-1");
        run.setContextSnapshotJson("{\"initialInputs\":{\"reason\":\"事假\"},\"stepOutputs\":{\"create_leave\":{\"leave_id\":\"L-1\"}},\"stepStatuses\":{\"create_leave\":\"COMPLETED\"}}");
        ApprovalRequest approvalRequest = new ApprovalRequest();
        approvalRequest.setId(100L);
        approvalRequest.setRequestId("RUN-1:submit_approval");
        approvalRequest.setRunId("RUN-1");
        approvalRequest.setStepId("submit_approval");
        approvalRequest.setStatus("WAITING_APPROVAL");

        when(approvalRequestService.findLatestByRequestId("RUN-1:submit_approval"))
                .thenReturn(Optional.of(approvalRequest));
        when(executionRunService.findLatestByRunId("RUN-1")).thenReturn(Optional.of(run));
        when(artifactRuntimeExecutor.resume(eq(descriptor), any(FlowContext.class)))
                .thenReturn(Map.of("success", true, "runId", "RUN-1"));

        Map<String, Object> result = resumeService.approveAndResume(descriptor, "RUN-1:submit_approval");

        ArgumentCaptor<FlowContext> contextCaptor = ArgumentCaptor.forClass(FlowContext.class);
        verify(artifactRuntimeExecutor).resume(eq(descriptor), contextCaptor.capture());
        verify(approvalRequestService).updateById(approvalRequest);
        assertEquals("APPROVED", approvalRequest.getStatus());
        assertTrue(approvalRequest.getRespondedAt() != null);
        assertEquals("RUN-1", contextCaptor.getValue().getRunId());
        assertEquals("u1", contextCaptor.getValue().getAssistantUid());
        assertEquals("T-1", contextCaptor.getValue().getThreadId());
        assertTrue(contextCaptor.getValue().isStepApproved("submit_approval"));
        assertEquals(Map.of("success", true, "runId", "RUN-1"), result);
    }

    private PublishedToolDescriptor descriptor() {
        RuntimeArtifact artifact = new RuntimeArtifact(
                1L,
                "oa.leave.apply",
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
                "workflow:oa.leave.apply",
                "请假申请",
                null,
                null,
                false,
                "gougu_oa",
                artifact);
    }
}
