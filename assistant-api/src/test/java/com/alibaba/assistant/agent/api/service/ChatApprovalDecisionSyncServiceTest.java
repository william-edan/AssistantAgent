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
import com.alibaba.assistant.agent.api.protocol.FrontendEventType;
import com.alibaba.assistant.agent.api.protocol.V3ProtocolAdapter;
import com.alibaba.assistant.agent.runtime.execution.ExecutionApprovalDecisionView;
import com.alibaba.assistant.agent.runtime.execution.ExecutionApprovalDetailView;
import com.alibaba.assistant.agent.runtime.execution.ExecutionApprovalService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatApprovalDecisionSyncServiceTest {

    @Test
    void shouldPublishCompletedTaskEventsAfterApproval() {
        ExecutionApprovalService executionApprovalService = mock(ExecutionApprovalService.class);
        ChatFrontendEventPublisher eventPublisher = mock(ChatFrontendEventPublisher.class);
        ChatTaskService chatTaskService = mock(ChatTaskService.class);
        ChatApprovalDecisionSyncService service = new ChatApprovalDecisionSyncService(
                executionApprovalService,
                new ChatTaskFrontendEventService(new V3ProtocolAdapter(new ObjectMapper())),
                eventPublisher,
                chatTaskService);

        ExecutionApprovalDetailView detailView = new ExecutionApprovalDetailView(
                "REQ-1",
                "RUN-1",
                "oa.leave.apply",
                "WORKFLOW",
                11L,
                "finance-space",
                "prod",
                "submit_approval",
                "APPROVED",
                "COMPLETED",
                "platform",
                "2001",
                null,
                "1001",
                "T-1",
                LocalDateTime.of(2026, 3, 15, 23, 0),
                LocalDateTime.of(2026, 3, 15, 23, 1));
        ExecutionApprovalDecisionView decisionView = new ExecutionApprovalDecisionView(
                "REQ-1",
                "RUN-1",
                "oa.leave.apply",
                "WORKFLOW",
                11L,
                "finance-space",
                "prod",
                "submit_approval",
                "APPROVED",
                "COMPLETED",
                "platform",
                "2001",
                "1001",
                LocalDateTime.of(2026, 3, 15, 23, 0),
                LocalDateTime.of(2026, 3, 15, 23, 1));
        ChatTaskData taskData = new ChatTaskData(
                "RUN-1",
                "T-1",
                "ARTIFACT_EXECUTION",
                "请假申请",
                "COMPLETED",
                "ARTIFACT_EXECUTION",
                "oa.leave.apply",
                100,
                true,
                true,
                "2026-03-15T23:00:00",
                "2026-03-15T23:01:00",
                "审批已通过",
                "审批已通过",
                false,
                false,
                Map.of("showInChat", true),
                List.of(new ChatTaskEventItemData("evt-1", "RUN-1", "TASK_STATE", "COMPLETED", 2L, "2026-03-15T23:01:00", Map.of("text", "审批已通过"))),
                Map.of("summary", "审批已通过，任务已完成"),
                Map.of("type", "TASK_DETAIL", "targetId", "RUN-1"));

        when(executionApprovalService.findRequest("finance-space", "prod", "REQ-1")).thenReturn(Optional.of(detailView));
        when(chatTaskService.getTask("1001", "RUN-1")).thenReturn(taskData);

        service.publishDecision("finance-space", "prod", "REQ-1", decisionView);

        ArgumentCaptor<FrontendEvent> eventCaptor = ArgumentCaptor.forClass(FrontendEvent.class);
        verify(eventPublisher, times(2)).publish(eq("T-1"), eq("1001"), eq(null), eq(null), eq("REQ-1:approval-decision"), eventCaptor.capture());
        assertEquals(FrontendEventType.TASK_STATE, eventCaptor.getAllValues().get(0).eventType());
        assertEquals(FrontendEventType.RESULT, eventCaptor.getAllValues().get(1).eventType());
        verify(eventPublisher).finishTurn("T-1", "1001", null, null, "REQ-1:approval-decision");
    }

    @Test
    void shouldSynthesizeFailedTaskEventsWhenApprovalRejected() {
        ExecutionApprovalService executionApprovalService = mock(ExecutionApprovalService.class);
        ChatFrontendEventPublisher eventPublisher = mock(ChatFrontendEventPublisher.class);
        ChatTaskService chatTaskService = mock(ChatTaskService.class);
        ChatApprovalDecisionSyncService service = new ChatApprovalDecisionSyncService(
                executionApprovalService,
                new ChatTaskFrontendEventService(new V3ProtocolAdapter(new ObjectMapper())),
                eventPublisher,
                chatTaskService);

        ExecutionApprovalDetailView detailView = new ExecutionApprovalDetailView(
                "REQ-2",
                "RUN-2",
                "oa.leave.apply",
                "WORKFLOW",
                11L,
                "finance-space",
                "prod",
                "submit_approval",
                "REJECTED",
                "CANCELLED",
                "platform",
                "2001",
                null,
                "1001",
                "T-2",
                LocalDateTime.of(2026, 3, 15, 23, 0),
                LocalDateTime.of(2026, 3, 15, 23, 1));
        ExecutionApprovalDecisionView decisionView = new ExecutionApprovalDecisionView(
                "REQ-2",
                "RUN-2",
                "oa.leave.apply",
                "WORKFLOW",
                11L,
                "finance-space",
                "prod",
                "submit_approval",
                "REJECTED",
                "CANCELLED",
                "platform",
                "2001",
                "1001",
                LocalDateTime.of(2026, 3, 15, 23, 0),
                LocalDateTime.of(2026, 3, 15, 23, 1));

        when(executionApprovalService.findRequest("finance-space", "prod", "REQ-2")).thenReturn(Optional.of(detailView));
        when(chatTaskService.getTask("1001", "RUN-2")).thenThrow(new RuntimeException("task missing"));

        service.publishDecision("finance-space", "prod", "REQ-2", decisionView);

        ArgumentCaptor<FrontendEvent> eventCaptor = ArgumentCaptor.forClass(FrontendEvent.class);
        verify(eventPublisher, times(2)).publish(eq("T-2"), eq("1001"), eq(null), eq(null), eq("REQ-2:approval-decision"), eventCaptor.capture());
        assertEquals(FrontendEventType.TASK_STATE, eventCaptor.getAllValues().get(0).eventType());
        assertEquals(FrontendEventType.RESULT, eventCaptor.getAllValues().get(1).eventType());
        assertEquals(Boolean.FALSE, eventCaptor.getAllValues().get(1).payload().get("success"));
        assertTrue(String.valueOf(eventCaptor.getAllValues().get(1).payload().get("error")).contains("审批已拒绝"));
        verify(eventPublisher).finishTurn("T-2", "1001", null, null, "REQ-2:approval-decision");
    }
}
