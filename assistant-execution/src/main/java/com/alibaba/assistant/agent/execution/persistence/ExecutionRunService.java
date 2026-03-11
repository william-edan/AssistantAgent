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

import com.alibaba.assistant.agent.execution.persistence.mapper.ExecutionRunMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Service for execution-run persistence lookups.
 */
@Service
public class ExecutionRunService extends ServiceImpl<ExecutionRunMapper, ExecutionRun> {

    private static final int DEFAULT_LIST_LIMIT = 20;

    public Optional<ExecutionRun> findLatestByRunId(String runId) {
        if (!StringUtils.hasText(runId)) {
            return Optional.empty();
        }
        LambdaQueryWrapper<ExecutionRun> query = new LambdaQueryWrapper<>();
        query.eq(ExecutionRun::getRunId, runId.trim());
        query.orderByDesc(ExecutionRun::getId);
        return Optional.ofNullable(getOne(query, false));
    }

    public Optional<ExecutionRun> findLatestByApprovalRequestId(String approvalRequestId) {
        if (!StringUtils.hasText(approvalRequestId)) {
            return Optional.empty();
        }
        LambdaQueryWrapper<ExecutionRun> query = new LambdaQueryWrapper<>();
        query.eq(ExecutionRun::getApprovalRequestId, approvalRequestId.trim());
        query.orderByDesc(ExecutionRun::getId);
        return Optional.ofNullable(getOne(query, false));
    }

    public List<ExecutionRun> listLatestBySpace(Long spaceId, String runId, Integer limit) {
        if (spaceId == null) {
            return List.of();
        }
        int normalizedLimit = normalizeLimit(limit);
        return lambdaQuery()
                .eq(ExecutionRun::getSpaceId, spaceId)
                .eq(StringUtils.hasText(runId), ExecutionRun::getRunId, runId != null ? runId.trim() : null)
                .orderByDesc(ExecutionRun::getStartedAt)
                .orderByDesc(ExecutionRun::getId)
                .list()
                .stream()
                .limit(normalizedLimit)
                .toList();
    }

    public List<ExecutionRun> listBySpace(
            Long spaceId,
            String runId,
            String status,
            String artifactCode,
            String platformPrincipalId,
            String threadId,
            LocalDateTime startedAfter,
            LocalDateTime startedBefore,
            Integer limit) {
        if (spaceId == null) {
            return List.of();
        }
        int normalizedLimit = normalizeLimit(limit);
        return lambdaQuery()
                .eq(ExecutionRun::getSpaceId, spaceId)
                .eq(StringUtils.hasText(runId), ExecutionRun::getRunId, runId != null ? runId.trim() : null)
                .eq(StringUtils.hasText(status), ExecutionRun::getStatus, status != null ? status.trim() : null)
                .eq(StringUtils.hasText(artifactCode), ExecutionRun::getArtifactCode, artifactCode != null ? artifactCode.trim() : null)
                .eq(StringUtils.hasText(platformPrincipalId), ExecutionRun::getPlatformPrincipalId,
                        platformPrincipalId != null ? platformPrincipalId.trim() : null)
                .eq(StringUtils.hasText(threadId), ExecutionRun::getThreadId, threadId != null ? threadId.trim() : null)
                .ge(startedAfter != null, ExecutionRun::getStartedAt, startedAfter)
                .le(startedBefore != null, ExecutionRun::getStartedAt, startedBefore)
                .orderByDesc(ExecutionRun::getStartedAt)
                .orderByDesc(ExecutionRun::getId)
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
