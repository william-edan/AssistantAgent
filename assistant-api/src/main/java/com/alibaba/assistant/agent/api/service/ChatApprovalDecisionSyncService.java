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

import com.alibaba.assistant.agent.api.controller.dto.ChatTaskData;
import com.alibaba.assistant.agent.api.controller.dto.ChatTaskEventItemData;
import com.alibaba.assistant.agent.api.protocol.FrontendEvent;
import com.alibaba.assistant.agent.runtime.execution.ExecutionApprovalDecisionView;
import com.alibaba.assistant.agent.runtime.execution.ExecutionApprovalDetailView;
import com.alibaba.assistant.agent.runtime.execution.ExecutionApprovalService;
import org.springframework.context.annotation.Profile;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 将控制面审批结果同步回聊天读侧，保证审批完成后线程状态、消息和任务卡一致。
 */
@Service
@Profile("migration")
public class ChatApprovalDecisionSyncService {

    private final ExecutionApprovalService executionApprovalService;

    private final ChatTaskFrontendEventService chatTaskFrontendEventService;

    private final ChatFrontendEventPublisher chatFrontendEventPublisher;

    @Nullable
    private final ChatTaskService chatTaskService;

    public ChatApprovalDecisionSyncService(
            ExecutionApprovalService executionApprovalService,
            ChatTaskFrontendEventService chatTaskFrontendEventService,
            ChatFrontendEventPublisher chatFrontendEventPublisher,
            @Nullable ChatTaskService chatTaskService) {
        this.executionApprovalService = executionApprovalService;
        this.chatTaskFrontendEventService = chatTaskFrontendEventService;
        this.chatFrontendEventPublisher = chatFrontendEventPublisher;
        this.chatTaskService = chatTaskService;
    }

    /**
     * 在 approve/reject 成功后，把终态任务事件补写到聊天读侧。
     */
    public void publishDecision(
            String spaceCode,
            String environment,
            String requestId,
            ExecutionApprovalDecisionView decisionView) {
        if (decisionView == null || !StringUtils.hasText(spaceCode) || !StringUtils.hasText(requestId)) {
            return;
        }
        ExecutionApprovalDetailView detailView = executionApprovalService
                .findRequest(spaceCode, environment, requestId)
                .orElse(null);
        if (detailView == null || !StringUtils.hasText(detailView.threadId())
                || !StringUtils.hasText(detailView.platformPrincipalId())) {
            return;
        }

        String ownerAssistantUid = detailView.platformPrincipalId().trim();
        String threadId = detailView.threadId().trim();
        String turnId = decisionView.requestId() + ":approval-decision";
        Map<String, Object> taskPayload = resolveTaskPayload(ownerAssistantUid, detailView, decisionView);
        List<FrontendEvent> events = chatTaskFrontendEventService.buildEvents(threadId, taskPayload);
        for (FrontendEvent event : events) {
            chatFrontendEventPublisher.publish(threadId, ownerAssistantUid, null, null, turnId, event);
        }
        chatFrontendEventPublisher.finishTurn(threadId, ownerAssistantUid, null, null, turnId);
    }

    private Map<String, Object> resolveTaskPayload(
            String ownerAssistantUid,
            ExecutionApprovalDetailView detailView,
            ExecutionApprovalDecisionView decisionView) {
        if (chatTaskService != null && StringUtils.hasText(decisionView.runId())) {
            try {
                ChatTaskData taskData = chatTaskService.getTask(ownerAssistantUid, decisionView.runId());
                if (taskData != null) {
                    return toTaskPayload(taskData);
                }
            }
            catch (Exception ignored) {
                // 任务读侧可能尚未生成，回退到合成终态 payload。
            }
        }
        return synthesizeTaskPayload(detailView, decisionView);
    }

    private Map<String, Object> toTaskPayload(ChatTaskData taskData) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("taskId", taskData.taskId());
        payload.put("runId", taskData.taskId());
        payload.put("threadId", taskData.threadId());
        payload.put("taskType", taskData.taskType());
        payload.put("title", taskData.title());
        payload.put("status", taskData.status());
        payload.put("sourceType", taskData.sourceType());
        payload.put("sourceCode", taskData.sourceCode());
        payload.put("progressPercent", taskData.progressPercent());
        payload.put("collapsible", taskData.collapsible());
        payload.put("resultReady", taskData.resultReady());
        payload.put("background", taskData.background());
        payload.put("detached", taskData.detached());
        if (taskData.display() != null && !taskData.display().isEmpty()) {
            payload.put("display", taskData.display());
        }
        if (taskData.resultPreview() != null && !taskData.resultPreview().isEmpty()) {
            payload.put("resultPreview", taskData.resultPreview());
        }
        if (taskData.action() != null && !taskData.action().isEmpty()) {
            payload.put("action", taskData.action());
        }
        if (taskData.liveOutput() != null && !taskData.liveOutput().isEmpty()) {
            payload.put("liveOutput", taskData.liveOutput().stream().map(this::toLiveOutputItem).toList());
        }
        return payload;
    }

    private Map<String, Object> synthesizeTaskPayload(
            ExecutionApprovalDetailView detailView,
            ExecutionApprovalDecisionView decisionView) {
        boolean approved = "APPROVED".equalsIgnoreCase(decisionView.status());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("taskId", decisionView.runId());
        payload.put("runId", decisionView.runId());
        payload.put("threadId", detailView.threadId());
        payload.put("taskType", "ARTIFACT_EXECUTION");
        payload.put("title", firstText(detailView.artifactCode(), "审批任务"));
        payload.put("status", approved ? firstText(decisionView.runStatus(), "COMPLETED") : "CANCELLED");
        payload.put("sourceType", "ARTIFACT_EXECUTION");
        payload.put("sourceCode", detailView.artifactCode());
        payload.put("progressPercent", 100);
        payload.put("collapsible", true);
        payload.put("resultReady", true);
        payload.put("background", false);
        payload.put("detached", false);
        payload.put("action", Map.of(
                "type", "TASK_DETAIL",
                "targetId", decisionView.runId(),
                "threadId", detailView.threadId()));
        payload.put("display", Map.of(
                "showInChat", true,
                "showInTaskCenter", true,
                "showInInbox", true,
                "foldable", true,
                "collapsedByDefault", true,
                "showLiveOutput", true,
                "showResultPreview", true,
                "detached", false,
                "background", false));
        payload.put("liveOutput", List.of(Map.of(
                "eventType", approved ? "APPROVAL_APPROVED" : "APPROVAL_REJECTED",
                "status", approved ? "COMPLETED" : "FAILED",
                "text", approved ? "审批已通过，任务已完成" : "审批已拒绝，流程已终止",
                "occurredAt", firstText(decisionView.respondedAt(), decisionView.requestedAt()))));
        payload.put("resultPreview", approved
                ? Map.of("summary", "审批已通过，任务已完成")
                : Map.of("summary", "审批已拒绝", "error", "审批已拒绝，流程已终止"));
        return payload;
    }

    private Map<String, Object> toLiveOutputItem(ChatTaskEventItemData item) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("eventId", item.eventId());
        payload.put("eventType", item.eventType());
        payload.put("status", item.status());
        payload.put("sequence", item.sequence());
        payload.put("occurredAt", item.createdAt());
        if (item.payload() != null && !item.payload().isEmpty()) {
            payload.putAll(item.payload());
        }
        return payload;
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
}
