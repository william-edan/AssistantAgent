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

import com.alibaba.assistant.agent.api.controller.dto.ChatMessageData;
import com.alibaba.assistant.agent.api.controller.dto.ChatThreadSummaryData;
import com.alibaba.assistant.agent.execution.persistence.AgentTask;
import com.alibaba.assistant.agent.execution.persistence.AgentTaskService;
import com.alibaba.assistant.agent.execution.persistence.ChatMessageRecord;
import com.alibaba.assistant.agent.execution.persistence.ChatMessageRecordService;
import com.alibaba.assistant.agent.execution.persistence.ChatThreadRecord;
import com.alibaba.assistant.agent.execution.persistence.ChatThreadRecordService;
import com.alibaba.assistant.agent.execution.persistence.UserInboxNotification;
import com.alibaba.assistant.agent.execution.persistence.UserInboxNotificationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@SuppressWarnings("unchecked")
class ChatConversationHistoryServiceTest {

    @Mock
    private ChatThreadRecordService chatThreadRecordService;

    @Mock
    private ChatMessageRecordService chatMessageRecordService;

    @Mock
    private AgentTaskService agentTaskService;

    @Mock
    private UserInboxNotificationService userInboxNotificationService;

    private ChatConversationHistoryService chatConversationHistoryService;

    @BeforeEach
    void setUp() {
        chatConversationHistoryService = new ChatConversationHistoryService(
                chatThreadRecordService,
                chatMessageRecordService,
                agentTaskService,
                userInboxNotificationService,
                new ObjectMapper());
    }

    @Test
    void shouldExposeResumableThreadSummaryMetadata() {
        ChatThreadRecord threadRecord = new ChatThreadRecord();
        threadRecord.setThreadId("thread-confirm");
        threadRecord.setAssistantUid("1001");
        threadRecord.setTitle("请假申请");
        threadRecord.setStatus("WAITING_CONFIRMATION");
        threadRecord.setPhase("CONFIRMING");
        threadRecord.setUnfinished(true);
        threadRecord.setCanResume(true);
        threadRecord.setToolCode("gougu_oa.leave_application");
        threadRecord.setPendingCardType("FORM_CARD");
        threadRecord.setLastMessagePreview("请确认审批人与请假时间");
        threadRecord.setLastEventType("FORM_STATE");
        threadRecord.setUpdatedAt(LocalDateTime.parse("2026-03-13T12:00:00"));

        AgentTask activeTask = new AgentTask();
        activeTask.setTaskId("TASK-1");
        activeTask.setThreadId("thread-confirm");
        activeTask.setAssistantUid("1001");
        activeTask.setStatus("RUNNING");

        UserInboxNotification unreadNotification = new UserInboxNotification();
        unreadNotification.setNotificationId("N-1");
        unreadNotification.setThreadId("thread-confirm");
        unreadNotification.setAssistantUid("1001");
        unreadNotification.setStatus("UNREAD");

        when(chatThreadRecordService.listByAssistantUid("1001", 20)).thenReturn(List.of(threadRecord));
        when(agentTaskService.countActiveByAssistantUidAndThreadId("1001", "thread-confirm")).thenReturn(1);
        when(userInboxNotificationService.countUnreadByAssistantUidAndThreadId("1001", "thread-confirm")).thenReturn(1);

        ChatThreadSummaryData summary = chatConversationHistoryService.listThreads("1001", 20).threads().get(0);

        assertThat(summary.threadId()).isEqualTo("thread-confirm");
        assertThat(summary.canResume()).isTrue();
        assertThat(summary.toolCode()).isEqualTo("gougu_oa.leave_application");
        assertThat(summary.pendingCardType()).isEqualTo("FORM_CARD");
        assertThat(summary.activeTaskCount()).isEqualTo(1);
        assertThat(summary.unreadNotificationCount()).isEqualTo(1);
        assertThat(summary.nextAction()).containsEntry("endpoint", "/api/chat/run_sse");
    }

    @Test
    void shouldRecoverWaitingConfirmationFromLegacyReadyToConfirmFormCard() throws Exception {
        ChatThreadRecord threadRecord = new ChatThreadRecord();
        threadRecord.setThreadId("thread-ready-confirm");
        threadRecord.setAssistantUid("1001");
        threadRecord.setStatus("WAITING_INPUT");
        threadRecord.setPhase("COLLECTING");
        threadRecord.setUnfinished(true);
        threadRecord.setCanResume(true);
        threadRecord.setUpdatedAt(LocalDateTime.parse("2026-03-13T12:05:00"));

        ChatMessageRecord messageRecord = new ChatMessageRecord();
        messageRecord.setMessageId("msg-ready-confirm");
        messageRecord.setThreadId("thread-ready-confirm");
        messageRecord.setAssistantUid("1001");
        messageRecord.setTurnId("turn-ready-confirm");
        messageRecord.setMessageType("FORM_CARD");
        messageRecord.setEventType("FORM_STATE");
        messageRecord.setStage("COLLECTING");
        messageRecord.setStatus("WAITING_INPUT");
        messageRecord.setTitle("请确认请假信息");
        messageRecord.setSummaryText("审批人");
        messageRecord.setPayloadJson(new ObjectMapper().writeValueAsString(Map.of(
                "protocolVersion", "2026-03-13",
                "eventId", "evt-ready-confirm",
                "threadId", "thread-ready-confirm",
                "timestamp", "2026-03-13T12:05:05Z",
                "eventType", "FORM_STATE",
                "stage", "COLLECTING",
                "payload", Map.of(
                        "mode", "COLLECT",
                        "status", "READY_TO_CONFIRM",
                        "phase", "READY_TO_CONFIRM",
                        "toolCode", "gougu_oa.leave_application",
                        "values", Map.of("types", 1, "check_uids", "4"),
                        "canSubmit", true))));

        when(chatThreadRecordService.findByThreadId("thread-ready-confirm")).thenReturn(Optional.of(threadRecord));
        when(chatMessageRecordService.listByThreadId("thread-ready-confirm", "1001", 200)).thenReturn(List.of(messageRecord));
        when(agentTaskService.countActiveByAssistantUidAndThreadId("1001", "thread-ready-confirm")).thenReturn(0);
        when(userInboxNotificationService.countUnreadByAssistantUidAndThreadId("1001", "thread-ready-confirm")).thenReturn(0);

        Map<String, Object> snapshot = chatConversationHistoryService.findThreadStateSnapshot("1001", "thread-ready-confirm")
                .orElseThrow();

        assertThat(snapshot).containsEntry("status", "WAITING_CONFIRMATION");
        assertThat(snapshot).containsEntry("phase", "CONFIRMING");
        assertThat(snapshot).containsEntry("pendingCardType", "FORM_CARD");
        assertThat(((Map<String, Object>) snapshot.get("pendingForm"))).containsEntry("mode", "CONFIRM");
        assertThat(((Map<String, Object>) snapshot.get("pendingForm"))).containsEntry("status", "WAITING_CONFIRMATION");
        assertThat(snapshot).containsEntry("lastMessage", "审批人");
    }

    @Test
    void shouldFlattenPersistedEnvelopeWhenListingMessages() throws Exception {
        ChatThreadRecord threadRecord = new ChatThreadRecord();
        threadRecord.setThreadId("thread-confirm");
        threadRecord.setAssistantUid("1001");

        ChatMessageRecord messageRecord = new ChatMessageRecord();
        messageRecord.setMessageId("msg-form");
        messageRecord.setThreadId("thread-confirm");
        messageRecord.setAssistantUid("1001");
        messageRecord.setTurnId("turn-1");
        messageRecord.setMessageType("FORM_CARD");
        messageRecord.setEventType("FORM_STATE");
        messageRecord.setStage("COLLECTING");
        messageRecord.setStatus("WAITING_INPUT");
        messageRecord.setTitle("请确认请假信息");
        messageRecord.setSummaryText("审批人");
        messageRecord.setPayloadJson(new ObjectMapper().writeValueAsString(Map.of(
                "protocolVersion", "2026-03-13",
                "eventId", "evt-form",
                "threadId", "thread-confirm",
                "timestamp", "2026-03-13T12:00:05Z",
                "eventType", "FORM_STATE",
                "stage", "COLLECTING",
                "payload", Map.of(
                        "mode", "COLLECT",
                        "status", "READY_TO_CONFIRM",
                        "phase", "READY_TO_CONFIRM",
                        "toolCode", "gougu_oa.leave_application",
                        "values", Map.of("types", 1, "check_uids", "4"),
                        "canSubmit", true))));
        messageRecord.setCollapsed(false);
        messageRecord.setRevisionNo(2);

        when(chatThreadRecordService.findByThreadId("thread-confirm")).thenReturn(Optional.of(threadRecord));
        when(chatMessageRecordService.listByThreadId("thread-confirm", "1001", 100)).thenReturn(List.of(messageRecord));

        ChatMessageData message = chatConversationHistoryService.listMessages("1001", "thread-confirm", 100)
                .messages()
                .get(0);

        assertThat(message.stage()).isEqualTo("CONFIRMING");
        assertThat(message.status()).isEqualTo("WAITING_CONFIRMATION");
        assertThat(message.payload()).containsEntry("mode", "CONFIRM");
        assertThat(message.payload()).containsEntry("status", "WAITING_CONFIRMATION");
        assertThat(message.payload()).containsEntry("toolCode", "gougu_oa.leave_application");
        assertThat(message.meta()).containsEntry("eventId", "evt-form");
        assertThat(message.meta()).containsEntry("stage", "CONFIRMING");
        assertThat(message.meta()).containsEntry("protocolVersion", "2026-03-13");
    }

    @Test
    void shouldNormalizeDetachedTaskCardWhenListingMessages() throws Exception {
        ChatThreadRecord threadRecord = new ChatThreadRecord();
        threadRecord.setThreadId("thread-mcp");
        threadRecord.setAssistantUid("1001");
        threadRecord.setStatus("RUNNING");
        threadRecord.setPhase("EXECUTING");
        threadRecord.setUnfinished(true);
        threadRecord.setCanResume(false);

        ChatMessageRecord messageRecord = new ChatMessageRecord();
        messageRecord.setMessageId("msg-task");
        messageRecord.setThreadId("thread-mcp");
        messageRecord.setAssistantUid("1001");
        messageRecord.setTurnId("turn-task");
        messageRecord.setMessageType("TASK_CARD");
        messageRecord.setEventType("TASK_STATE");
        messageRecord.setStage("EXECUTING");
        messageRecord.setStatus("RUNNING");
        messageRecord.setTitle("数据 Agent 分析任务");
        messageRecord.setSummaryText("已完成 2/3 批 (65%)");
        messageRecord.setPayloadJson(new ObjectMapper().writeValueAsString(Map.of(
                "protocolVersion", "2026-03-13",
                "eventId", "evt-task",
                "threadId", "thread-mcp",
                "timestamp", "2026-03-13T12:10:05Z",
                "eventType", "TASK_STATE",
                "stage", "EXECUTING",
                "payload", Map.of(
                        "taskId", "TASK-MCP-1",
                        "status", "IN_PROGRESS",
                        "taskType", "SUB_AGENT_CALL",
                        "title", "数据 Agent 分析任务",
                        "sourceType", "SUB_AGENT",
                        "sourceCode", "mcp:data-agent",
                        "progressPercent", 65,
                        "background", true,
                        "detached", true,
                        "liveOutput", List.of(Map.of(
                                "eventType", "PROGRESS",
                                "text", "已完成 2/3 批",
                                "occurredAt", "2026-03-13T12:10:00Z"))))));
        messageRecord.setCollapsed(true);
        messageRecord.setRevisionNo(1);

        when(chatThreadRecordService.findByThreadId("thread-mcp")).thenReturn(Optional.of(threadRecord));
        when(chatMessageRecordService.listByThreadId("thread-mcp", "1001", 100)).thenReturn(List.of(messageRecord));
        when(chatMessageRecordService.listByThreadId("thread-mcp", "1001", 200)).thenReturn(List.of(messageRecord));
        when(agentTaskService.countActiveByAssistantUidAndThreadId("1001", "thread-mcp")).thenReturn(1);
        when(userInboxNotificationService.countUnreadByAssistantUidAndThreadId("1001", "thread-mcp")).thenReturn(0);

        ChatMessageData message = chatConversationHistoryService.listMessages("1001", "thread-mcp", 100)
                .messages()
                .get(0);
        assertThat(message.stage()).isEqualTo("EXECUTING");
        assertThat(message.status()).isEqualTo("RUNNING");
        assertThat(message.payload()).containsEntry("background", true);
        assertThat(message.payload()).containsEntry("detached", true);
        assertThat(message.payload()).containsEntry("summaryText", "已完成 2/3 批 (65%)");
        assertThat(((Map<String, Object>) message.payload().get("display"))).containsEntry("collapsedByDefault", true);

        Map<String, Object> snapshot = chatConversationHistoryService.findThreadStateSnapshot("1001", "thread-mcp")
                .orElseThrow();
        assertThat(snapshot).containsEntry("status", "RUNNING");
        assertThat(snapshot).containsEntry("phase", "EXECUTING");
        assertThat(snapshot).containsEntry("pendingCardType", "TASK_CARD");
        assertThat(snapshot).containsEntry("lastMessage", "已完成 2/3 批 (65%)");
    }
}

