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

import com.alibaba.assistant.agent.common.chat.FrontendTaskStateSupport;
import com.alibaba.assistant.agent.api.controller.dto.ChatTaskData;
import com.alibaba.assistant.agent.api.controller.dto.ChatTaskEventItemData;
import com.alibaba.assistant.agent.api.controller.dto.ChatTaskEventListData;
import com.alibaba.assistant.agent.api.controller.dto.ChatTaskListData;
import com.alibaba.assistant.agent.api.controller.dto.ChatTaskListItemData;
import com.alibaba.assistant.agent.execution.persistence.AgentTask;
import com.alibaba.assistant.agent.execution.persistence.AgentTaskEvent;
import com.alibaba.assistant.agent.execution.persistence.AgentTaskEventService;
import com.alibaba.assistant.agent.execution.persistence.AgentTaskService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 聊天任务读服务。
 *
 * <p>负责把任务表、任务事件表整理成前端可直接展示的任务列表和任务详情，
 * 是任务中心、聊天折叠任务卡和任务详情页的读模型入口。</p>
 */
@Service
@Profile("migration")
public class ChatTaskService {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final AgentTaskService agentTaskService;

    private final AgentTaskEventService agentTaskEventService;

    private final ObjectMapper objectMapper;

    public ChatTaskService(
            AgentTaskService agentTaskService,
            AgentTaskEventService agentTaskEventService,
            ObjectMapper objectMapper) {
        this.agentTaskService = agentTaskService;
        this.agentTaskEventService = agentTaskEventService;
        this.objectMapper = objectMapper;
    }

    public ChatTaskListData listTasks(String assistantUid, String threadId, String status, Integer limit) {
        return new ChatTaskListData(agentTaskService.listByAssistantUid(assistantUid, threadId, status, limit).stream()
                .map(this::toListItem)
                .toList());
    }

    public ChatTaskData getTask(String assistantUid, String taskId) {
        AgentTask task = requireOwnedTask(assistantUid, taskId);
        return toDetail(task, listEventItems(task.getTaskId(), 20));
    }

    public ChatTaskEventListData listTaskEvents(String assistantUid, String taskId, Integer limit) {
        AgentTask task = requireOwnedTask(assistantUid, taskId);
        return new ChatTaskEventListData(listEventItems(task.getTaskId(), limit));
    }

    public List<ChatTaskListItemData> listThreadTasks(String assistantUid, String threadId, Integer limit) {
        return agentTaskService.listByAssistantUid(assistantUid, threadId, null, limit).stream()
                .map(this::toListItem)
                .toList();
    }

    private AgentTask requireOwnedTask(String assistantUid, String taskId) {
        AgentTask task = agentTaskService.findLatestByTaskId(taskId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "chat_task_not_found"));
        if (StringUtils.hasText(assistantUid)
                && StringUtils.hasText(task.getAssistantUid())
                && !assistantUid.trim().equals(task.getAssistantUid().trim())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "chat_task_scope_denied");
        }
        return task;
    }

    private ChatTaskListItemData toListItem(AgentTask task) {
        Map<String, Object> normalizedPayload = normalizeTaskPayload(task, List.of());
        return new ChatTaskListItemData(
                task.getTaskId(),
                task.getThreadId(),
                task.getTaskType(),
                task.getTitle(),
                task.getStatus(),
                task.getSourceType(),
                task.getSourceCode(),
                task.getProgressPercent(),
                Boolean.TRUE.equals(task.getCollapsible()),
                Boolean.TRUE.equals(task.getResultReady()),
                asText(task.getCreatedAt()),
                asText(task.getCompletedAt()),
                extractLatestOutput(task.getLatestOutputJson()),
                firstText(normalizedPayload.get("summaryText"), extractLatestOutput(task.getLatestOutputJson())),
                Boolean.TRUE.equals(normalizedPayload.get("background")),
                Boolean.TRUE.equals(normalizedPayload.get("detached")),
                readMapValue(normalizedPayload.get("display")));
    }

    private ChatTaskData toDetail(AgentTask task, List<ChatTaskEventItemData> liveOutput) {
        Map<String, Object> normalizedPayload = normalizeTaskPayload(task, liveOutput);
        return new ChatTaskData(
                task.getTaskId(),
                task.getThreadId(),
                task.getTaskType(),
                task.getTitle(),
                task.getStatus(),
                task.getSourceType(),
                task.getSourceCode(),
                task.getProgressPercent(),
                Boolean.TRUE.equals(task.getCollapsible()),
                Boolean.TRUE.equals(task.getResultReady()),
                asText(task.getCreatedAt()),
                asText(task.getCompletedAt()),
                extractLatestOutput(task.getLatestOutputJson()),
                firstText(normalizedPayload.get("summaryText"), extractLatestOutput(task.getLatestOutputJson())),
                Boolean.TRUE.equals(normalizedPayload.get("background")),
                Boolean.TRUE.equals(normalizedPayload.get("detached")),
                readMapValue(normalizedPayload.get("display")),
                liveOutput,
                readMap(task.getResultPreviewJson()),
                readMap(task.getActionJson()));
    }

    private List<ChatTaskEventItemData> listEventItems(String taskId, Integer limit) {
        return agentTaskEventService.listByTaskId(taskId, limit).stream()
                .map(this::toEventItem)
                .toList();
    }

    private ChatTaskEventItemData toEventItem(AgentTaskEvent event) {
        return new ChatTaskEventItemData(
                event.getEventId(),
                event.getTaskId(),
                event.getEventType(),
                event.getStatus(),
                event.getSequenceNo(),
                asText(event.getCreatedAt()),
                readMap(event.getPayloadJson()));
    }

    private String extractLatestOutput(String latestOutputJson) {
        Map<String, Object> payload = readMap(latestOutputJson);
        if (payload.isEmpty()) {
            return null;
        }
        String text = textValue(payload.get("text"));
        if (StringUtils.hasText(text)) {
            return text;
        }
        String stepName = textValue(payload.get("stepName"));
        if (StringUtils.hasText(stepName)) {
            return stepName;
        }
        return null;
    }

    private Map<String, Object> normalizeTaskPayload(AgentTask task, List<ChatTaskEventItemData> liveOutput) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("taskId", task.getTaskId());
        payload.put("runId", task.getRunId());
        payload.put("threadId", task.getThreadId());
        payload.put("taskType", task.getTaskType());
        payload.put("title", task.getTitle());
        payload.put("status", task.getStatus());
        payload.put("sourceType", task.getSourceType());
        payload.put("sourceCode", task.getSourceCode());
        payload.put("progressPercent", task.getProgressPercent());
        payload.put("collapsible", task.getCollapsible());
        payload.put("background", task.getBackground());
        payload.put("detached", task.getDetached());
        payload.put("resultReady", task.getResultReady());
        Map<String, Object> resultPreview = readMap(task.getResultPreviewJson());
        if (!resultPreview.isEmpty()) {
            payload.put("resultPreview", resultPreview);
        }
        Map<String, Object> action = readMap(task.getActionJson());
        if (!action.isEmpty()) {
            payload.put("action", action);
        }
        List<Map<String, Object>> liveOutputPayload = new java.util.ArrayList<>();
        if (liveOutput != null && !liveOutput.isEmpty()) {
            for (ChatTaskEventItemData event : liveOutput) {
                if (event == null) {
                    continue;
                }
                Map<String, Object> eventPayload = new LinkedHashMap<>();
                eventPayload.put("eventId", event.eventId());
                eventPayload.put("eventType", event.eventType());
                eventPayload.put("status", event.status());
                eventPayload.put("sequence", event.sequence());
                eventPayload.put("occurredAt", event.createdAt());
                if (event.payload() != null && !event.payload().isEmpty()) {
                    eventPayload.putAll(event.payload());
                }
                liveOutputPayload.add(eventPayload);
            }
        }
        else {
            Map<String, Object> latestOutput = readMap(task.getLatestOutputJson());
            if (!latestOutput.isEmpty()) {
                liveOutputPayload.add(latestOutput);
            }
        }
        if (!liveOutputPayload.isEmpty()) {
            payload.put("liveOutput", liveOutputPayload);
        }
        return FrontendTaskStateSupport.normalizePayload(payload);
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
            return Map.of("text", json);
        }
    }

    private String asText(Object value) {
        return value != null ? String.valueOf(value) : null;
    }

    private Map<String, Object> readMapValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            return new LinkedHashMap<>((Map<String, Object>) map);
        }
        return Map.of();
    }

    private String firstText(Object... values) {
        if (values == null) {
            return null;
        }
        for (Object value : values) {
            String text = textValue(value);
            if (StringUtils.hasText(text)) {
                return text;
            }
        }
        return null;
    }

    private String textValue(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return StringUtils.hasText(text) ? text : null;
    }
}




