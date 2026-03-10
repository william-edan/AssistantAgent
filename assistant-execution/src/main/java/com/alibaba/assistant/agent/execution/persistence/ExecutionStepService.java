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

import com.alibaba.assistant.agent.execution.persistence.mapper.ExecutionStepMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Optional;

/**
 * Service for execution-step persistence lookups.
 */
@Service
public class ExecutionStepService extends ServiceImpl<ExecutionStepMapper, ExecutionStep> {

    public List<ExecutionStep> listByRunId(String runId) {
        if (!StringUtils.hasText(runId)) {
            return List.of();
        }
        LambdaQueryWrapper<ExecutionStep> query = new LambdaQueryWrapper<>();
        query.eq(ExecutionStep::getRunId, runId.trim());
        query.orderByAsc(ExecutionStep::getId);
        return list(query);
    }

    public Optional<ExecutionStep> findByRunIdAndStepId(String runId, String stepId) {
        if (!StringUtils.hasText(runId) || !StringUtils.hasText(stepId)) {
            return Optional.empty();
        }
        LambdaQueryWrapper<ExecutionStep> query = new LambdaQueryWrapper<>();
        query.eq(ExecutionStep::getRunId, runId.trim());
        query.eq(ExecutionStep::getStepId, stepId.trim());
        query.orderByDesc(ExecutionStep::getId);
        return Optional.ofNullable(getOne(query, false));
    }
}
