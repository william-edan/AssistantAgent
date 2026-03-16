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
import com.alibaba.assistant.agent.api.controller.dto.ChatTaskListItemData;
import com.alibaba.assistant.agent.api.controller.dto.ChatThreadStateData;
import com.alibaba.assistant.agent.api.protocol.V3ProtocolAdapter;
import com.alibaba.assistant.agent.runtime.agent.AssistantStateKeys;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.checkpoint.BaseCheckpointSaver;
import com.alibaba.cloud.ai.graph.checkpoint.Checkpoint;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 线程状态读服务。
 *
 * <p>负责把运行时检查点、聊天历史快照、任务读侧和站内信聚合成一份统一的线程状态，
 * 供前端恢复未完成线程、展示当前阶段和恢复待处理卡片。</p>
 */
@Service
@Profile("migration")
public class ChatThreadStateService {

    @Nullable
    private final BaseCheckpointSaver checkpointSaver;

    private final V3ProtocolAdapter protocolAdapter;

    @Nullable
    private final ChatTaskService chatTaskService;

    @Nullable
    private final ChatNotificationService chatNotificationService;

    @Nullable
    private final ChatConversationHistoryService chatConversationHistoryService;

    @Autowired
    public ChatThreadStateService(
            @Nullable BaseCheckpointSaver checkpointSaver,
            @Nullable V3ProtocolAdapter protocolAdapter,
            @Nullable ChatTaskService chatTaskService,
            @Nullable ChatNotificationService chatNotificationService,
            @Nullable ChatConversationHistoryService chatConversationHistoryService) {
        this.checkpointSaver = checkpointSaver;
        this.protocolAdapter = protocolAdapter != null ? protocolAdapter : new V3ProtocolAdapter(new ObjectMapper());
        this.chatTaskService = chatTaskService;
        this.chatNotificationService = chatNotificationService;
        this.chatConversationHistoryService = chatConversationHistoryService;
    }

    public ChatThreadStateData getThreadState(String threadId, String assistantUid) {
        if (!StringUtils.hasText(threadId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "threadId cannot be null or empty");
        }
        Optional<Checkpoint> checkpoint = checkpointSaver != null
                ? checkpointSaver.get(RunnableConfig.builder().threadId(threadId).build())
                : Optional.empty();
        Map<String, Object> state = checkpoint
                .map(Checkpoint::getState)
                .filter(raw -> raw != null && !raw.isEmpty())
                .map(LinkedHashMap::new)
                .orElseGet(LinkedHashMap::new);
        authorizeThreadOwner(state, assistantUid);

        List<ChatTaskListItemData> threadTasks = chatTaskService != null
                ? chatTaskService.listThreadTasks(assistantUid, threadId, 10)
                : List.of();
        List<ChatNotificationData> threadNotifications = chatNotificationService != null
                ? chatNotificationService.listThreadNotifications(assistantUid, threadId, 10)
                : List.of();

        Map<String, Object> checkpointSnapshot = new LinkedHashMap<>(protocolAdapter.projectThreadState(state));
        Map<String, Object> historySnapshot = chatConversationHistoryService != null
                ? new LinkedHashMap<>(chatConversationHistoryService.findThreadStateSnapshot(assistantUid, threadId).orElse(Map.of()))
                : new LinkedHashMap<>();
        Map<String, Object> taskSnapshot = new LinkedHashMap<>(synthesizeSnapshot(threadTasks, threadNotifications));
        Map<String, Object> snapshot = reconcileSnapshot(checkpointSnapshot, historySnapshot, taskSnapshot);
        if (snapshot.isEmpty()) {
            if (checkpoint.isPresent()) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "chat_thread_state_not_found");
            }
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "chat_thread_not_found");
        }
        if (!threadTasks.isEmpty() || !snapshot.containsKey("tasks")) {
            snapshot.put("tasks", threadTasks);
        }
        if (!threadNotifications.isEmpty() || !snapshot.containsKey("notifications")) {
            snapshot.put("notifications", threadNotifications);
        }
        snapshot.putIfAbsent("activeTaskCount", countActiveTasks(threadTasks));
        snapshot.putIfAbsent("unreadNotificationCount", countUnreadNotifications(threadNotifications));
        return ChatThreadStateData.from(threadId, checkpoint.map(Checkpoint::getId).orElse(null), snapshot);
    }

    private Map<String, Object> reconcileSnapshot(
            Map<String, Object> checkpointSnapshot,
            Map<String, Object> historySnapshot,
            Map<String, Object> taskSnapshot) {
        Map<String, Object> resolved = new LinkedHashMap<>();
        if (!checkpointSnapshot.isEmpty()) {
            resolved.putAll(checkpointSnapshot);
        }
        else if (!historySnapshot.isEmpty()) {
            resolved.putAll(historySnapshot);
        }
        else if (!taskSnapshot.isEmpty()) {
            resolved.putAll(taskSnapshot);
        }

        if (shouldPreferCandidate(resolved, historySnapshot)) {
            resolved.clear();
            resolved.putAll(historySnapshot);
        }
        if (shouldPreferCandidate(resolved, taskSnapshot)) {
            resolved.clear();
            resolved.putAll(taskSnapshot);
        }
        return resolved;
    }

    /**
     * checkpoint 代表运行时瞬时状态，若读侧已有更新后的终态快照，应优先展示读侧结果。
     */
    private boolean shouldPreferCandidate(Map<String, Object> currentSnapshot, Map<String, Object> candidateSnapshot) {
        if (candidateSnapshot == null || candidateSnapshot.isEmpty()) {
            return false;
        }
        if (currentSnapshot == null || currentSnapshot.isEmpty()) {
            return true;
        }
        String currentStatus = firstText(asText(currentSnapshot.get("status")));
        String candidateStatus = firstText(asText(candidateSnapshot.get("status")));
        boolean currentTerminal = ChatThreadActionSupport.isTerminalStatus(currentStatus);
        boolean candidateTerminal = ChatThreadActionSupport.isTerminalStatus(candidateStatus);
        boolean currentUnfinished = Boolean.TRUE.equals(currentSnapshot.get("unfinished"));
        boolean candidateUnfinished = Boolean.TRUE.equals(candidateSnapshot.get("unfinished"));
        if (candidateTerminal && !currentTerminal) {
            return true;
        }
        if ("WAITING_APPROVAL".equalsIgnoreCase(currentStatus) && candidateTerminal) {
            return true;
        }
        return currentUnfinished && !candidateUnfinished && candidateTerminal;
    }

    private void authorizeThreadOwner(Map<String, Object> state, String assistantUid) {
        if (!StringUtils.hasText(assistantUid) || state == null || state.isEmpty()) {
            return;
        }
        Object rawOwner = state.get(AssistantStateKeys.ASSISTANT_UID);
        if (rawOwner == null) {
            return;
        }
        String owner = String.valueOf(rawOwner).trim();
        if (StringUtils.hasText(owner) && !assistantUid.trim().equals(owner)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "chat_thread_scope_denied");
        }
    }

    private Map<String, Object> synthesizeSnapshot(
            List<ChatTaskListItemData> threadTasks,
            List<ChatNotificationData> threadNotifications) {
        if ((threadTasks == null || threadTasks.isEmpty())
                && (threadNotifications == null || threadNotifications.isEmpty())) {
            return Map.of();
        }
        ChatTaskListItemData primaryTask = primaryTask(threadTasks);
        String status = primaryTask != null ? firstText(primaryTask.status(), "RUNNING") : "COMPLETED";
        String phase = "WAITING_APPROVAL".equalsIgnoreCase(status) ? "WAITING_APPROVAL"
                : (ChatThreadActionSupport.isTerminalStatus(status) ? "DONE" : "EXECUTING");
        boolean unfinished = primaryTask != null && !ChatThreadActionSupport.isTerminalStatus(status);
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("status", status);
        snapshot.put("phase", phase);
        snapshot.put("unfinished", unfinished);
        snapshot.put("canResume", ChatThreadActionSupport.canResume(status, phase, unfinished));
        snapshot.put("updatedAt", Instant.now().toString());
        snapshot.put("activeTaskCount", countActiveTasks(threadTasks));
        snapshot.put("unreadNotificationCount", countUnreadNotifications(threadNotifications));
        if (primaryTask != null) {
            snapshot.put("toolCode", primaryTask.sourceCode());
            snapshot.put("pendingCardType", "TASK_CARD");
            snapshot.put("lastMessage", firstText(primaryTask.latestOutput(), primaryTask.title(), status));
        }
        else if (threadNotifications != null && !threadNotifications.isEmpty()) {
            snapshot.put("lastMessage", firstText(threadNotifications.get(0).title(), threadNotifications.get(0).body()));
        }
        return snapshot;
    }

    private ChatTaskListItemData primaryTask(List<ChatTaskListItemData> threadTasks) {
        if (threadTasks == null || threadTasks.isEmpty()) {
            return null;
        }
        return threadTasks.stream()
                .filter(task -> task != null && "WAITING_APPROVAL".equalsIgnoreCase(task.status()))
                .findFirst()
                .orElse(threadTasks.get(0));
    }

    private int countActiveTasks(List<ChatTaskListItemData> tasks) {
        if (tasks == null || tasks.isEmpty()) {
            return 0;
        }
        return (int) tasks.stream()
                .filter(task -> task != null && !ChatThreadActionSupport.isTerminalStatus(task.status()))
                .count();
    }

    private int countUnreadNotifications(List<ChatNotificationData> notifications) {
        if (notifications == null || notifications.isEmpty()) {
            return 0;
        }
        return (int) notifications.stream()
                .filter(notification -> notification != null && "UNREAD".equalsIgnoreCase(notification.status()))
                .count();
    }

    private String asText(Object value) {
        return value != null ? String.valueOf(value) : null;
    }

    private String firstText(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value;
            }
        }
        return null;
    }
}

