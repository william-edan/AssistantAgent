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

import com.alibaba.assistant.agent.execution.persistence.mapper.ChatThreadRecordMapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Optional;

/**
 * Service for persisted chat-thread summaries.
 */
@Service
public class ChatThreadRecordService extends ServiceImpl<ChatThreadRecordMapper, ChatThreadRecord> {

    private static final int DEFAULT_LIST_LIMIT = 20;

    public Optional<ChatThreadRecord> findByThreadId(String threadId) {
        if (!StringUtils.hasText(threadId)) {
            return Optional.empty();
        }
        return Optional.ofNullable(lambdaQuery()
                .eq(ChatThreadRecord::getThreadId, threadId.trim())
                .orderByDesc(ChatThreadRecord::getId)
                .last("limit 1")
                .one());
    }

    public List<ChatThreadRecord> listByAssistantUid(String assistantUid, Integer limit) {
        if (!StringUtils.hasText(assistantUid)) {
            return List.of();
        }
        return lambdaQuery()
                .eq(ChatThreadRecord::getAssistantUid, assistantUid.trim())
                .orderByDesc(ChatThreadRecord::getUpdatedAt)
                .orderByDesc(ChatThreadRecord::getId)
                .list()
                .stream()
                .limit(normalizeLimit(limit))
                .toList();
    }

    public void saveOrUpdateByThreadId(ChatThreadRecord threadRecord) {
        if (threadRecord == null || !StringUtils.hasText(threadRecord.getThreadId())) {
            return;
        }
        ChatThreadRecord existing = findByThreadId(threadRecord.getThreadId()).orElse(null);
        if (existing == null) {
            save(threadRecord);
            return;
        }
        threadRecord.setId(existing.getId());
        if (threadRecord.getCreatedAt() == null) {
            threadRecord.setCreatedAt(existing.getCreatedAt());
        }
        updateById(threadRecord);
    }

    private int normalizeLimit(Integer limit) {
        if (limit == null || limit <= 0) {
            return DEFAULT_LIST_LIMIT;
        }
        return Math.min(limit, 100);
    }
}
