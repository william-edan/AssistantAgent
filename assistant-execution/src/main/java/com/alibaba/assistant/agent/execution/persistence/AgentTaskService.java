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

import com.alibaba.assistant.agent.execution.persistence.mapper.AgentTaskMapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Optional;

/**
 * Service for persisted user task queries.
 */
@Service
public class AgentTaskService extends ServiceImpl<AgentTaskMapper, AgentTask> {

    private static final int DEFAULT_LIST_LIMIT = 20;

    public Optional<AgentTask> findLatestByTaskId(String taskId) {
        if (!StringUtils.hasText(taskId)) {
            return Optional.empty();
        }
        return Optional.ofNullable(lambdaQuery()
                .eq(AgentTask::getTaskId, taskId.trim())
                .orderByDesc(AgentTask::getId)
                .last("limit 1")
                .one());
    }

    public List<AgentTask> listByAssistantUid(
            String assistantUid,
            String threadId,
            String status,
            Integer limit) {
        if (!StringUtils.hasText(assistantUid)) {
            return List.of();
        }
        return lambdaQuery()
                .eq(AgentTask::getAssistantUid, assistantUid.trim())
                .eq(StringUtils.hasText(threadId), AgentTask::getThreadId, threadId != null ? threadId.trim() : null)
                .eq(StringUtils.hasText(status), AgentTask::getStatus, status != null ? status.trim() : null)
                .orderByDesc(AgentTask::getUpdatedAt)
                .orderByDesc(AgentTask::getId)
                .list()
                .stream()
                .limit(normalizeLimit(limit))
                .toList();
    }

    public int countActiveByAssistantUidAndThreadId(String assistantUid, String threadId) {
        if (!StringUtils.hasText(assistantUid) || !StringUtils.hasText(threadId)) {
            return 0;
        }
        return Math.toIntExact(lambdaQuery()
                .eq(AgentTask::getAssistantUid, assistantUid.trim())
                .eq(AgentTask::getThreadId, threadId.trim())
                .notIn(AgentTask::getStatus, List.of("COMPLETED", "DONE", "FAILED", "ERROR"))
                .count());
    }

    public void saveOrUpdateByTaskId(AgentTask task) {
        if (task == null || !StringUtils.hasText(task.getTaskId())) {
            return;
        }
        AgentTask existing = findLatestByTaskId(task.getTaskId()).orElse(null);
        if (existing == null) {
            save(task);
            return;
        }
        task.setId(existing.getId());
        updateById(task);
    }

    private int normalizeLimit(Integer limit) {
        if (limit == null || limit <= 0) {
            return DEFAULT_LIST_LIMIT;
        }
        return Math.min(limit, 100);
    }
}
