/*
 * Copyright 2024-2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 */
package com.alibaba.assistant.agent.runtime.task;

import com.alibaba.assistant.agent.execution.flow.FlowContext;
import com.alibaba.assistant.agent.execution.flow.FlowDefinition;
import com.alibaba.assistant.agent.execution.persistence.AgentTask;
import com.alibaba.assistant.agent.execution.persistence.AgentTaskEvent;
import com.alibaba.assistant.agent.execution.persistence.AgentTaskEventService;
import com.alibaba.assistant.agent.execution.persistence.AgentTaskService;
import com.alibaba.assistant.agent.execution.persistence.UserInboxNotification;
import com.alibaba.assistant.agent.execution.persistence.UserInboxNotificationService;
import com.alibaba.assistant.agent.runtime.agent.AssistantStateKeys;
import com.alibaba.assistant.agent.runtime.compiler.RuntimeArtifact;
import com.alibaba.assistant.agent.runtime.execution.ExecutionEvent;
import com.alibaba.assistant.agent.runtime.execution.ExecutionEventType;
import com.alibaba.assistant.agent.runtime.execution.ExecutionLifecycleStatus;
import com.alibaba.assistant.agent.runtime.registry.PublishedToolDescriptor;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentTaskProjectorTest {

    @Test
    void shouldPersistTerminalSubAgentTaskWithoutUppercasingIdentifiers() {
        AgentTaskService agentTaskService = mock(AgentTaskService.class);
        AgentTaskEventService agentTaskEventService = mock(AgentTaskEventService.class);
        UserInboxNotificationService userInboxNotificationService = mock(UserInboxNotificationService.class);
        when(agentTaskService.findLatestByTaskId("task-abc")).thenReturn(Optional.empty());
        when(userInboxNotificationService.findLatestByNotificationId("N-1")).thenReturn(Optional.empty());
        AgentTaskProjector projector = new AgentTaskProjector(
                agentTaskService,
                agentTaskEventService,
                userInboxNotificationService,
                new ObjectMapper());

        projector.recordTaskState(
                "thread-1",
                Map.of(
                        AssistantStateKeys.ASSISTANT_UID, "1001",
                        AssistantStateKeys.THREAD_ID, "thread-1"),
                Map.of(
                        "taskId", "task-abc",
                        "status", "COMPLETED",
                        "taskType", "SUB_AGENT_CALL",
                        "sourceType", "SUB_AGENT",
                        "sourceCode", "mcp:data-agent",
                        "title", "数据分析任务",
                        "resultReady", true,
                        "resultPreview", Map.of("reportId", "R-1"),
                        "notification", Map.of(
                                "notificationId", "N-1",
                                "title", "分析已完成",
                                "body", "点击查看结果")));

        ArgumentCaptor<AgentTask> taskCaptor = ArgumentCaptor.forClass(AgentTask.class);
        verify(agentTaskService).saveOrUpdateByTaskId(taskCaptor.capture());
        assertEquals("task-abc", taskCaptor.getValue().getTaskId());
        assertEquals("mcp:data-agent", taskCaptor.getValue().getSourceCode());
        assertEquals("COMPLETED", taskCaptor.getValue().getStatus());

        ArgumentCaptor<AgentTaskEvent> eventCaptor = ArgumentCaptor.forClass(AgentTaskEvent.class);
        verify(agentTaskEventService).saveIfAbsent(eventCaptor.capture());
        assertEquals("task-abc", eventCaptor.getValue().getTaskId());

        ArgumentCaptor<UserInboxNotification> notificationCaptor = ArgumentCaptor.forClass(UserInboxNotification.class);
        verify(userInboxNotificationService).saveOrUpdateByNotificationId(notificationCaptor.capture());
        assertEquals("N-1", notificationCaptor.getValue().getNotificationId());
        assertEquals("分析已完成", notificationCaptor.getValue().getTitle());
    }

    @Test
    void shouldNormalizeDetachedRunningSubAgentTaskBeforePersisting() {
        AgentTaskService agentTaskService = mock(AgentTaskService.class);
        AgentTaskEventService agentTaskEventService = mock(AgentTaskEventService.class);
        UserInboxNotificationService userInboxNotificationService = mock(UserInboxNotificationService.class);
        when(agentTaskService.findLatestByTaskId("TASK-DETACHED-1")).thenReturn(Optional.empty());
        AgentTaskProjector projector = new AgentTaskProjector(
                agentTaskService,
                agentTaskEventService,
                userInboxNotificationService,
                new ObjectMapper());

        projector.recordTaskState(
                "thread-detached",
                Map.of(
                        AssistantStateKeys.ASSISTANT_UID, "1001",
                        AssistantStateKeys.THREAD_ID, "thread-detached"),
                buildDetachedRunningTaskPayload());

        ArgumentCaptor<AgentTask> taskCaptor = ArgumentCaptor.forClass(AgentTask.class);
        verify(agentTaskService).saveOrUpdateByTaskId(taskCaptor.capture());
        assertEquals("RUNNING", taskCaptor.getValue().getStatus());
        assertEquals(Integer.valueOf(65), taskCaptor.getValue().getProgressPercent());
        assertEquals("mcp:data-agent", taskCaptor.getValue().getSourceCode());
        assertEquals(Boolean.TRUE, taskCaptor.getValue().getBackground());
        assertEquals(Boolean.TRUE, taskCaptor.getValue().getDetached());
        assertTrue(taskCaptor.getValue().getLatestOutputJson().contains("已完成 2/3 批"));
        assertTrue(taskCaptor.getValue().getActionJson().contains("TASK_DETAIL"));

        ArgumentCaptor<AgentTaskEvent> eventCaptor = ArgumentCaptor.forClass(AgentTaskEvent.class);
        verify(agentTaskEventService).saveIfAbsent(eventCaptor.capture());
        assertEquals("TASK-DETACHED-1", eventCaptor.getValue().getTaskId());
        assertEquals("RUNNING", eventCaptor.getValue().getStatus());
        assertTrue(eventCaptor.getValue().getPayloadJson().contains("PROGRESS"));

        verify(userInboxNotificationService, never()).saveOrUpdateByNotificationId(any());
    }

    @Test
    void shouldIgnoreInternalTaskStateProjection() {
        AgentTaskService agentTaskService = mock(AgentTaskService.class);
        AgentTaskEventService agentTaskEventService = mock(AgentTaskEventService.class);
        UserInboxNotificationService userInboxNotificationService = mock(UserInboxNotificationService.class);
        AgentTaskProjector projector = new AgentTaskProjector(
                agentTaskService,
                agentTaskEventService,
                userInboxNotificationService,
                new ObjectMapper());

        projector.recordTaskState(
                "thread-internal",
                Map.of(
                        AssistantStateKeys.ASSISTANT_UID, "1001",
                        AssistantStateKeys.THREAD_ID, "thread-internal"),
                Map.of(
                        "taskId", "TASK-INTERNAL-1",
                        "status", "COMPLETED",
                        "internal", true,
                        "toolType", "QUERY",
                        "visibility", "INTERNAL",
                        "invocationPolicy", "DEPENDENCY_ONLY",
                        "title", "员工解析"));

        verify(agentTaskService, never()).saveOrUpdateByTaskId(any());
        verify(agentTaskEventService, never()).saveIfAbsent(any());
        verify(userInboxNotificationService, never()).saveOrUpdateByNotificationId(any());
    }

    @Test
    void shouldNotDowngradeCompletedTaskWhenLateRunningStateArrives() {
        AgentTaskService agentTaskService = mock(AgentTaskService.class);
        AgentTaskEventService agentTaskEventService = mock(AgentTaskEventService.class);
        UserInboxNotificationService userInboxNotificationService = mock(UserInboxNotificationService.class);
        AgentTask existing = new AgentTask();
        existing.setId(1L);
        existing.setTaskId("TASK-2");
        existing.setStatus("COMPLETED");
        existing.setTitle("历史任务");
        existing.setAssistantUid("1001");
        existing.setThreadId("thread-2");
        when(agentTaskService.findLatestByTaskId("TASK-2")).thenReturn(Optional.of(existing));
        AgentTaskProjector projector = new AgentTaskProjector(
                agentTaskService,
                agentTaskEventService,
                userInboxNotificationService,
                new ObjectMapper());

        projector.recordTaskState(
                "thread-2",
                Map.of(
                        AssistantStateKeys.ASSISTANT_UID, "1001",
                        AssistantStateKeys.THREAD_ID, "thread-2"),
                Map.of(
                        "taskId", "TASK-2",
                        "status", "RUNNING",
                        "taskType", "SUB_AGENT_CALL",
                        "sourceType", "SUB_AGENT",
                        "sourceCode", "mcp:data-agent",
                        "title", "历史任务",
                        "progressPercent", 50));

        ArgumentCaptor<AgentTask> taskCaptor = ArgumentCaptor.forClass(AgentTask.class);
        verify(agentTaskService).saveOrUpdateByTaskId(taskCaptor.capture());
        assertEquals("COMPLETED", taskCaptor.getValue().getStatus());
        assertNull(taskCaptor.getValue().getProgressPercent());
        verify(userInboxNotificationService, never()).saveOrUpdateByNotificationId(any());
    }

    @Test
    void shouldProjectBatchProgressPercentFromExecutionEventPayload() {
        AgentTaskService agentTaskService = mock(AgentTaskService.class);
        AgentTaskEventService agentTaskEventService = mock(AgentTaskEventService.class);
        UserInboxNotificationService userInboxNotificationService = mock(UserInboxNotificationService.class);
        when(agentTaskService.findLatestByTaskId("run-1")).thenReturn(Optional.empty());
        AgentTaskProjector projector = new AgentTaskProjector(
                agentTaskService,
                agentTaskEventService,
                userInboxNotificationService,
                new ObjectMapper());

        projector.recordExecutionEvent(
                descriptor(),
                flowContext(),
                new ExecutionEvent(
                        "run-1",
                        "office1.approval_cleanup",
                        "WORKFLOW",
                        "approval_batch",
                        2L,
                        ExecutionEventType.STEP_PROGRESS,
                        ExecutionLifecycleStatus.RUNNING,
                        Instant.parse("2026-03-16T02:05:00Z"),
                        Map.of("batchProgress", Map.of("percent", 50, "processedItems", 1, "selectedItems", 2))));

        ArgumentCaptor<AgentTask> taskCaptor = ArgumentCaptor.forClass(AgentTask.class);
        verify(agentTaskService).saveOrUpdateByTaskId(taskCaptor.capture());
        assertEquals(Integer.valueOf(50), taskCaptor.getValue().getProgressPercent());
        assertEquals("RUNNING", taskCaptor.getValue().getStatus());
    }

    private Map<String, Object> buildDetachedRunningTaskPayload() {
        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("taskId", "TASK-DETACHED-1");
        payload.put("status", "IN_PROGRESS");
        payload.put("taskType", "SUB_AGENT_CALL");
        payload.put("sourceType", "SUB_AGENT");
        payload.put("sourceCode", "mcp:data-agent");
        payload.put("title", "数据分析任务");
        payload.put("progressPercent", 65);
        payload.put("background", true);
        payload.put("detached", true);
        payload.put("collapsible", true);
        payload.put("liveOutput", java.util.List.of(Map.of(
                "eventType", "PROGRESS",
                "text", "已完成 2/3 批",
                "occurredAt", "2026-03-13T10:02:00Z")));
        return payload;
    }

    private PublishedToolDescriptor descriptor() {
        FlowDefinition flowDefinition = new FlowDefinition();
        flowDefinition.setVersion("2.0");
        flowDefinition.setEntry(List.of("approval_batch"));
        flowDefinition.setTerminal(List.of("approval_batch"));
        RuntimeArtifact artifact = new RuntimeArtifact(
                1L,
                "office1.approval_cleanup",
                RuntimeArtifact.ArtifactType.WORKFLOW,
                "approval cleanup",
                1,
                null,
                null,
                null,
                null,
                null,
                flowDefinition,
                Map.of(),
                Map.of());
        return PublishedToolDescriptor.forArtifact(
                "tool-meta-catalog",
                "workflow:office1.approval_cleanup",
                "approval cleanup",
                null,
                null,
                false,
                "office1",
                artifact);
    }

    private FlowContext flowContext() {
        FlowContext context = new FlowContext(Map.of());
        context.setAssistantUid("1001");
        context.setThreadId("thread-1");
        return context;
    }
}
