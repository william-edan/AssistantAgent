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
import com.alibaba.assistant.agent.execution.persistence.ExecutionStep;
import com.alibaba.assistant.agent.execution.persistence.ExecutionStepService;
import com.alibaba.assistant.agent.runtime.registry.ArtifactPublicationLookupService;
import com.alibaba.assistant.agent.runtime.registry.PublishedToolDescriptor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Control-plane service for listing and deciding persisted approval requests.
 */
@Service
public class ExecutionApprovalService {

    static final String STATUS_WAITING_APPROVAL = "WAITING_APPROVAL";
    static final String STATUS_APPROVED = "APPROVED";
    static final String STATUS_REJECTED = "REJECTED";
    static final String RUN_STATUS_CANCELLED = "CANCELLED";
    static final String DEFAULT_ENVIRONMENT = "prod";

    private final PlatformSpaceService platformSpaceService;
    private final ApprovalRequestService approvalRequestService;
    private final ExecutionRunService executionRunService;
    private final ExecutionStepService executionStepService;
    private final ArtifactPublicationLookupService artifactPublicationLookupService;
    private final ArtifactRuntimeResumeService artifactRuntimeResumeService;

    public ExecutionApprovalService(
            PlatformSpaceService platformSpaceService,
            ApprovalRequestService approvalRequestService,
            ExecutionRunService executionRunService,
            ExecutionStepService executionStepService,
            ArtifactPublicationLookupService artifactPublicationLookupService,
            ArtifactRuntimeResumeService artifactRuntimeResumeService) {
        this.platformSpaceService = platformSpaceService;
        this.approvalRequestService = approvalRequestService;
        this.executionRunService = executionRunService;
        this.executionStepService = executionStepService;
        this.artifactPublicationLookupService = artifactPublicationLookupService;
        this.artifactRuntimeResumeService = artifactRuntimeResumeService;
    }

    /**
     * List approval requests for the target space with optional status and run filters.
     */
    public List<ExecutionApprovalRequestView> listRequests(
            String spaceCode,
            String environment,
            String status,
            String runId,
            LocalDateTime requestedAfter,
            LocalDateTime requestedBefore,
            Integer limit) {
        Optional<PlatformSpace> spaceOptional = findSpace(spaceCode, environment);
        if (spaceOptional.isEmpty()) {
            return List.of();
        }
        PlatformSpace space = spaceOptional.get();
        String normalizedStatus = normalizeStatus(status);
        String normalizedRunId = normalizeOptional(runId);
        List<ExecutionRun> runs = executionRunService.listLatestBySpace(space.getId(), normalizedRunId, limit);
        if (runs == null || runs.isEmpty()) {
            return List.of();
        }

        Map<String, ExecutionRun> runsById = new LinkedHashMap<>();
        for (ExecutionRun run : runs) {
            if (run != null && StringUtils.hasText(run.getRunId())) {
                runsById.putIfAbsent(run.getRunId(), run);
            }
        }
        if (runsById.isEmpty()) {
            return List.of();
        }

        String normalizedEnvironment = normalizeEnvironment(environment);
        return approvalRequestService.listByRunIds(List.copyOf(runsById.keySet()), normalizedStatus, requestedAfter, requestedBefore, limit).stream()
                .map(request -> toRequestView(request, runsById.get(request.getRunId()), space.getSpaceCode(), normalizedEnvironment))
                .filter(view -> view != null)
                .toList();
    }

    /**
     * List pending approval requests for the target space.
     */
    public List<ExecutionApprovalRequestView> listPendingRequests(String spaceCode, String environment) {
        return listRequests(spaceCode, environment, null, null, null, null, null);
    }

    /**
     * Load a single approval request detail for operator views.
     */
    public Optional<ExecutionApprovalDetailView> findRequest(String spaceCode, String environment, String requestId) {
        Optional<PlatformSpace> spaceOptional = findSpace(spaceCode, environment);
        if (spaceOptional.isEmpty() || !StringUtils.hasText(requestId)) {
            return Optional.empty();
        }
        ApprovalRequest request = approvalRequestService.findLatestByRequestId(requestId.trim()).orElse(null);
        if (request == null) {
            return Optional.empty();
        }
        ExecutionRun run = executionRunService.findLatestByRunId(request.getRunId()).orElse(null);
        if (run == null || run.getSpaceId() == null || !run.getSpaceId().equals(spaceOptional.get().getId())) {
            return Optional.empty();
        }
        return Optional.of(toDetailView(request, run, spaceOptional.get().getSpaceCode(), normalizeEnvironment(environment)));
    }

    /**
     * Approve a pending request and resume the paused execution.
     */
    public Optional<ExecutionApprovalDecisionView> approveRequest(
            String spaceCode,
            String environment,
            String requestId,
            String actorUserId) {
        ApprovalResolution resolution = resolvePendingRequest(spaceCode, environment, requestId);
        if (resolution == null) {
            return Optional.empty();
        }
        PublishedToolDescriptor descriptor = resolvePublishedDescriptor(resolution.space(), resolution.run());
        if (descriptor == null) {
            return Optional.empty();
        }
        artifactRuntimeResumeService.approveAndResume(descriptor, resolution.request().getRequestId());
        ApprovalRequest request = approvalRequestService.findLatestByRequestId(resolution.request().getRequestId())
                .orElse(resolution.request());
        ExecutionRun run = executionRunService.findLatestByRunId(resolution.run().getRunId())
                .orElse(resolution.run());
        return Optional.of(toDecisionView(request, run, resolution.space().getSpaceCode(), resolution.environment()));
    }

    /**
     * Reject a pending request and mark the execution as cancelled.
     */
    public Optional<ExecutionApprovalDecisionView> rejectRequest(
            String spaceCode,
            String environment,
            String requestId,
            String actorUserId) {
        ApprovalResolution resolution = resolvePendingRequest(spaceCode, environment, requestId);
        if (resolution == null) {
            return Optional.empty();
        }
        LocalDateTime now = LocalDateTime.now();
        ApprovalRequest request = resolution.request();
        request.setStatus(STATUS_REJECTED);
        request.setRespondedAt(now);
        approvalRequestService.updateById(request);

        ExecutionRun run = resolution.run();
        run.setStatus(RUN_STATUS_CANCELLED);
        run.setCompletedAt(now);
        executionRunService.updateById(run);

        Optional<ExecutionStep> stepOptional = executionStepService.findByRunIdAndStepId(run.getRunId(), request.getStepId());
        if (stepOptional.isPresent()) {
            ExecutionStep step = stepOptional.get();
            step.setStatus(RUN_STATUS_CANCELLED);
            step.setCompletedAt(now);
            executionStepService.updateById(step);
        }

        return Optional.of(toDecisionView(request, run, resolution.space().getSpaceCode(), resolution.environment()));
    }

    private ApprovalResolution resolvePendingRequest(String spaceCode, String environment, String requestId) {
        Optional<PlatformSpace> spaceOptional = findSpace(spaceCode, environment);
        if (spaceOptional.isEmpty() || !StringUtils.hasText(requestId)) {
            return null;
        }
        ApprovalRequest request = approvalRequestService.findLatestByRequestId(requestId.trim()).orElse(null);
        if (request == null || !STATUS_WAITING_APPROVAL.equalsIgnoreCase(request.getStatus())) {
            return null;
        }
        ExecutionRun run = executionRunService.findLatestByRunId(request.getRunId()).orElse(null);
        if (run == null || run.getSpaceId() == null || !run.getSpaceId().equals(spaceOptional.get().getId())) {
            return null;
        }
        return new ApprovalResolution(spaceOptional.get(), normalizeEnvironment(environment), request, run);
    }

    private Optional<PlatformSpace> findSpace(String spaceCode, String environment) {
        return platformSpaceService.findActiveByCode(spaceCode, normalizeEnvironment(environment));
    }

    private PublishedToolDescriptor resolvePublishedDescriptor(PlatformSpace space, ExecutionRun run) {
        if (space == null || run == null || !StringUtils.hasText(run.getArtifactCode())) {
            return null;
        }
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("space_id", space.getId());
        attributes.put("space_environment", normalizeEnvironment(space.getEnvironment()));
        attributes.put("tool_source_mode", "exclusive");
        attributes.put("tool_source_ids", List.of("artifact-catalog"));
        return artifactPublicationLookupService.listPublishedArtifacts(attributes).stream()
                .filter(descriptor -> descriptor != null
                        && descriptor.artifact() != null
                        && StringUtils.hasText(descriptor.artifact().getArtifactCode())
                        && run.getArtifactCode().trim().equalsIgnoreCase(descriptor.artifact().getArtifactCode().trim()))
                .findFirst()
                .orElse(null);
    }

    private ExecutionApprovalRequestView toRequestView(
            ApprovalRequest request,
            ExecutionRun run,
            String spaceCode,
            String environment) {
        if (request == null || run == null) {
            return null;
        }
        return new ExecutionApprovalRequestView(
                request.getRequestId(),
                run.getRunId(),
                run.getArtifactCode(),
                run.getArtifactType(),
                run.getSpaceId(),
                spaceCode,
                environment,
                request.getStepId(),
                request.getStatus(),
                request.getApprovalChannel(),
                request.getApproverPrincipalId(),
                run.getPausedStepId(),
                run.getPlatformPrincipalId(),
                run.getThreadId(),
                request.getRequestedAt(),
                request.getRespondedAt());
    }

    private ExecutionApprovalDetailView toDetailView(
            ApprovalRequest request,
            ExecutionRun run,
            String spaceCode,
            String environment) {
        return new ExecutionApprovalDetailView(
                request.getRequestId(),
                run.getRunId(),
                run.getArtifactCode(),
                run.getArtifactType(),
                run.getSpaceId(),
                spaceCode,
                environment,
                request.getStepId(),
                request.getStatus(),
                run.getStatus(),
                request.getApprovalChannel(),
                request.getApproverPrincipalId(),
                run.getPausedStepId(),
                run.getPlatformPrincipalId(),
                run.getThreadId(),
                request.getRequestedAt(),
                request.getRespondedAt());
    }

    private ExecutionApprovalDecisionView toDecisionView(
            ApprovalRequest request,
            ExecutionRun run,
            String spaceCode,
            String environment) {
        return new ExecutionApprovalDecisionView(
                request.getRequestId(),
                run.getRunId(),
                run.getArtifactCode(),
                run.getArtifactType(),
                run.getSpaceId(),
                spaceCode,
                environment,
                request.getStepId(),
                request.getStatus(),
                run.getStatus(),
                request.getApprovalChannel(),
                request.getApproverPrincipalId(),
                run.getPlatformPrincipalId(),
                request.getRequestedAt(),
                request.getRespondedAt());
    }

    private String normalizeEnvironment(String environment) {
        return StringUtils.hasText(environment) ? environment.trim() : DEFAULT_ENVIRONMENT;
    }

    private String normalizeStatus(String status) {
        if (!StringUtils.hasText(status)) {
            return STATUS_WAITING_APPROVAL;
        }
        return status.trim().toUpperCase();
    }

    private String normalizeOptional(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private record ApprovalResolution(
            PlatformSpace space,
            String environment,
            ApprovalRequest request,
            ExecutionRun run) {
    }
}

