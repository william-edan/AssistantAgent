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
package com.alibaba.assistant.agent.common.chat;

import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Canonical normalization rules for user-facing long-running task payloads.
 */
@SuppressWarnings("unchecked")
public final class FrontendTaskStateSupport {

    public static final String STATUS_RUNNING = "RUNNING";

    public static final String STATUS_WAITING_APPROVAL = "WAITING_APPROVAL";

    public static final String STATUS_COMPLETED = "COMPLETED";

    public static final String STATUS_FAILED = "FAILED";

    private FrontendTaskStateSupport() {
    }

    public static Map<String, Object> normalizePayload(Map<String, Object> payload) {
        Map<String, Object> raw = unwrapTaskPayload(payload);
        if (raw.isEmpty()) {
            return Map.of();
        }
        String taskId = firstText(raw.get("taskId"), raw.get("runId"));
        String title = firstText(raw.get("title"), raw.get("sourceLabel"), raw.get("sourceCode"), "后台任务");
        String status = normalizedStatus(raw);
        boolean terminal = isTerminalStatus(status);
        boolean collapsible = !Boolean.FALSE.equals(raw.get("collapsible"));
        boolean detached = Boolean.TRUE.equals(raw.get("detached")) || Boolean.TRUE.equals(raw.get("background"));
        boolean background = Boolean.TRUE.equals(raw.get("background")) || detached;

        Map<String, Object> normalized = new LinkedHashMap<>();
        putText(normalized, "taskId", taskId);
        putText(normalized, "runId", firstText(raw.get("runId"), taskId));
        putText(normalized, "taskType", firstText(raw.get("taskType"), "AGENT_TASK"));
        putText(normalized, "title", title);
        putText(normalized, "status", status);
        putText(normalized, "phase", normalizedPhase(status));
        putText(normalized, "sourceType", firstText(raw.get("sourceType"), "AGENT_TASK"));
        putText(normalized, "sourceCode", firstText(raw.get("sourceCode"), raw.get("artifactCode"), raw.get("toolCode")));
        putText(normalized, "sourceLabel", firstText(raw.get("sourceLabel"), title));
        putText(normalized, "eventType", firstText(raw.get("eventType"), raw.get("type")));
        putText(normalized, "parentTaskId", firstText(raw.get("parentTaskId"), raw.get("parentRunId")));
        putText(normalized, "approvalRequestId", firstText(raw.get("approvalRequestId")));

        Integer progressPercent = normalizedProgressPercent(raw, status);
        if (progressPercent != null) {
            normalized.put("progressPercent", progressPercent);
        }

        normalized.put("collapsible", collapsible);
        normalized.put("background", background);
        normalized.put("detached", detached);
        normalized.put("resultReady", Boolean.TRUE.equals(raw.get("resultReady")) || terminal);
        if (raw.containsKey("internal")) {
            normalized.put("internal", Boolean.TRUE.equals(raw.get("internal")));
        }
        putText(normalized, "toolType", firstText(raw.get("toolType")));
        putText(normalized, "visibility", firstText(raw.get("visibility")));
        putText(normalized, "invocationPolicy", firstText(raw.get("invocationPolicy")));

        List<Map<String, Object>> liveOutput = normalizeLiveOutput(raw);
        if (!liveOutput.isEmpty()) {
            normalized.put("liveOutput", liveOutput);
        }

        Map<String, Object> resultPreview = asMap(firstNonEmpty(raw.get("resultPreview"), raw.get("result")));
        if (!resultPreview.isEmpty()) {
            normalized.put("resultPreview", resultPreview);
        }

        Map<String, Object> action = normalizeAction(raw, taskId);
        if (!action.isEmpty()) {
            normalized.put("action", action);
        }

        Map<String, Object> display = normalizeDisplay(
                raw,
                collapsible,
                background,
                detached,
                terminal,
                !liveOutput.isEmpty(),
                !resultPreview.isEmpty());
        if (!display.isEmpty()) {
            normalized.put("display", display);
        }

        Map<String, Object> notification = normalizeNotification(raw, taskId, title, status, action, terminal);
        if (!notification.isEmpty()) {
            normalized.put("notification", notification);
        }

        String summaryText = summaryText(normalized);
        if (StringUtils.hasText(summaryText)) {
            normalized.put("summaryText", summaryText);
        }
        return normalized;
    }

    public static String normalizedStatus(Map<String, Object> payload) {
        if (payload == null || payload.isEmpty()) {
            return STATUS_RUNNING;
        }
        String status = firstText(payload.get("status"), payload.get("state"), payload.get("lifecycleStatus"));
        if (!StringUtils.hasText(status)) {
            return STATUS_RUNNING;
        }
        String normalized = status.trim().toUpperCase();
        return switch (normalized) {
            case "WAITING_APPROVAL", "PENDING_APPROVAL", "PENDING_REVIEW" -> STATUS_WAITING_APPROVAL;
            case "DONE", "COMPLETE", "COMPLETED", "SUCCESS", "SUCCEEDED", "FINISHED" -> STATUS_COMPLETED;
            case "FAILED", "ERROR", "FAIL", "CANCELLED", "CANCELED", "TIMEOUT" -> STATUS_FAILED;
            default -> STATUS_RUNNING;
        };
    }

    public static String normalizedPhase(Map<String, Object> payload) {
        return normalizedPhase(normalizedStatus(payload));
    }

    public static String normalizedPhase(String status) {
        String normalized = firstText(status, STATUS_RUNNING);
        return switch (normalized) {
            case STATUS_WAITING_APPROVAL -> "WAITING_APPROVAL";
            case STATUS_COMPLETED -> "DONE";
            case STATUS_FAILED -> "ERROR";
            default -> "EXECUTING";
        };
    }

    public static boolean isTerminalStatus(String status) {
        return STATUS_COMPLETED.equalsIgnoreCase(status) || STATUS_FAILED.equalsIgnoreCase(status);
    }

    public static String summaryText(Map<String, Object> payload) {
        if (payload == null || payload.isEmpty()) {
            return null;
        }
        String explicitSummary = firstText(payload.get("summaryText"));
        if (StringUtils.hasText(explicitSummary)) {
            return explicitSummary;
        }

        String status = normalizedStatus(payload);
        String resultText = resultSummaryText(payload);
        if (isTerminalStatus(status) && StringUtils.hasText(resultText)) {
            return resultText;
        }

        List<Map<String, Object>> liveOutput = asListOfMaps(payload.get("liveOutput"));
        if (!liveOutput.isEmpty()) {
            Map<String, Object> latest = liveOutput.get(liveOutput.size() - 1);
            String liveText = firstText(latest.get("text"), latest.get("stepName"), latest.get("eventType"));
            if (StringUtils.hasText(liveText)) {
                Integer progress = intValue(payload.get("progressPercent"));
                if (progress != null && progress > 0 && progress < 100) {
                    return liveText + " (" + progress + "%)";
                }
                return liveText;
            }
        }

        if (StringUtils.hasText(resultText)) {
            return resultText;
        }

        String title = firstText(payload.get("title"), payload.get("sourceLabel"), payload.get("sourceCode"));
        Integer progress = intValue(payload.get("progressPercent"));
        if (StringUtils.hasText(title) && progress != null && progress > 0 && progress < 100) {
            return title + " (" + progress + "%)";
        }
        return firstText(title, payload.get("status"));
    }

    private static String resultSummaryText(Map<String, Object> payload) {
        Map<String, Object> resultPreview = asMap(firstNonEmpty(payload.get("resultPreview"), payload.get("result")));
        return firstText(resultPreview.get("summary"), resultPreview.get("text"), resultPreview.get("reportId"),
                resultPreview.get("leave_id"));
    }
    public static Map<String, Object> extractMeta(Map<String, Object> normalizedPayload) {
        if (normalizedPayload == null || normalizedPayload.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> meta = new LinkedHashMap<>();
        putText(meta, "sourceLabel", firstText(normalizedPayload.get("sourceLabel")));
        putText(meta, "summaryText", summaryText(normalizedPayload));
        if (normalizedPayload.containsKey("background")) {
            meta.put("background", normalizedPayload.get("background"));
        }
        if (normalizedPayload.containsKey("detached")) {
            meta.put("detached", normalizedPayload.get("detached"));
        }
        if (normalizedPayload.containsKey("internal")) {
            meta.put("internal", normalizedPayload.get("internal"));
        }
        putText(meta, "toolType", firstText(normalizedPayload.get("toolType")));
        putText(meta, "visibility", firstText(normalizedPayload.get("visibility")));
        putText(meta, "invocationPolicy", firstText(normalizedPayload.get("invocationPolicy")));
        Map<String, Object> display = asMap(normalizedPayload.get("display"));
        if (!display.isEmpty()) {
            meta.put("display", display);
        }
        return meta;
    }

    private static Integer normalizedProgressPercent(Map<String, Object> payload, String status) {
        Integer progress = intValue(payload.get("progressPercent"));
        if (progress != null) {
            return Math.max(0, Math.min(progress, 100));
        }
        return isTerminalStatus(status) ? 100 : null;
    }

    private static List<Map<String, Object>> normalizeLiveOutput(Map<String, Object> payload) {
        List<Map<String, Object>> items = new ArrayList<>();
        Object rawLiveOutput = payload.get("liveOutput");
        if (rawLiveOutput instanceof List<?> list) {
            for (Object item : list) {
                Map<String, Object> normalized = normalizeLiveOutputItem(asMap(item));
                if (!normalized.isEmpty()) {
                    items.add(normalized);
                }
            }
            if (!items.isEmpty()) {
                return items;
            }
        }
        Map<String, Object> fallback = normalizeLiveOutputItem(payload);
        if (!fallback.isEmpty()) {
            items.add(fallback);
        }
        return items;
    }

    private static Map<String, Object> normalizeLiveOutputItem(Map<String, Object> payload) {
        if (payload == null || payload.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> nestedPayload = asMap(payload.get("payload"));
        Map<String, Object> outputs = asMap(firstNonEmpty(payload.get("outputs"), nestedPayload.get("outputs")));
        String stepName = firstText(payload.get("stepName"), nestedPayload.get("stepName"));
        String text = firstText(payload.get("text"), payload.get("message"), nestedPayload.get("text"),
                nestedPayload.get("message"), stepName);
        String eventType = firstText(payload.get("eventType"), payload.get("type"), nestedPayload.get("eventType"));
        String status = firstText(payload.get("status"), nestedPayload.get("status"));
        if (!StringUtils.hasText(text) && outputs.isEmpty() && !StringUtils.hasText(eventType)) {
            return Map.of();
        }

        Map<String, Object> item = new LinkedHashMap<>();
        putText(item, "eventId", firstText(payload.get("eventId")));
        Long sequence = longValue(firstNonEmpty(payload.get("sequence"), payload.get("sequenceNo")));
        if (sequence != null) {
            item.put("sequence", sequence);
        }
        putText(item, "eventType", firstText(eventType, "PROGRESS"));
        putText(item, "status", status);
        putText(item, "stepName", stepName);
        putText(item, "text", firstText(text, eventType));
        putText(item, "level", firstText(payload.get("level"), nestedPayload.get("level"), "INFO"));
        putText(item, "occurredAt", firstText(payload.get("occurredAt"), payload.get("createdAt"), Instant.now().toString()));
        if (!outputs.isEmpty()) {
            item.put("outputs", outputs);
        }
        return item;
    }

    private static Map<String, Object> normalizeAction(Map<String, Object> payload, String taskId) {
        Map<String, Object> rawAction = asMap(payload.get("action"));
        if (!rawAction.isEmpty()) {
            Map<String, Object> action = new LinkedHashMap<>(rawAction);
            putText(action, "type", firstText(action.get("type"), "TASK_DETAIL"));
            putText(action, "targetId", firstText(action.get("targetId"), taskId));
            putText(action, "threadId", firstText(action.get("threadId"), payload.get("threadId")));
            return action;
        }
        if (!StringUtils.hasText(taskId)) {
            return Map.of();
        }
        Map<String, Object> action = new LinkedHashMap<>();
        action.put("type", "TASK_DETAIL");
        action.put("targetId", taskId);
        putText(action, "threadId", firstText(payload.get("threadId")));
        return action;
    }

    private static Map<String, Object> normalizeDisplay(
            Map<String, Object> payload,
            boolean collapsible,
            boolean background,
            boolean detached,
            boolean terminal,
            boolean hasLiveOutput,
            boolean hasResultPreview) {
        Map<String, Object> rawDisplay = asMap(firstNonEmpty(payload.get("display"), payload.get("view")));
        Map<String, Object> display = new LinkedHashMap<>();
        display.put("showInChat", rawDisplay.getOrDefault("showInChat", Boolean.TRUE));
        display.put("showInTaskCenter", rawDisplay.getOrDefault("showInTaskCenter", Boolean.TRUE));
        display.put("showInInbox", rawDisplay.getOrDefault("showInInbox",
                !asMap(payload.get("notification")).isEmpty() || terminal || background || detached));
        display.put("foldable", rawDisplay.getOrDefault("foldable", collapsible));
        display.put("collapsedByDefault", rawDisplay.getOrDefault("collapsedByDefault", collapsible));
        display.put("showLiveOutput", rawDisplay.getOrDefault("showLiveOutput", hasLiveOutput));
        display.put("showResultPreview", rawDisplay.getOrDefault("showResultPreview", hasResultPreview || terminal));
        display.put("detached", rawDisplay.getOrDefault("detached", detached));
        display.put("background", rawDisplay.getOrDefault("background", background));
        return display;
    }

    private static Map<String, Object> normalizeNotification(
            Map<String, Object> payload,
            String taskId,
            String title,
            String status,
            Map<String, Object> action,
            boolean terminal) {
        Map<String, Object> rawNotification = asMap(payload.get("notification"));
        if (rawNotification.isEmpty() && !terminal) {
            return Map.of();
        }
        Map<String, Object> notification = new LinkedHashMap<>(rawNotification);
        if (!StringUtils.hasText(firstText(notification.get("notificationId"))) && StringUtils.hasText(taskId)) {
            notification.put("notificationId", taskId + ":" + status);
        }
        putText(notification, "taskId", firstText(notification.get("taskId"), taskId));
        putText(notification, "status", firstText(notification.get("status"), "UNREAD"));
        putText(notification, "title", firstText(notification.get("title"), defaultNotificationTitle(title, status)));
        putText(notification, "body", firstText(notification.get("body"), defaultNotificationBody(status)));
        putText(notification, "createdAt", firstText(notification.get("createdAt"), Instant.now().toString()));
        if (!notification.containsKey("action") && !action.isEmpty()) {
            notification.put("action", action);
        }
        return notification;
    }

    private static String defaultNotificationTitle(String title, String status) {
        String taskTitle = firstText(title, "后台任务");
        return STATUS_COMPLETED.equalsIgnoreCase(status)
                ? taskTitle + " 已完成"
                : taskTitle + " 执行失败";
    }

    private static String defaultNotificationBody(String status) {
        return STATUS_COMPLETED.equalsIgnoreCase(status) ? "点击查看任务结果" : "点击查看失败详情";
    }

    private static Map<String, Object> unwrapTaskPayload(Map<String, Object> payload) {
        Map<String, Object> task = asMap(payload != null ? payload.get("task") : null);
        return task.isEmpty() ? (payload != null ? payload : Map.of()) : task;
    }

    private static void putText(Map<String, Object> target, String key, String value) {
        if (target != null && StringUtils.hasText(key) && StringUtils.hasText(value)) {
            target.put(key, value);
        }
    }

    private static Map<String, Object> asMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            return new LinkedHashMap<>((Map<String, Object>) map);
        }
        return Map.of();
    }

    private static List<Map<String, Object>> asListOfMaps(Object value) {
        if (!(value instanceof List<?> list) || list.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> normalized = new ArrayList<>();
        for (Object item : list) {
            Map<String, Object> map = asMap(item);
            if (!map.isEmpty()) {
                normalized.add(map);
            }
        }
        return normalized;
    }

    private static String firstText(Object... values) {
        if (values == null) {
            return null;
        }
        for (Object value : values) {
            if (value == null) {
                continue;
            }
            String text = String.valueOf(value).trim();
            if (StringUtils.hasText(text)) {
                return text;
            }
        }
        return null;
    }

    private static Object firstNonEmpty(Object... values) {
        if (values == null) {
            return null;
        }
        for (Object value : values) {
            if (value == null) {
                continue;
            }
            if (value instanceof Map<?, ?> map && map.isEmpty()) {
                continue;
            }
            if (value instanceof List<?> list && list.isEmpty()) {
                continue;
            }
            if (value instanceof String text && !StringUtils.hasText(text)) {
                continue;
            }
            return value;
        }
        return null;
    }

    private static Integer intValue(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value).trim());
        }
        catch (Exception ignored) {
            return null;
        }
    }

    private static Long longValue(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(value).trim());
        }
        catch (Exception ignored) {
            return null;
        }
    }
}



