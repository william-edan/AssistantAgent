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
package com.alibaba.assistant.agent.execution.persistence;

import com.alibaba.assistant.agent.execution.persistence.mapper.ApprovalRequestMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Optional;

/**
 * Service for approval-request persistence lookups.
 */
@Service
public class ApprovalRequestService extends ServiceImpl<ApprovalRequestMapper, ApprovalRequest> {

    private static final String STATUS_PENDING = "WAITING_APPROVAL";
    private static final int DEFAULT_LIST_LIMIT = 20;

    public Optional<ApprovalRequest> findLatestPendingByRunAndStep(String runId, String stepId) {
        if (!StringUtils.hasText(runId) || !StringUtils.hasText(stepId)) {
            return Optional.empty();
        }
        LambdaQueryWrapper<ApprovalRequest> query = new LambdaQueryWrapper<>();
        query.eq(ApprovalRequest::getRunId, runId.trim());
        query.eq(ApprovalRequest::getStepId, stepId.trim());
        query.eq(ApprovalRequest::getStatus, STATUS_PENDING);
        query.orderByDesc(ApprovalRequest::getId);
        return Optional.ofNullable(getOne(query, false));
    }

    public Optional<ApprovalRequest> findLatestByRequestId(String requestId) {
        if (!StringUtils.hasText(requestId)) {
            return Optional.empty();
        }
        LambdaQueryWrapper<ApprovalRequest> query = new LambdaQueryWrapper<>();
        query.eq(ApprovalRequest::getRequestId, requestId.trim());
        query.orderByDesc(ApprovalRequest::getId);
        return Optional.ofNullable(getOne(query, false));
    }

    public List<ApprovalRequest> listByRunIds(List<String> runIds, String status, Integer limit) {
        if (runIds == null || runIds.isEmpty()) {
            return List.of();
        }
        int normalizedLimit = normalizeLimit(limit);
        return lambdaQuery()
                .in(ApprovalRequest::getRunId, runIds)
                .eq(StringUtils.hasText(status), ApprovalRequest::getStatus, status != null ? status.trim() : null)
                .orderByDesc(ApprovalRequest::getRequestedAt)
                .orderByDesc(ApprovalRequest::getId)
                .list()
                .stream()
                .limit(normalizedLimit)
                .toList();
    }

    private int normalizeLimit(Integer limit) {
        if (limit == null || limit <= 0) {
            return DEFAULT_LIST_LIMIT;
        }
        return Math.min(limit, 100);
    }
}
