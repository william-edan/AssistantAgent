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
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Optional;

/**
 * Aggregates execution and approval views for operator-facing control-plane pages.
 */
@Service
public class ControlPlaneExecutionOverviewService {

    private static final String DEFAULT_ENVIRONMENT = "prod";
    private static final String STATUS_WAITING_APPROVAL = "WAITING_APPROVAL";
    private static final int DEFAULT_RECENT_RUN_LIMIT = 5;
    private static final int DEFAULT_PENDING_APPROVAL_LIMIT = 5;
    private static final int MAX_LIMIT = 20;

    private final PlatformSpaceService platformSpaceService;
    private final ExecutionHistoryService executionHistoryService;
    private final ExecutionApprovalService executionApprovalService;

    public ControlPlaneExecutionOverviewService(
            PlatformSpaceService platformSpaceService,
            ExecutionHistoryService executionHistoryService,
            ExecutionApprovalService executionApprovalService) {
        this.platformSpaceService = platformSpaceService;
        this.executionHistoryService = executionHistoryService;
        this.executionApprovalService = executionApprovalService;
    }

    /**
     * Load a summarized execution overview for a space.
     */
    public Optional<ControlPlaneExecutionOverview> getOverview(
            String spaceCode,
            String environment,
            Integer recentRunLimit,
            Integer pendingApprovalLimit,
            boolean approvalAccess) {
        if (!StringUtils.hasText(spaceCode)) {
            return Optional.empty();
        }
        String normalizedEnvironment = normalizeEnvironment(environment);
        Optional<PlatformSpace> spaceOptional = platformSpaceService.findActiveByCode(spaceCode.trim(), normalizedEnvironment);
        if (spaceOptional.isEmpty()) {
            return Optional.empty();
        }
        PlatformSpace space = spaceOptional.get();
        List<ExecutionHistoryRunSummaryView> recentRuns = executionHistoryService.listRuns(
                space.getId(),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                normalizeLimit(recentRunLimit, DEFAULT_RECENT_RUN_LIMIT));
        List<ExecutionApprovalRequestView> pendingApprovals = approvalAccess
                ? executionApprovalService.listRequests(
                        space.getSpaceCode(),
                        normalizedEnvironment,
                        STATUS_WAITING_APPROVAL,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        normalizeLimit(pendingApprovalLimit, DEFAULT_PENDING_APPROVAL_LIMIT))
                : List.of();
        return Optional.of(new ControlPlaneExecutionOverview(
                space.getSpaceCode(),
                normalizedEnvironment,
                new ControlPlaneExecutionOverview.Summary(
                        recentRuns.size(),
                        pendingApprovals.size(),
                        approvalAccess),
                recentRuns,
                pendingApprovals));
    }

    private String normalizeEnvironment(String environment) {
        return StringUtils.hasText(environment) ? environment.trim() : DEFAULT_ENVIRONMENT;
    }

    private int normalizeLimit(Integer limit, int defaultValue) {
        if (limit == null || limit <= 0) {
            return defaultValue;
        }
        return Math.min(limit, MAX_LIMIT);
    }
}
