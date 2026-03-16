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

import com.alibaba.assistant.agent.execution.persistence.mapper.UserInboxNotificationMapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Optional;

/**
 * Service for user inbox notifications.
 */
@Service
public class UserInboxNotificationService extends ServiceImpl<UserInboxNotificationMapper, UserInboxNotification> {

    private static final int DEFAULT_LIST_LIMIT = 20;

    public Optional<UserInboxNotification> findLatestByNotificationId(String notificationId) {
        if (!StringUtils.hasText(notificationId)) {
            return Optional.empty();
        }
        return Optional.ofNullable(lambdaQuery()
                .eq(UserInboxNotification::getNotificationId, notificationId.trim())
                .orderByDesc(UserInboxNotification::getId)
                .last("limit 1")
                .one());
    }

    public List<UserInboxNotification> listByAssistantUid(String assistantUid, String status, Integer limit) {
        if (!StringUtils.hasText(assistantUid)) {
            return List.of();
        }
        return lambdaQuery()
                .eq(UserInboxNotification::getAssistantUid, assistantUid.trim())
                .eq(StringUtils.hasText(status), UserInboxNotification::getStatus, status != null ? status.trim() : null)
                .orderByDesc(UserInboxNotification::getCreatedAt)
                .orderByDesc(UserInboxNotification::getId)
                .list()
                .stream()
                .limit(normalizeLimit(limit))
                .toList();
    }

    public List<UserInboxNotification> listByThreadId(String assistantUid, String threadId, Integer limit) {
        if (!StringUtils.hasText(assistantUid) || !StringUtils.hasText(threadId)) {
            return List.of();
        }
        return lambdaQuery()
                .eq(UserInboxNotification::getAssistantUid, assistantUid.trim())
                .eq(UserInboxNotification::getThreadId, threadId.trim())
                .orderByDesc(UserInboxNotification::getCreatedAt)
                .orderByDesc(UserInboxNotification::getId)
                .list()
                .stream()
                .limit(normalizeLimit(limit))
                .toList();
    }

    public int countUnreadByAssistantUidAndThreadId(String assistantUid, String threadId) {
        if (!StringUtils.hasText(assistantUid) || !StringUtils.hasText(threadId)) {
            return 0;
        }
        return Math.toIntExact(lambdaQuery()
                .eq(UserInboxNotification::getAssistantUid, assistantUid.trim())
                .eq(UserInboxNotification::getThreadId, threadId.trim())
                .eq(UserInboxNotification::getStatus, "UNREAD")
                .count());
    }

    public void saveOrUpdateByNotificationId(UserInboxNotification notification) {
        if (notification == null || !StringUtils.hasText(notification.getNotificationId())) {
            return;
        }
        UserInboxNotification existing = findLatestByNotificationId(notification.getNotificationId()).orElse(null);
        if (existing == null) {
            save(notification);
            return;
        }
        notification.setId(existing.getId());
        updateById(notification);
    }

    private int normalizeLimit(Integer limit) {
        if (limit == null || limit <= 0) {
            return DEFAULT_LIST_LIMIT;
        }
        return Math.min(limit, 100);
    }
}
