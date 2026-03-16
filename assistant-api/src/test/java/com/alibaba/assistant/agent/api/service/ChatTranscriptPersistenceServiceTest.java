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

import com.alibaba.assistant.agent.api.protocol.FrontendEvent;
import com.alibaba.assistant.agent.api.protocol.FrontendEventType;
import com.alibaba.assistant.agent.api.protocol.FrontendStage;
import com.alibaba.assistant.agent.execution.persistence.ChatMessageRecord;
import com.alibaba.assistant.agent.execution.persistence.ChatMessageRecordService;
import com.alibaba.assistant.agent.execution.persistence.ChatThreadRecord;
import com.alibaba.assistant.agent.execution.persistence.ChatThreadRecordService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChatTranscriptPersistenceServiceTest {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    @Mock
    private ChatThreadRecordService chatThreadRecordService;

    @Mock
    private ChatMessageRecordService chatMessageRecordService;

    private ObjectMapper objectMapper;

    private ChatTranscriptPersistenceService transcriptPersistenceService;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        transcriptPersistenceService = new ChatTranscriptPersistenceService(
                chatThreadRecordService,
                chatMessageRecordService,
                objectMapper);
    }

    @Test
    void shouldPersistUserMessageAndWaitingConfirmationFormState() throws Exception {
        when(chatThreadRecordService.findByThreadId("thread-confirm")).thenReturn(Optional.empty());
        when(chatMessageRecordService.findBySourceKey("FORM_CARD:turn-1:gougu_oa.leave_application:CONFIRM"))
                .thenReturn(Optional.empty());

        transcriptPersistenceService.recordUserMessage(
                "thread-confirm",
                "1001",
                "assistant-ui",
                "gougu_oa",
                "turn-1",
                "明天请一天事假");

        transcriptPersistenceService.recordFrontendEvent(
                "thread-confirm",
                "1001",
                "assistant-ui",
                "gougu_oa",
                "turn-1",
                new FrontendEvent(
                        "2026-03-13",
                        "evt-confirm",
                        "thread-confirm",
                        "2026-03-13T12:00:00Z",
                        FrontendEventType.FORM_STATE,
                        FrontendStage.CONFIRMING,
                        new LinkedHashMap<>(Map.of(
                                "mode", "CONFIRM",
                                "status", "WAITING_CONFIRMATION",
                                "toolCode", "gougu_oa.leave_application",
                                "message", "请确认请假信息",
                                "values", Map.of("types", 1, "reason", "个人事务"),
                                "summary", Map.of("summaryItems", java.util.List.of(Map.of(
                                        "label", "审批人",
                                        "value", "直属上级")))))));

        ArgumentCaptor<ChatThreadRecord> threadCaptor = ArgumentCaptor.forClass(ChatThreadRecord.class);
        verify(chatThreadRecordService, org.mockito.Mockito.atLeastOnce()).saveOrUpdateByThreadId(threadCaptor.capture());
        ChatThreadRecord latestThread = threadCaptor.getAllValues().get(threadCaptor.getAllValues().size() - 1);
        assertThat(latestThread.getThreadId()).isEqualTo("thread-confirm");
        assertThat(latestThread.getAssistantUid()).isEqualTo("1001");
        assertThat(latestThread.getStatus()).isEqualTo("WAITING_CONFIRMATION");
        assertThat(latestThread.getPhase()).isEqualTo("CONFIRMING");
        assertThat(latestThread.getUnfinished()).isTrue();
        assertThat(latestThread.getCanResume()).isTrue();
        assertThat(latestThread.getToolCode()).isEqualTo("gougu_oa.leave_application");
        assertThat(latestThread.getPendingCardType()).isEqualTo("FORM_CARD");
        assertThat(latestThread.getLastMessagePreview()).isEqualTo("审批人：直属上级");

        ArgumentCaptor<ChatMessageRecord> messageCaptor = ArgumentCaptor.forClass(ChatMessageRecord.class);
        verify(chatMessageRecordService).save(any(ChatMessageRecord.class));
        verify(chatMessageRecordService).saveOrUpdateBySourceKey(messageCaptor.capture());
        ChatMessageRecord formCard = messageCaptor.getValue();
        assertThat(formCard.getThreadId()).isEqualTo("thread-confirm");
        assertThat(formCard.getMessageType()).isEqualTo("FORM_CARD");
        assertThat(formCard.getStatus()).isEqualTo("WAITING_CONFIRMATION");
        assertThat(formCard.getCollapsed()).isFalse();

        Map<String, Object> persistedPayload = objectMapper.readValue(formCard.getPayloadJson(), MAP_TYPE);
        assertThat(persistedPayload).containsEntry("eventType", "FORM_STATE");
        assertThat(persistedPayload).containsEntry("threadId", "thread-confirm");
        assertThat((Map<String, Object>) persistedPayload.get("payload")).containsEntry("mode", "CONFIRM");
    }

    @Test
    void shouldNormalizeReadyToConfirmFormStateBeforePersisting() throws Exception {
        when(chatThreadRecordService.findByThreadId("thread-ready-confirm")).thenReturn(Optional.empty());
        when(chatMessageRecordService.findBySourceKey("FORM_CARD:turn-ready-confirm:gougu_oa.leave_application:CONFIRM"))
                .thenReturn(Optional.empty());

        transcriptPersistenceService.recordFrontendEvent(
                "thread-ready-confirm",
                "1001",
                "assistant-ui",
                "gougu_oa",
                "turn-ready-confirm",
                new FrontendEvent(
                        "2026-03-13",
                        "evt-ready-confirm",
                        "thread-ready-confirm",
                        "2026-03-13T12:00:10Z",
                        FrontendEventType.FORM_STATE,
                        FrontendStage.CONFIRMING,
                        new LinkedHashMap<>(Map.of(
                                "mode", "COLLECT",
                                "status", "READY_TO_CONFIRM",
                                "phase", "READY_TO_CONFIRM",
                                "toolCode", "gougu_oa.leave_application",
                                "message", "请确认请假信息",
                                "values", Map.of("types", 1)))));

        ChatThreadRecord latestThread = threadCaptorFromLatestSave();
        assertThat(latestThread.getStatus()).isEqualTo("WAITING_CONFIRMATION");
        assertThat(latestThread.getPhase()).isEqualTo("CONFIRMING");
        assertThat(latestThread.getPendingCardType()).isEqualTo("FORM_CARD");

        ArgumentCaptor<ChatMessageRecord> messageCaptor = ArgumentCaptor.forClass(ChatMessageRecord.class);
        verify(chatMessageRecordService).saveOrUpdateBySourceKey(messageCaptor.capture());
        ChatMessageRecord formCard = messageCaptor.getValue();
        assertThat(formCard.getStatus()).isEqualTo("WAITING_CONFIRMATION");
        Map<String, Object> persistedPayload = objectMapper.readValue(formCard.getPayloadJson(), MAP_TYPE);
        assertThat((Map<String, Object>) persistedPayload.get("payload")).containsEntry("mode", "CONFIRM");
        assertThat(persistedPayload).containsEntry("stage", "CONFIRMING");
    }

    @Test
    void shouldFoldAssistantChunksIntoSingleTranscriptRowAndTrackTaskStatus() throws Exception {
        ChatMessageRecord existingAssistantMessage = new ChatMessageRecord();
        existingAssistantMessage.setId(11L);
        existingAssistantMessage.setMessageId("msg-assistant");
        existingAssistantMessage.setSourceKey("ASSISTANT_STREAM:turn-2");
        existingAssistantMessage.setPayloadJson(objectMapper.writeValueAsString(Map.of("text", "请假申请已")));
        existingAssistantMessage.setRevisionNo(1);

        ChatMessageRecord existingTaskCard = new ChatMessageRecord();
        existingTaskCard.setId(21L);
        existingTaskCard.setMessageId("msg-task");
        existingTaskCard.setSourceKey("TASK_CARD:TASK-99");
        existingTaskCard.setRevisionNo(1);

        when(chatThreadRecordService.findByThreadId("thread-task")).thenReturn(Optional.of(new ChatThreadRecord()));
        when(chatMessageRecordService.findBySourceKey("ASSISTANT_STREAM:turn-2"))
                .thenReturn(Optional.of(existingAssistantMessage));
        when(chatMessageRecordService.findBySourceKey("TASK_CARD:TASK-99"))
                .thenReturn(Optional.of(existingTaskCard));

        transcriptPersistenceService.recordFrontendEvent(
                "thread-task",
                "1001",
                "assistant-ui",
                "gougu_oa",
                "turn-2",
                new FrontendEvent(
                        "2026-03-13",
                        "evt-msg",
                        "thread-task",
                        "2026-03-13T12:01:00Z",
                        FrontendEventType.MESSAGE,
                        FrontendStage.DONE,
                        Map.of("text", "创建成功，等待审批。")));

        transcriptPersistenceService.recordFrontendEvent(
                "thread-task",
                "1001",
                "assistant-ui",
                "gougu_oa",
                "turn-2",
                new FrontendEvent(
                        "2026-03-13",
                        "evt-task",
                        "thread-task",
                        "2026-03-13T12:02:00Z",
                        FrontendEventType.TASK_STATE,
                        FrontendStage.WAITING_APPROVAL,
                        new LinkedHashMap<>(Map.of(
                                "taskId", "TASK-99",
                                "status", "WAITING_APPROVAL",
                                "title", "数据 Agent 分析中",
                                "sourceType", "SUB_AGENT",
                                "sourceCode", "mcp:data-agent",
                                "collapsible", true,
                                "liveOutput", java.util.List.of(Map.of("text", "已完成 2/3 批"))))));

        transcriptPersistenceService.recordFrontendEvent(
                "thread-task",
                "1001",
                "assistant-ui",
                "gougu_oa",
                "turn-2",
                new FrontendEvent(
                        "2026-03-13",
                        "evt-result",
                        "thread-task",
                        "2026-03-13T12:03:00Z",
                        FrontendEventType.RESULT,
                        FrontendStage.DONE,
                        new LinkedHashMap<>(Map.of(
                                "success", true,
                                "artifactCode", "data.agent.report",
                                "result", Map.of("summary", "报告已生成", "reportId", "R-100")))));

        ArgumentCaptor<ChatMessageRecord> cardCaptor = ArgumentCaptor.forClass(ChatMessageRecord.class);
        verify(chatMessageRecordService, org.mockito.Mockito.times(2)).saveOrUpdateBySourceKey(cardCaptor.capture());

        ChatMessageRecord assistantMessage = cardCaptor.getAllValues().stream()
                .filter(item -> "ASSISTANT_MESSAGE".equals(item.getMessageType()))
                .findFirst()
                .orElseThrow();
        Map<String, Object> assistantPayload = objectMapper.readValue(assistantMessage.getPayloadJson(), MAP_TYPE);
        assertThat(assistantMessage.getRevisionNo()).isEqualTo(2);
        assertThat(assistantMessage.getSummaryText()).contains("请假申请已创建成功");
        assertThat(assistantPayload).containsEntry("eventType", "MESSAGE");

        ChatMessageRecord taskCard = cardCaptor.getAllValues().stream()
                .filter(item -> "TASK_CARD".equals(item.getMessageType()))
                .findFirst()
                .orElseThrow();
        assertThat(taskCard.getCollapsed()).isTrue();
        assertThat(taskCard.getStatus()).isEqualTo("WAITING_APPROVAL");

        ChatThreadRecord terminalThread = threadCaptorFromLatestSave();
        assertThat(terminalThread.getStatus()).isEqualTo("COMPLETED");
        assertThat(terminalThread.getPhase()).isEqualTo("DONE");
        assertThat(terminalThread.getUnfinished()).isFalse();
        assertThat(terminalThread.getCanResume()).isFalse();
    }

    @Test
    void shouldReplaceAssistantTranscriptWhenIncomingChunkAlreadyContainsExistingPrefix() throws Exception {
        ChatMessageRecord existingAssistantMessage = new ChatMessageRecord();
        existingAssistantMessage.setId(31L);
        existingAssistantMessage.setMessageId("msg-assistant-dup");
        existingAssistantMessage.setSourceKey("ASSISTANT_STREAM:turn-dup");
        existingAssistantMessage.setPayloadJson(objectMapper.writeValueAsString(Map.of(
                "text", "我来帮您处理明天请一天事假的申请。")));
        existingAssistantMessage.setRevisionNo(1);

        when(chatThreadRecordService.findByThreadId("thread-dup")).thenReturn(Optional.of(new ChatThreadRecord()));
        when(chatMessageRecordService.findBySourceKey("ASSISTANT_STREAM:turn-dup"))
                .thenReturn(Optional.of(existingAssistantMessage));

        transcriptPersistenceService.recordFrontendEvent(
                "thread-dup",
                "1001",
                "assistant-ui",
                "gougu_oa",
                "turn-dup",
                new FrontendEvent(
                        "2026-03-13",
                        "evt-msg-dup",
                        "thread-dup",
                        "2026-03-13T12:02:30Z",
                        FrontendEventType.MESSAGE,
                        FrontendStage.DONE,
                        Map.of("text", "我来帮您处理明天请一天事假的申请。首先，我需要收集必要的请假信息。")));

        ArgumentCaptor<ChatMessageRecord> messageCaptor = ArgumentCaptor.forClass(ChatMessageRecord.class);
        verify(chatMessageRecordService).saveOrUpdateBySourceKey(messageCaptor.capture());
        ChatMessageRecord assistantMessage = messageCaptor.getValue();
        Map<String, Object> assistantPayload = objectMapper.readValue(assistantMessage.getPayloadJson(), MAP_TYPE);
        assertThat(assistantPayload).containsEntry("text", "我来帮您处理明天请一天事假的申请。首先，我需要收集必要的请假信息。");
        assertThat(assistantMessage.getSummaryText()).doesNotContain("申请。申请。");
    }
    @Test
    void shouldIgnoreInternalPlanningNarrationMessage() {
        ChatThreadRecord existingThread = new ChatThreadRecord();
        existingThread.setThreadId("thread-internal");
        existingThread.setAssistantUid("1001");

        when(chatThreadRecordService.findByThreadId("thread-internal")).thenReturn(Optional.of(existingThread));

        transcriptPersistenceService.recordFrontendEvent(
                "thread-internal",
                "1001",
                "assistant-ui",
                "gougu_oa",
                "turn-internal",
                new FrontendEvent(
                        "2026-03-15",
                        "evt-internal",
                        "thread-internal",
                        "2026-03-15T09:00:00Z",
                        FrontendEventType.MESSAGE,
                        FrontendStage.DONE,
                        Map.of("text", "用户明确表示“我要写汇报”，结合上下文，意图清晰对应工具 `gougu_oa.work_report`（工作汇报）。根据执行策略，操作型请求需先调收集必要参数。当前可用且匹配 `gougu_oa.work_report`。我将启动槽位收集流程，系统将自动加载该工具所需的 slotSchema，并识别当前缺失的必填字段。")));

        verify(chatThreadRecordService, never()).saveOrUpdateByThreadId(any(ChatThreadRecord.class));
        verify(chatMessageRecordService, never()).saveOrUpdateBySourceKey(any(ChatMessageRecord.class));
    }

    @Test
    void shouldIgnoreAssistantMessageAfterResultCardHasBeenPersisted() {
        ChatThreadRecord existingThread = new ChatThreadRecord();
        existingThread.setThreadId("thread-result");
        existingThread.setAssistantUid("1001");
        existingThread.setPendingCardType("RESULT_CARD");
        existingThread.setLastEventType("RESULT");

        when(chatThreadRecordService.findByThreadId("thread-result")).thenReturn(Optional.of(existingThread));

        transcriptPersistenceService.recordFrontendEvent(
                "thread-result",
                "1001",
                "assistant-ui",
                "gougu_oa",
                "turn-3",
                new FrontendEvent(
                        "2026-03-13",
                        "evt-followup",
                        "thread-result",
                        "2026-03-13T12:05:00Z",
                        FrontendEventType.MESSAGE,
                        FrontendStage.DONE,
                        Map.of("text", "这是结果之后的冗余补充")));

        verify(chatThreadRecordService, never()).saveOrUpdateByThreadId(any(ChatThreadRecord.class));
        verify(chatMessageRecordService, never()).saveOrUpdateBySourceKey(any(ChatMessageRecord.class));
    }

    @Test
    void shouldNormalizeDetachedMcpTaskCardBeforePersisting() throws Exception {
        when(chatThreadRecordService.findByThreadId("thread-mcp")).thenReturn(Optional.empty());
        when(chatMessageRecordService.findBySourceKey("TASK_CARD:TASK-MCP-1"))
                .thenReturn(Optional.empty());

        transcriptPersistenceService.recordFrontendEvent(
                "thread-mcp",
                "1001",
                "assistant-ui",
                "gougu_oa",
                "turn-mcp-1",
                new FrontendEvent(
                        "2026-03-13",
                        "evt-mcp-running",
                        "thread-mcp",
                        "2026-03-13T12:10:00Z",
                        FrontendEventType.TASK_STATE,
                        FrontendStage.EXECUTING,
                        buildDetachedMcpTaskPayload()));

        ChatThreadRecord latestThread = threadCaptorFromLatestSave();
        assertThat(latestThread.getStatus()).isEqualTo("RUNNING");
        assertThat(latestThread.getPhase()).isEqualTo("EXECUTING");
        assertThat(latestThread.getUnfinished()).isTrue();
        assertThat(latestThread.getCanResume()).isFalse();
        assertThat(latestThread.getPendingCardType()).isEqualTo("TASK_CARD");
        assertThat(latestThread.getLastMessagePreview()).isEqualTo("已完成 2/3 批 (65%)");

        ArgumentCaptor<ChatMessageRecord> messageCaptor = ArgumentCaptor.forClass(ChatMessageRecord.class);
        verify(chatMessageRecordService).saveOrUpdateBySourceKey(messageCaptor.capture());
        ChatMessageRecord taskCard = messageCaptor.getValue();
        assertThat(taskCard.getStage()).isEqualTo("EXECUTING");
        assertThat(taskCard.getStatus()).isEqualTo("RUNNING");
        assertThat(taskCard.getCollapsed()).isTrue();
        assertThat(taskCard.getSummaryText()).isEqualTo("已完成 2/3 批 (65%)");

        Map<String, Object> persistedPayload = objectMapper.readValue(taskCard.getPayloadJson(), MAP_TYPE);
        Map<String, Object> taskPayload = (Map<String, Object>) persistedPayload.get("payload");
        assertThat(taskPayload).containsEntry("background", true);
        assertThat(taskPayload).containsEntry("detached", true);
        assertThat(taskPayload).containsEntry("status", "RUNNING");
        assertThat(taskPayload).containsEntry("summaryText", "已完成 2/3 批 (65%)");
        assertThat(((Map<String, Object>) taskPayload.get("display"))).containsEntry("collapsedByDefault", true);
    }
    private Map<String, Object> buildDetachedMcpTaskPayload() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("taskId", "TASK-MCP-1");
        payload.put("status", "IN_PROGRESS");
        payload.put("taskType", "SUB_AGENT_CALL");
        payload.put("title", "数据 Agent 分析任务");
        payload.put("sourceType", "SUB_AGENT");
        payload.put("sourceCode", "mcp:data-agent");
        payload.put("progressPercent", 65);
        payload.put("background", true);
        payload.put("detached", true);
        payload.put("collapsible", true);
        payload.put("liveOutput", java.util.List.of(Map.of(
                "eventType", "PROGRESS",
                "text", "已完成 2/3 批",
                "occurredAt", "2026-03-13T12:10:00Z")));
        return payload;
    }

    private ChatThreadRecord threadCaptorFromLatestSave() {
        ArgumentCaptor<ChatThreadRecord> threadCaptor = ArgumentCaptor.forClass(ChatThreadRecord.class);
        verify(chatThreadRecordService, org.mockito.Mockito.atLeastOnce()).saveOrUpdateByThreadId(threadCaptor.capture());
        return threadCaptor.getAllValues().get(threadCaptor.getAllValues().size() - 1);
    }
}
















