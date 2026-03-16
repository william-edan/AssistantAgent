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
import com.alibaba.assistant.agent.execution.persistence.AgentTask;
import com.alibaba.assistant.agent.execution.persistence.AgentTaskEvent;
import com.alibaba.assistant.agent.execution.persistence.AgentTaskEventService;
import com.alibaba.assistant.agent.execution.persistence.AgentTaskService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ChatTaskServiceTest {

    @Test
    void shouldPreserveBackgroundAndDetachedFlagsFromPersistedTask() {
        AgentTaskService agentTaskService = mock(AgentTaskService.class);
        AgentTaskEventService agentTaskEventService = mock(AgentTaskEventService.class);
        ChatTaskService chatTaskService = new ChatTaskService(
                agentTaskService,
                agentTaskEventService,
                new ObjectMapper());

        AgentTask task = new AgentTask();
        task.setTaskId("TASK-1");
        task.setThreadId("thread-1");
        task.setAssistantUid("1001");
        task.setTaskType("SUB_AGENT_CALL");
        task.setTitle("销售分析长任务");
        task.setStatus("COMPLETED");
        task.setSourceType("SUB_AGENT");
        task.setSourceCode("mcp:data-agent");
        task.setProgressPercent(100);
        task.setCollapsible(Boolean.TRUE);
        task.setBackground(Boolean.TRUE);
        task.setDetached(Boolean.TRUE);
        task.setResultReady(Boolean.TRUE);
        task.setResultPreviewJson("{\"reportId\":\"R-1\",\"summary\":\"分析完成\"}");
        task.setActionJson("{\"type\":\"TASK_DETAIL\",\"targetId\":\"TASK-1\"}");
        task.setCreatedAt(LocalDateTime.of(2026, 3, 15, 10, 0));
        task.setCompletedAt(LocalDateTime.of(2026, 3, 15, 10, 5));

        when(agentTaskService.findLatestByTaskId("TASK-1")).thenReturn(Optional.of(task));
        when(agentTaskEventService.listByTaskId("TASK-1", 20)).thenReturn(List.of());

        ChatTaskData data = chatTaskService.getTask("1001", "TASK-1");

        assertTrue(data.background());
        assertTrue(data.detached());
        assertEquals(Boolean.TRUE, data.display().get("background"));
        assertEquals(Boolean.TRUE, data.display().get("detached"));
    }

    @Test
    void shouldPreferTerminalResultPreviewSummaryOverLatestTaskStateEvent() {
        AgentTaskService agentTaskService = mock(AgentTaskService.class);
        AgentTaskEventService agentTaskEventService = mock(AgentTaskEventService.class);
        ChatTaskService chatTaskService = new ChatTaskService(
                agentTaskService,
                agentTaskEventService,
                new ObjectMapper());

        AgentTask task = new AgentTask();
        task.setTaskId("TASK-2");
        task.setThreadId("thread-2");
        task.setAssistantUid("1001");
        task.setTaskType("SUB_AGENT_CALL");
        task.setTitle("销售分析长任务");
        task.setStatus("COMPLETED");
        task.setSourceType("SUB_AGENT");
        task.setSourceCode("mcp:data-agent");
        task.setProgressPercent(100);
        task.setCollapsible(Boolean.TRUE);
        task.setBackground(Boolean.TRUE);
        task.setDetached(Boolean.TRUE);
        task.setResultReady(Boolean.TRUE);
        task.setResultPreviewJson("{\"reportId\":\"R-2\",\"summary\":\"销售额同比增长 12%\"}");
        task.setActionJson("{\"type\":\"TASK_DETAIL\",\"targetId\":\"TASK-2\"}");
        task.setCreatedAt(LocalDateTime.of(2026, 3, 15, 11, 0));
        task.setCompletedAt(LocalDateTime.of(2026, 3, 15, 11, 5));

        AgentTaskEvent completedEvent = new AgentTaskEvent();
        completedEvent.setEventId("evt-completed");
        completedEvent.setTaskId("TASK-2");
        completedEvent.setEventType("TASK_STATE");
        completedEvent.setStatus("COMPLETED");
        completedEvent.setSequenceNo(2L);
        completedEvent.setCreatedAt(LocalDateTime.of(2026, 3, 15, 11, 5));
        completedEvent.setPayloadJson("{\"eventType\":\"TASK_STATE\",\"status\":\"COMPLETED\",\"text\":\"TASK_STATE\"}");

        when(agentTaskService.findLatestByTaskId("TASK-2")).thenReturn(Optional.of(task));
        when(agentTaskEventService.listByTaskId("TASK-2", 20)).thenReturn(List.of(completedEvent));

        ChatTaskData data = chatTaskService.getTask("1001", "TASK-2");

        assertEquals("销售额同比增长 12%", data.summaryText());
    }
}
