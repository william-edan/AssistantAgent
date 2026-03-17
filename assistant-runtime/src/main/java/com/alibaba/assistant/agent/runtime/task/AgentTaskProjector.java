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
package com.alibaba.assistant.agent.runtime.task;

import com.alibaba.assistant.agent.common.chat.FrontendTaskStateSupport;
import com.alibaba.assistant.agent.execution.flow.FlowContext;
import com.alibaba.assistant.agent.execution.persistence.AgentTask;
import com.alibaba.assistant.agent.execution.persistence.AgentTaskEvent;
import com.alibaba.assistant.agent.execution.persistence.AgentTaskEventService;
import com.alibaba.assistant.agent.execution.persistence.AgentTaskService;
import com.alibaba.assistant.agent.execution.persistence.UserInboxNotification;
import com.alibaba.assistant.agent.execution.persistence.UserInboxNotificationService;
import com.alibaba.assistant.agent.runtime.agent.AssistantStateKeys;
import com.alibaba.assistant.agent.runtime.execution.ExecutionEvent;
import com.alibaba.assistant.agent.runtime.registry.PublishedToolDescriptor;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 将运行时任务投影到任务中心和站内信读模型。
 */
@Service
@Profile("migration")
public class AgentTaskProjector {

    private final AgentTaskService agentTaskService;
    private final AgentTaskEventService agentTaskEventService;
    private final UserInboxNotificationService userInboxNotificationService;
    private final ObjectMapper objectMapper;

    public AgentTaskProjector(
            AgentTaskService agentTaskService,
            AgentTaskEventService agentTaskEventService,
            UserInboxNotificationService userInboxNotificationService,
            ObjectMapper objectMapper) {
        this.agentTaskService = agentTaskService;
        this.agentTaskEventService = agentTaskEventService;
        this.userInboxNotificationService = userInboxNotificationService;
        this.objectMapper = objectMapper;
    }

    public void recordExecutionEvent(PublishedToolDescriptor descriptor, FlowContext flowContext, ExecutionEvent event) {
        if (descriptor == null || flowContext == null || event == null) {
            return;
        }
        if (!descriptor.isUserVisible() || isInternalExecutionEvent(event)) {
            return;
        }
        String taskId = text(event.runId());
        String assistantUid = text(flowContext.getAssistantUid());
        if (!StringUtils.hasText(taskId) || !StringUtils.hasText(assistantUid)) {
            return;
        }
        AgentTask task = agentTaskService.findLatestByTaskId(taskId).orElseGet(AgentTask::new);
        String previousStatus = task.getStatus();
        String incomingStatus = mapExecutionStatus(event);
        if (!shouldUpdateStatus(task.getStatus(), incomingStatus)) {
            incomingStatus = task.getStatus();
        }
        task.setTaskId(taskId);
        task.setRunId(taskId);
        task.setThreadId(text(flowContext.getThreadId()));
        task.setAssistantUid(assistantUid);
        task.setTaskType("ARTIFACT_EXECUTION");
        task.setSourceType("ARTIFACT_EXECUTION");
        task.setSourceCode(text(event.artifactCode()));
        task.setTitle(resolveExecutionTitle(descriptor, event));
        task.setStatus(incomingStatus);
        task.setProgressPercent(resolveExecutionProgressPercent(descriptor, event));
        task.setCollapsible(Boolean.TRUE);
        task.setBackground(Boolean.FALSE);
        task.setDetached(Boolean.FALSE);
        task.setResultReady(isTerminalStatus(incomingStatus));
        task.setLatestOutputJson(serialize(buildExecutionOutput(event)));
        task.setActionJson(serialize(buildAction(taskId, task.getThreadId())));
        if (isTerminalStatus(incomingStatus)) {
            task.setCompletedAt(asLocalDateTime(event.occurredAt()));
            task.setResultPreviewJson(serialize(buildExecutionResultPreview(event)));
        }
        agentTaskService.saveOrUpdateByTaskId(task);

        AgentTaskEvent taskEvent = new AgentTaskEvent();
        taskEvent.setEventId(buildExecutionEventId(event));
        taskEvent.setTaskId(taskId);
        taskEvent.setThreadId(task.getThreadId());
        taskEvent.setAssistantUid(assistantUid);
        taskEvent.setEventType(event.eventType() != null ? event.eventType().name() : "EXECUTION_EVENT");
        taskEvent.setStatus(incomingStatus);
        taskEvent.setSequenceNo(event.sequence());
        taskEvent.setPayloadJson(serialize(buildExecutionOutput(event)));
        taskEvent.setCreatedAt(asLocalDateTime(event.occurredAt()));
        agentTaskEventService.saveIfAbsent(taskEvent);

        if (becameTerminal(previousStatus, incomingStatus)) {
            upsertNotification(task, successStatus(incomingStatus), buildExecutionNotificationBody(event));
        }
    }

    public void recordTaskState(String threadId, Map<String, Object> stateSnapshot, Map<String, Object> taskPayload) {
        Map<String, Object> normalizedPayload = FrontendTaskStateSupport.normalizePayload(taskPayload);
        if (isInternalTaskPayload(normalizedPayload)) {
            return;
        }
        String taskId = text(normalizedPayload.get("taskId"));
        String assistantUid = text(stateSnapshot != null ? stateSnapshot.get(AssistantStateKeys.ASSISTANT_UID) : null);
        if (!StringUtils.hasText(taskId) || !StringUtils.hasText(assistantUid)) {
            return;
        }
        AgentTask task = agentTaskService.findLatestByTaskId(taskId).orElseGet(AgentTask::new);
        String previousStatus = task.getStatus();
        String incomingStatus = normalizeStatus(normalizedPayload.get("status"));
        boolean statusAccepted = shouldUpdateStatus(task.getStatus(), incomingStatus);
        if (!statusAccepted) {
            incomingStatus = normalizeStatus(task.getStatus());
        }
        task.setTaskId(taskId);
        task.setRunId(firstText(normalizedPayload.get("runId"), taskId));
        task.setThreadId(firstText(threadId, stateSnapshot.get(AssistantStateKeys.THREAD_ID), task.getThreadId()));
        task.setAssistantUid(assistantUid);
        task.setTaskType(firstText(normalizedPayload.get("taskType"), task.getTaskType(), "AGENT_TASK"));
        task.setSourceType(firstText(normalizedPayload.get("sourceType"), task.getSourceType(), "AGENT_TASK"));
        task.setSourceCode(firstText(normalizedPayload.get("sourceCode"), task.getSourceCode()));
        task.setTitle(firstText(normalizedPayload.get("title"), task.getTitle(), "后台任务"));
        task.setStatus(firstText(incomingStatus, normalizeStatus(task.getStatus()), "RUNNING"));
        if (statusAccepted || !isTerminalStatus(previousStatus)) {
            task.setProgressPercent(intValue(normalizedPayload.get("progressPercent"), task.getProgressPercent()));
            task.setLatestOutputJson(serialize(lastLiveOutput(normalizedPayload)));
        }
        task.setCollapsible(!Boolean.FALSE.equals(normalizedPayload.get("collapsible")));
        task.setBackground(Boolean.TRUE.equals(normalizedPayload.get("background")));
        task.setDetached(Boolean.TRUE.equals(normalizedPayload.get("detached")));
        task.setResultReady(Boolean.TRUE.equals(normalizedPayload.get("resultReady")) || isTerminalStatus(task.getStatus()));
        Map<String, Object> resultPreview = asMap(normalizedPayload.get("resultPreview"));
        if (!resultPreview.isEmpty() && (statusAccepted || !isTerminalStatus(previousStatus))) {
            task.setResultPreviewJson(serialize(resultPreview));
        }
        task.setActionJson(serialize(resolveAction(taskId, task.getThreadId(), normalizedPayload)));
        if (isTerminalStatus(task.getStatus())) {
            task.setCompletedAt(resolveCompletedAt(normalizedPayload));
        }
        agentTaskService.saveOrUpdateByTaskId(task);

        AgentTaskEvent taskEvent = new AgentTaskEvent();
        taskEvent.setEventId(buildGenericEventId(taskId, normalizedPayload));
        taskEvent.setTaskId(taskId);
        taskEvent.setThreadId(task.getThreadId());
        taskEvent.setAssistantUid(assistantUid);
        taskEvent.setEventType(firstText(normalizedPayload.get("eventType"), "TASK_STATE"));
        taskEvent.setStatus(task.getStatus());
        taskEvent.setSequenceNo(longValue(normalizedPayload.get("sequence")));
        taskEvent.setPayloadJson(serialize(lastLiveOutput(normalizedPayload)));
        taskEvent.setCreatedAt(LocalDateTime.now());
        agentTaskEventService.saveIfAbsent(taskEvent);

        if (becameTerminal(previousStatus, task.getStatus())) {
            upsertNotification(task, normalizedPayload);
        }
    }

    private boolean isInternalExecutionEvent(ExecutionEvent event) {
        if (event == null || event.payload() == null || event.payload().isEmpty()) {
            return false;
        }
        return Boolean.TRUE.equals(event.payload().get("internal"));
    }

    private boolean isInternalTaskPayload(Map<String, Object> normalizedPayload) {
        if (normalizedPayload == null || normalizedPayload.isEmpty()) {
            return false;
        }
        return Boolean.TRUE.equals(normalizedPayload.get("internal"));
    }

    private boolean shouldUpdateStatus(String existingStatus, String incomingStatus) {
        if (!StringUtils.hasText(existingStatus)) {
            return true;
        }
        if (!isTerminalStatus(existingStatus)) {
            return true;
        }
        return isTerminalStatus(incomingStatus);
    }

    private boolean becameTerminal(String previousStatus, String currentStatus) {
        return !isTerminalStatus(previousStatus) && isTerminalStatus(currentStatus);
    }

    private String mapExecutionStatus(ExecutionEvent event) {
        if (event == null || event.lifecycleStatus() == null) {
            return "RUNNING";
        }
        return switch (event.lifecycleStatus()) {
            case COMPLETED, SKIPPED -> "COMPLETED";
            case FAILED -> "FAILED";
            case WAITING_APPROVAL -> "WAITING_APPROVAL";
            default -> "RUNNING";
        };
    }

    private Integer resolveExecutionProgressPercent(PublishedToolDescriptor descriptor, ExecutionEvent event) {
        if (event == null || event.eventType() == null) {
            return 0;
        }
        Integer payloadProgress = resolvePayloadProgressPercent(event);
        if (payloadProgress != null) {
            return payloadProgress;
        }
        String eventType = event.eventType().name();
        if ("RUN_COMPLETED".equals(eventType) || "RUN_FAILED".equals(eventType)) {
            return 100;
        }
        List<String> stepIds = descriptor != null && descriptor.artifact() != null
                ? new ArrayList<>(descriptor.artifact().getSteps().keySet())
                : List.of();
        if (stepIds.isEmpty() || !StringUtils.hasText(event.stepId())) {
            return "RUN_STARTED".equals(eventType) || "RUN_RESUMED".equals(eventType) ? 0 : null;
        }
        int index = Math.max(stepIds.indexOf(event.stepId()), 0);
        int total = Math.max(stepIds.size(), 1);
        if ("STEP_COMPLETED".equals(eventType)) {
            return Math.min(99, ((index + 1) * 100) / total);
        }
        if ("STEP_WAITING_APPROVAL".equals(eventType)) {
            return Math.min(95, ((index + 1) * 100) / total);
        }
        return Math.min(90, (index * 100) / total);
    }


    private Integer resolvePayloadProgressPercent(ExecutionEvent event) {
        if (event == null || event.payload() == null || event.payload().isEmpty()) {
            return null;
        }
        Object nested = event.payload().get("batchProgress");
        if (nested instanceof Map<?, ?> batchProgress) {
            Integer resolved = intValue(batchProgress.get("percent"), null);
            if (resolved != null) {
                return resolved;
            }
        }
        return intValue(event.payload().get("progressPercent"), null);
    }
    private String resolveExecutionTitle(PublishedToolDescriptor descriptor, ExecutionEvent event) {
        return firstText(
                descriptor != null ? descriptor.displayName() : null,
                event != null ? event.artifactCode() : null,
                "后台任务");
    }

    private Map<String, Object> buildExecutionOutput(ExecutionEvent event) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("eventType", event.eventType() != null ? event.eventType().name() : null);
        payload.put("stepId", text(event.stepId()));
        payload.put("occurredAt", event.occurredAt() != null ? event.occurredAt().toString() : null);
        if (event.payload() != null && !event.payload().isEmpty()) {
            payload.putAll(event.payload());
        }
        return payload;
    }

    private Map<String, Object> buildExecutionResultPreview(ExecutionEvent event) {
        Map<String, Object> preview = new LinkedHashMap<>();
        if (event != null && event.payload() != null) {
            if (event.payload().get("finalOutputs") instanceof Map<?, ?> finalOutputs) {
                preview.putAll((Map<String, Object>) finalOutputs);
            }
            if (StringUtils.hasText(text(event.payload().get("error")))) {
                preview.put("error", event.payload().get("error"));
            }
        }
        return preview;
    }

    private String buildExecutionNotificationBody(ExecutionEvent event) {
        if (event != null && event.payload() != null && StringUtils.hasText(text(event.payload().get("error")))) {
            return String.valueOf(event.payload().get("error"));
        }
        return "点击查看任务结果";
    }

    private Map<String, Object> buildAction(String taskId, String threadId) {
        Map<String, Object> action = new LinkedHashMap<>();
        action.put("type", "TASK_DETAIL");
        action.put("targetId", taskId);
        if (StringUtils.hasText(threadId)) {
            action.put("threadId", threadId);
        }
        return action;
    }

    private Map<String, Object> resolveAction(String taskId, String threadId, Map<String, Object> taskPayload) {
        Map<String, Object> action = asMap(taskPayload.get("action"));
        if (!action.isEmpty()) {
            return action;
        }
        return buildAction(taskId, threadId);
    }

    private Object lastLiveOutput(Map<String, Object> taskPayload) {
        Object liveOutput = taskPayload.get("liveOutput");
        if (liveOutput instanceof List<?> list && !list.isEmpty()) {
            return list.get(list.size() - 1);
        }
        return taskPayload;
    }

    private void upsertNotification(AgentTask task, boolean success, String body) {
        if (task == null || !StringUtils.hasText(task.getTaskId()) || !StringUtils.hasText(task.getAssistantUid())) {
            return;
        }
        UserInboxNotification notification = userInboxNotificationService
                .findLatestByNotificationId(task.getTaskId() + ":" + task.getStatus())
                .orElseGet(UserInboxNotification::new);
        notification.setNotificationId(task.getTaskId() + ":" + task.getStatus());
        notification.setAssistantUid(task.getAssistantUid());
        notification.setTaskId(task.getTaskId());
        notification.setThreadId(task.getThreadId());
        notification.setStatus("UNREAD");
        notification.setTitle(task.getTitle() + (success ? " 已完成" : " 执行失败"));
        notification.setBody(StringUtils.hasText(body) ? body : "点击查看任务结果");
        notification.setActionJson(serialize(buildAction(task.getTaskId(), task.getThreadId())));
        userInboxNotificationService.saveOrUpdateByNotificationId(notification);
    }

    private void upsertNotification(AgentTask task, Map<String, Object> taskPayload) {
        Map<String, Object> notificationPayload = asMap(taskPayload.get("notification"));
        if (notificationPayload.isEmpty()) {
            upsertNotification(task, successStatus(task.getStatus()), "点击查看任务结果");
            return;
        }
        String notificationId = firstText(
                notificationPayload.get("notificationId"),
                task.getTaskId() + ":" + task.getStatus());
        UserInboxNotification notification = userInboxNotificationService
                .findLatestByNotificationId(notificationId)
                .orElseGet(UserInboxNotification::new);
        notification.setNotificationId(notificationId);
        notification.setAssistantUid(task.getAssistantUid());
        notification.setTaskId(task.getTaskId());
        notification.setThreadId(task.getThreadId());
        notification.setStatus(firstText(notificationPayload.get("status"), "UNREAD"));
        notification.setTitle(firstText(notificationPayload.get("title"),
                task.getTitle() + (successStatus(task.getStatus()) ? " 已完成" : " 执行失败")));
        notification.setBody(firstText(notificationPayload.get("body"), "点击查看任务结果"));
        notification.setActionJson(serialize(firstNonEmpty(notificationPayload.get("action"),
                resolveAction(task.getTaskId(), task.getThreadId(), taskPayload))));
        userInboxNotificationService.saveOrUpdateByNotificationId(notification);
    }

    private boolean isTerminalStatus(String status) {
        String normalized = normalizeStatus(status);
        return "COMPLETED".equals(normalized) || "FAILED".equals(normalized);
    }

    private boolean successStatus(String status) {
        return "COMPLETED".equals(normalizeStatus(status));
    }

    private String buildExecutionEventId(ExecutionEvent event) {
        return firstText(event.runId(), "task") + ":" + event.sequence() + ":"
                + firstText(event.eventType() != null ? event.eventType().name() : null, "event");
    }

    private String buildGenericEventId(String taskId, Map<String, Object> taskPayload) {
        return firstText(taskPayload.get("eventId"),
                taskId + ":" + firstText(taskPayload.get("eventType"), taskPayload.get("status"), "state")
                + ":" + firstText(taskPayload.get("occurredAt"), Instant.now().toString()));
    }

    private LocalDateTime resolveCompletedAt(Map<String, Object> taskPayload) {
        String completedAt = firstText(taskPayload.get("completedAt"), taskPayload.get("occurredAt"));
        if (!StringUtils.hasText(completedAt)) {
            return LocalDateTime.now();
        }
        try {
            return LocalDateTime.ofInstant(Instant.parse(completedAt), ZoneId.systemDefault());
        }
        catch (Exception ignored) {
            return LocalDateTime.now();
        }
    }

    private LocalDateTime asLocalDateTime(Instant instant) {
        return instant != null ? LocalDateTime.ofInstant(instant, ZoneId.systemDefault()) : LocalDateTime.now();
    }

    private String serialize(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        }
        catch (Exception e) {
            return String.valueOf(value);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            return new LinkedHashMap<>((Map<String, Object>) map);
        }
        return Map.of();
    }

    private String text(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return StringUtils.hasText(text) ? text : null;
    }

    private String normalizeStatus(Object value) {
        String status = text(value);
        return status != null ? status.toUpperCase() : null;
    }

    private String firstText(Object... values) {
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

    private Object firstNonEmpty(Object... values) {
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
            if (value instanceof String text && !StringUtils.hasText(text)) {
                continue;
            }
            return value;
        }
        return null;
    }

    private Integer intValue(Object value, Integer fallback) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (!StringUtils.hasText(firstText(value))) {
            return fallback;
        }
        try {
            return Integer.parseInt(String.valueOf(value).trim());
        }
        catch (NumberFormatException e) {
            return fallback;
        }
    }

    private Long longValue(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (!StringUtils.hasText(firstText(value))) {
            return null;
        }
        try {
            return Long.parseLong(String.valueOf(value).trim());
        }
        catch (NumberFormatException e) {
            return null;
        }
    }
}


