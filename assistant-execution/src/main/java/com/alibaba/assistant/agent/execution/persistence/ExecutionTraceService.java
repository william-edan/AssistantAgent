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

import com.alibaba.assistant.agent.execution.persistence.mapper.ExecutionTraceMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Persistence service for business execution traces.
 */
@Service
public class ExecutionTraceService extends ServiceImpl<ExecutionTraceMapper, ExecutionTrace> {

    public Optional<ExecutionTrace> findLatestByRunId(String runId) {
        if (!StringUtils.hasText(runId)) {
            return Optional.empty();
        }
        LambdaQueryWrapper<ExecutionTrace> query = new LambdaQueryWrapper<>();
        query.eq(ExecutionTrace::getRunId, runId.trim());
        query.orderByDesc(ExecutionTrace::getId);
        return Optional.ofNullable(getOne(query, false));
    }

    public List<ExecutionTrace> listByRoleScenario(
            Long spaceId,
            String agentAppCode,
            String rolePackageCode,
            String scenarioCode,
            LocalDateTime startedAfter,
            LocalDateTime startedBefore) {
        if (spaceId == null
                || !StringUtils.hasText(agentAppCode)
                || !StringUtils.hasText(rolePackageCode)
                || !StringUtils.hasText(scenarioCode)) {
            return List.of();
        }
        return lambdaQuery()
                .eq(ExecutionTrace::getSpaceId, spaceId)
                .eq(ExecutionTrace::getAgentAppCode, agentAppCode.trim())
                .eq(ExecutionTrace::getRolePackageCode, rolePackageCode.trim())
                .eq(ExecutionTrace::getScenarioCode, scenarioCode.trim())
                .ge(startedAfter != null, ExecutionTrace::getStartedAt, startedAfter)
                .le(startedBefore != null, ExecutionTrace::getStartedAt, startedBefore)
                .orderByDesc(ExecutionTrace::getStartedAt)
                .orderByDesc(ExecutionTrace::getId)
                .list();
    }
}
