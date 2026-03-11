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
package com.alibaba.assistant.agent.api.controller.dto;

import com.alibaba.assistant.agent.api.controlplane.ControlPlaneExecutionOverview;

import java.util.List;

/**
 * Execution overview payload for operator-facing control-plane pages.
 */
public record ControlPlaneExecutionOverviewData(
        String spaceCode,
        String environment,
        Summary summary,
        List<ExecutionRunSummaryData> recentRuns,
        List<ExecutionApprovalRequestData> pendingApprovals) {

    public static ControlPlaneExecutionOverviewData from(ControlPlaneExecutionOverview overview) {
        return new ControlPlaneExecutionOverviewData(
                overview.spaceCode(),
                overview.environment(),
                Summary.from(overview.summary()),
                overview.recentRuns().stream()
                        .map(run -> ExecutionRunSummaryData.from(run, overview.spaceCode(), overview.environment()))
                        .toList(),
                overview.pendingApprovals().stream()
                        .map(ExecutionApprovalRequestData::from)
                        .toList());
    }

    public record Summary(int recentRunCount, int pendingApprovalCount, boolean approvalAccess) {
        static Summary from(ControlPlaneExecutionOverview.Summary summary) {
            return new Summary(summary.recentRunCount(), summary.pendingApprovalCount(), summary.approvalAccess());
        }
    }
}
