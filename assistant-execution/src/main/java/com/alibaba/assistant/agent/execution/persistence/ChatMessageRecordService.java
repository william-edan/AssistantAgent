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

import com.alibaba.assistant.agent.execution.persistence.mapper.ChatMessageRecordMapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Optional;

/**
 * Service for persisted chat-message rows.
 */
@Service
public class ChatMessageRecordService extends ServiceImpl<ChatMessageRecordMapper, ChatMessageRecord> {

    private static final int DEFAULT_LIST_LIMIT = 100;

    public Optional<ChatMessageRecord> findByMessageId(String messageId) {
        if (!StringUtils.hasText(messageId)) {
            return Optional.empty();
        }
        return Optional.ofNullable(lambdaQuery()
                .eq(ChatMessageRecord::getMessageId, messageId.trim())
                .orderByDesc(ChatMessageRecord::getId)
                .last("limit 1")
                .one());
    }

    public Optional<ChatMessageRecord> findBySourceKey(String sourceKey) {
        if (!StringUtils.hasText(sourceKey)) {
            return Optional.empty();
        }
        return Optional.ofNullable(lambdaQuery()
                .eq(ChatMessageRecord::getSourceKey, sourceKey.trim())
                .orderByDesc(ChatMessageRecord::getId)
                .last("limit 1")
                .one());
    }

    public List<ChatMessageRecord> listByThreadId(String threadId, String assistantUid, Integer limit) {
        if (!StringUtils.hasText(threadId) || !StringUtils.hasText(assistantUid)) {
            return List.of();
        }
        return lambdaQuery()
                .eq(ChatMessageRecord::getThreadId, threadId.trim())
                .eq(ChatMessageRecord::getAssistantUid, assistantUid.trim())
                .orderByAsc(ChatMessageRecord::getCreatedAt)
                .orderByAsc(ChatMessageRecord::getId)
                .list()
                .stream()
                .limit(normalizeLimit(limit))
                .toList();
    }

    public void saveOrUpdateBySourceKey(ChatMessageRecord messageRecord) {
        if (messageRecord == null) {
            return;
        }
        if (!StringUtils.hasText(messageRecord.getSourceKey())) {
            save(messageRecord);
            return;
        }
        ChatMessageRecord existing = findBySourceKey(messageRecord.getSourceKey()).orElse(null);
        if (existing == null) {
            save(messageRecord);
            return;
        }
        messageRecord.setId(existing.getId());
        if (!StringUtils.hasText(messageRecord.getMessageId())) {
            messageRecord.setMessageId(existing.getMessageId());
        }
        if (messageRecord.getCreatedAt() == null) {
            messageRecord.setCreatedAt(existing.getCreatedAt());
        }
        if (messageRecord.getRevisionNo() == null) {
            messageRecord.setRevisionNo(existing.getRevisionNo());
        }
        updateById(messageRecord);
    }

    private int normalizeLimit(Integer limit) {
        if (limit == null || limit <= 0) {
            return DEFAULT_LIST_LIMIT;
        }
        return Math.min(limit, 500);
    }
}
