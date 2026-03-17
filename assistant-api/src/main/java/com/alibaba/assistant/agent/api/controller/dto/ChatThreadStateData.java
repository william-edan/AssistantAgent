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

import com.alibaba.assistant.agent.api.service.ChatThreadActionSupport;
import com.alibaba.assistant.agent.common.chat.FrontendTaskStateSupport;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Persisted chat thread state payload.
 */
public record ChatThreadStateData(
        String threadId,
        String checkpointId,
        String status,
        String phase,
        boolean unfinished,
        boolean canResume,
        String toolCode,
        String rolePackageCode,
        String rolePackageVersion,
        String roleScenarioCode,
        String pendingCardType,
        Integer activeTaskCount,
        Integer unreadNotificationCount,
        String updatedAt,
        String lastMessage,
        Map<String, Object> pendingForm,
        Map<String, Object> lastResult,
        List<ChatTaskListItemData> tasks,
        List<ChatNotificationData> notifications,
        Map<String, Object> nextAction) {

    @SuppressWarnings("unchecked")
    public static ChatThreadStateData from(String threadId, String checkpointId, Map<String, Object> snapshot) {
        Map<String, Object> pendingForm = snapshot.get("pendingForm") instanceof Map<?, ?> pending
                ? new LinkedHashMap<>((Map<String, Object>) pending)
                : Map.of();
        Map<String, Object> lastResult = snapshot.get("lastResult") instanceof Map<?, ?> result
                ? new LinkedHashMap<>((Map<String, Object>) result)
                : Map.of();
        List<ChatTaskListItemData> tasks = normalizeTasks(snapshot.get("tasks"));
        List<ChatNotificationData> notifications = normalizeNotifications(snapshot.get("notifications"));
        String status = asText(snapshot.get("status"));
        String phase = asText(snapshot.get("phase"));
        boolean unfinished = Boolean.TRUE.equals(snapshot.get("unfinished"));
        boolean canResume = snapshot.containsKey("canResume")
                ? Boolean.TRUE.equals(snapshot.get("canResume"))
                : ChatThreadActionSupport.canResume(status, phase, unfinished);
        Integer activeTaskCount = asInteger(snapshot.get("activeTaskCount"));
        if (activeTaskCount == null) {
            activeTaskCount = (int) tasks.stream()
                    .filter(task -> !ChatThreadActionSupport.isTerminalStatus(task.status()))
                    .count();
        }
        Integer unreadNotificationCount = asInteger(snapshot.get("unreadNotificationCount"));
        if (unreadNotificationCount == null) {
            unreadNotificationCount = (int) notifications.stream()
                    .filter(notification -> "UNREAD".equalsIgnoreCase(asText(notification.status())))
                    .count();
        }
        String pendingCardType = asText(snapshot.get("pendingCardType"));
        if (pendingCardType == null) {
            pendingCardType = !pendingForm.isEmpty() ? "FORM_CARD" : (!tasks.isEmpty() ? "TASK_CARD" : null);
        }
        return new ChatThreadStateData(
                threadId,
                checkpointId,
                status,
                phase,
                unfinished,
                canResume,
                asText(snapshot.get("toolCode")),
                asText(snapshot.get("rolePackageCode")),
                asText(snapshot.get("rolePackageVersion")),
                asText(snapshot.get("roleScenarioCode")),
                pendingCardType,
                activeTaskCount,
                unreadNotificationCount,
                asText(snapshot.get("updatedAt")),
                asText(snapshot.get("lastMessage")),
                pendingForm,
                lastResult,
                tasks,
                notifications,
                ChatThreadActionSupport.nextAction(threadId, status, phase, unfinished));
    }

    @SuppressWarnings("unchecked")
    private static List<ChatTaskListItemData> normalizeTasks(Object rawTasks) {
        if (!(rawTasks instanceof List<?> list) || list.isEmpty()) {
            return List.of();
        }
        return list.stream()
                .map(item -> {
                    if (item instanceof ChatTaskListItemData task) {
                        return task;
                    }
                    if (item instanceof Map<?, ?> map) {
                        Map<String, Object> task = FrontendTaskStateSupport.normalizePayload(
                                new LinkedHashMap<>((Map<String, Object>) map));
                        return new ChatTaskListItemData(
                                asText(task.get("taskId")),
                                asText(task.get("threadId")),
                                asText(task.get("taskType")),
                                asText(task.get("title")),
                                asText(task.get("status")),
                                asText(task.get("sourceType")),
                                asText(task.get("sourceCode")),
                                asInteger(task.get("progressPercent")),
                                !Boolean.FALSE.equals(task.get("collapsible")),
                                Boolean.TRUE.equals(task.get("resultReady")),
                                asText(task.get("createdAt")),
                                asText(task.get("completedAt")),
                                firstText(task.get("latestOutput"), task.get("latestOutputText"),
                                        task.get("text"), task.get("stepName")),
                                asText(task.get("summaryText")),
                                Boolean.TRUE.equals(task.get("background")),
                                Boolean.TRUE.equals(task.get("detached")),
                                task.get("display") instanceof Map<?, ?> displayMap
                                        ? new LinkedHashMap<>((Map<String, Object>) displayMap)
                                        : Map.of());
                    }
                    return null;
                })
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    @SuppressWarnings("unchecked")
    private static List<ChatNotificationData> normalizeNotifications(Object rawNotifications) {
        if (!(rawNotifications instanceof List<?> list) || list.isEmpty()) {
            return List.of();
        }
        return list.stream()
                .map(item -> {
                    if (item instanceof ChatNotificationData notification) {
                        return notification;
                    }
                    if (item instanceof Map<?, ?> map) {
                        Map<String, Object> notification = new LinkedHashMap<>((Map<String, Object>) map);
                        Map<String, Object> action = notification.get("action") instanceof Map<?, ?> actionMap
                                ? new LinkedHashMap<>((Map<String, Object>) actionMap)
                                : Map.of();
                        return new ChatNotificationData(
                                asText(notification.get("notificationId")),
                                asText(notification.get("taskId")),
                                asText(notification.get("status")),
                                asText(notification.get("title")),
                                asText(notification.get("body")),
                                asText(notification.get("createdAt")),
                                action);
                    }
                    return null;
                })
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    private static String asText(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }

    private static Integer asInteger(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        String text = asText(value);
        if (text == null) {
            return null;
        }
        try {
            return Integer.parseInt(text);
        }
        catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static String firstText(Object... values) {
        if (values == null) {
            return null;
        }
        for (Object value : values) {
            String text = asText(value);
            if (text != null) {
                return text;
            }
        }
        return null;
    }
}
