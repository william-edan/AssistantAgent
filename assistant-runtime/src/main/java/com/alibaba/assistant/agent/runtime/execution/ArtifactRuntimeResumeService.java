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
import com.alibaba.assistant.agent.execution.model.StepStatus;
import com.alibaba.assistant.agent.execution.persistence.ApprovalRequest;
import com.alibaba.assistant.agent.execution.persistence.ApprovalRequestService;
import com.alibaba.assistant.agent.execution.persistence.ExecutionRun;
import com.alibaba.assistant.agent.execution.persistence.ExecutionRunService;
import com.alibaba.assistant.agent.runtime.registry.PublishedToolDescriptor;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * Restores paused artifact executions after approval is granted.
 */
@Service
public class ArtifactRuntimeResumeService {

    private final ExecutionRunService executionRunService;

    private final ApprovalRequestService approvalRequestService;

    private final ArtifactRuntimeExecutor artifactRuntimeExecutor;

    private final ObjectMapper objectMapper;

    public ArtifactRuntimeResumeService(
            ExecutionRunService executionRunService,
            ApprovalRequestService approvalRequestService,
            ArtifactRuntimeExecutor artifactRuntimeExecutor,
            ObjectMapper objectMapper) {
        this.executionRunService = executionRunService;
        this.approvalRequestService = approvalRequestService;
        this.artifactRuntimeExecutor = artifactRuntimeExecutor;
        this.objectMapper = objectMapper;
    }

    public Map<String, Object> approveAndResume(PublishedToolDescriptor descriptor, String requestId) {
        ApprovalRequest approvalRequest = approvalRequestService.findLatestByRequestId(requestId)
                .orElseThrow(() -> new IllegalArgumentException("approval_request_not_found"));
        ExecutionRun run = executionRunService.findLatestByRunId(approvalRequest.getRunId())
                .orElseThrow(() -> new IllegalStateException("execution_run_not_found"));
        if (!StringUtils.hasText(run.getContextSnapshotJson())) {
            throw new IllegalStateException("execution_context_snapshot_missing");
        }
        FlowContext flowContext = restoreContext(run, approvalRequest);
        approvalRequest.setStatus("APPROVED");
        approvalRequest.setRespondedAt(LocalDateTime.now());
        approvalRequestService.updateById(approvalRequest);
        return artifactRuntimeExecutor.resume(descriptor, flowContext);
    }

    private FlowContext restoreContext(ExecutionRun run, ApprovalRequest approvalRequest) {
        try {
            ExecutionContextSnapshot snapshot = objectMapper.readValue(run.getContextSnapshotJson(), ExecutionContextSnapshot.class);
            FlowContext flowContext = new FlowContext(snapshot.initialInputs());
            flowContext.setRunId(run.getRunId());
            flowContext.setAssistantUid(run.getPlatformPrincipalId());
            flowContext.setThreadId(run.getThreadId());
            if (snapshot.stepOutputs() != null) {
                for (Map.Entry<String, Map<String, Object>> entry : snapshot.stepOutputs().entrySet()) {
                    flowContext.restoreStepOutput(entry.getKey(), entry.getValue());
                }
            }
            if (snapshot.stepStatuses() != null) {
                for (Map.Entry<String, String> entry : snapshot.stepStatuses().entrySet()) {
                    if (!StringUtils.hasText(entry.getValue())) {
                        continue;
                    }
                    StepStatus status = StepStatus.valueOf(entry.getValue().trim());
                    if (status != StepStatus.WAITING_APPROVAL) {
                        flowContext.restoreStepStatus(entry.getKey(), status);
                    }
                }
            }
            flowContext.approveStep(approvalRequest.getStepId());
            return flowContext;
        }
        catch (Exception e) {
            throw new IllegalStateException("execution_context_snapshot_invalid", e);
        }
    }
}
