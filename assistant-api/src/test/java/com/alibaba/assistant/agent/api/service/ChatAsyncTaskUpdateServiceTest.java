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
import com.alibaba.assistant.agent.api.protocol.V3ProtocolAdapter;
import com.alibaba.assistant.agent.runtime.task.AgentTaskProjector;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.AbstractMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class ChatAsyncTaskUpdateServiceTest {

    @Test
    void shouldPublishRunningDetachedSubAgentTaskAsTaskStateOnly() {
        ChatFrontendEventPublisher eventPublisher = mock(ChatFrontendEventPublisher.class);
        AgentTaskProjector agentTaskProjector = mock(AgentTaskProjector.class);
        ChatAsyncTaskUpdateService service = new ChatAsyncTaskUpdateService(
                new ChatTaskFrontendEventService(new V3ProtocolAdapter(new ObjectMapper())),
                eventPublisher,
                agentTaskProjector);

        List<FrontendEvent> events = service.publishUpdate(new ChatAsyncTaskUpdateCommand(
                "thread-async-1",
                "1001",
                "assistant-ui",
                "gougu_oa",
                "turn-async-1",
                Map.ofEntries(
                        new AbstractMap.SimpleEntry<>("taskId", "TASK-ASYNC-1"),
                        new AbstractMap.SimpleEntry<>("taskType", "SUB_AGENT_CALL"),
                        new AbstractMap.SimpleEntry<>("sourceType", "SUB_AGENT"),
                        new AbstractMap.SimpleEntry<>("sourceCode", "mcp:data-agent"),
                        new AbstractMap.SimpleEntry<>("title", "数据分析任务"),
                        new AbstractMap.SimpleEntry<>("status", "IN_PROGRESS"),
                        new AbstractMap.SimpleEntry<>("progressPercent", 40),
                        new AbstractMap.SimpleEntry<>("background", true),
                        new AbstractMap.SimpleEntry<>("detached", true),
                        new AbstractMap.SimpleEntry<>("collapsible", true),
                        new AbstractMap.SimpleEntry<>("liveOutput", List.of(Map.of(
                                "eventType", "PROGRESS",
                                "text", "已完成 2/5 批",
                                "occurredAt", "2026-03-15T10:00:00Z"))))));

        assertEquals(1, events.size());
        FrontendEvent taskEvent = events.get(0);
        assertEquals(FrontendEventType.TASK_STATE, taskEvent.eventType());
        assertEquals(FrontendStage.EXECUTING, taskEvent.stage());
        assertEquals("TASK-ASYNC-1", taskEvent.payload().get("taskId"));
        assertEquals("RUNNING", taskEvent.payload().get("status"));
        assertEquals(Boolean.TRUE, taskEvent.payload().get("detached"));
        assertEquals(Boolean.TRUE, taskEvent.payload().get("background"));

        verify(agentTaskProjector).recordTaskState(eq("thread-async-1"), org.mockito.ArgumentMatchers.anyMap(), eq(taskEvent.payload()));
        verify(eventPublisher).publish("thread-async-1", "1001", "assistant-ui", "gougu_oa", "turn-async-1", taskEvent);
    }

    @Test
    void shouldPublishTerminalSubAgentTaskWithResultCard() {
        ChatFrontendEventPublisher eventPublisher = mock(ChatFrontendEventPublisher.class);
        AgentTaskProjector agentTaskProjector = mock(AgentTaskProjector.class);
        ChatAsyncTaskUpdateService service = new ChatAsyncTaskUpdateService(
                new ChatTaskFrontendEventService(new V3ProtocolAdapter(new ObjectMapper())),
                eventPublisher,
                agentTaskProjector);

        List<FrontendEvent> events = service.publishUpdate(new ChatAsyncTaskUpdateCommand(
                "thread-async-2",
                "1001",
                "assistant-ui",
                "gougu_oa",
                "turn-async-2",
                Map.of(
                        "taskId", "TASK-ASYNC-2",
                        "taskType", "SUB_AGENT_CALL",
                        "sourceType", "SUB_AGENT",
                        "sourceCode", "mcp:data-agent",
                        "title", "销售报表分析",
                        "status", "COMPLETED",
                        "resultReady", true,
                        "resultPreview", Map.of(
                                "reportId", "R-200",
                                "summary", "分析完成"),
                        "notification", Map.of(
                                "notificationId", "N-200",
                                "title", "销售报表已完成",
                                "body", "点击查看结果"))));

        assertEquals(2, events.size());
        assertEquals(FrontendEventType.TASK_STATE, events.get(0).eventType());
        assertEquals(FrontendEventType.RESULT, events.get(1).eventType());
        assertEquals(FrontendStage.DONE, events.get(0).stage());
        assertEquals(FrontendStage.DONE, events.get(1).stage());
        assertEquals(Boolean.TRUE, events.get(1).payload().get("success"));
        assertEquals("mcp:data-agent", events.get(1).payload().get("artifactCode"));
        assertEquals("R-200", ((Map<?, ?>) events.get(1).payload().get("result")).get("reportId"));

        verify(agentTaskProjector).recordTaskState(eq("thread-async-2"), org.mockito.ArgumentMatchers.anyMap(), eq(events.get(0).payload()));
        ArgumentCaptor<FrontendEvent> eventCaptor = ArgumentCaptor.forClass(FrontendEvent.class);
        verify(eventPublisher, times(2)).publish(
                eq("thread-async-2"),
                eq("1001"),
                eq("assistant-ui"),
                eq("gougu_oa"),
                eq("turn-async-2"),
                eventCaptor.capture());
        assertTrue(eventCaptor.getAllValues().stream().anyMatch(event -> event.eventType() == FrontendEventType.RESULT));
    }
}
