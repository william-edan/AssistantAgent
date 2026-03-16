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
package com.alibaba.assistant.agent.api.service;

import com.alibaba.assistant.agent.api.controller.dto.ChatNotificationData;
import com.alibaba.assistant.agent.api.controller.dto.ChatNotificationListData;
import com.alibaba.assistant.agent.api.controller.dto.ChatNotificationReadData;
import com.alibaba.assistant.agent.execution.persistence.UserInboxNotification;
import com.alibaba.assistant.agent.execution.persistence.UserInboxNotificationService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 站内信读写服务。
 *
 * <p>负责查询当前用户的通知列表，并处理前端的已读动作。
 * 它是聊天线程、任务中心和站内信页面之间的通知桥接层。</p>
 */
@Service
@Profile("migration")
public class ChatNotificationService {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final UserInboxNotificationService userInboxNotificationService;

    private final ObjectMapper objectMapper;

    public ChatNotificationService(
            UserInboxNotificationService userInboxNotificationService,
            ObjectMapper objectMapper) {
        this.userInboxNotificationService = userInboxNotificationService;
        this.objectMapper = objectMapper;
    }

    public ChatNotificationListData listNotifications(String assistantUid, String status, Integer limit) {
        return new ChatNotificationListData(userInboxNotificationService.listByAssistantUid(assistantUid, status, limit)
                .stream()
                .map(this::toNotification)
                .toList());
    }

    public ChatNotificationReadData markRead(String assistantUid, String notificationId) {
        UserInboxNotification notification = requireOwnedNotification(assistantUid, notificationId);
        notification.setStatus("READ");
        notification.setReadAt(LocalDateTime.now());
        userInboxNotificationService.updateById(notification);
        return new ChatNotificationReadData(
                notification.getNotificationId(),
                notification.getTaskId(),
                notification.getStatus(),
                notification.getReadAt() != null ? notification.getReadAt().toString() : null);
    }

    public List<ChatNotificationData> listThreadNotifications(String assistantUid, String threadId, Integer limit) {
        return userInboxNotificationService.listByThreadId(assistantUid, threadId, limit).stream()
                .map(this::toNotification)
                .toList();
    }

    private UserInboxNotification requireOwnedNotification(String assistantUid, String notificationId) {
        UserInboxNotification notification = userInboxNotificationService.findLatestByNotificationId(notificationId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "chat_notification_not_found"));
        if (StringUtils.hasText(assistantUid)
                && StringUtils.hasText(notification.getAssistantUid())
                && !assistantUid.trim().equals(notification.getAssistantUid().trim())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "chat_notification_scope_denied");
        }
        return notification;
    }

    private ChatNotificationData toNotification(UserInboxNotification notification) {
        return new ChatNotificationData(
                notification.getNotificationId(),
                notification.getTaskId(),
                notification.getStatus(),
                notification.getTitle(),
                notification.getBody(),
                notification.getCreatedAt() != null ? notification.getCreatedAt().toString() : null,
                readMap(notification.getActionJson()));
    }

    private Map<String, Object> readMap(String json) {
        if (!StringUtils.hasText(json)) {
            return Map.of();
        }
        try {
            Map<String, Object> parsed = objectMapper.readValue(json, MAP_TYPE);
            return parsed != null ? new LinkedHashMap<>(parsed) : Map.of();
        }
        catch (Exception e) {
            return Map.of();
        }
    }
}
