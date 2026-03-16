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
package com.alibaba.assistant.agent.api.controller;

import com.alibaba.assistant.agent.api.protocol.FrontendEvent;
import com.alibaba.assistant.agent.api.protocol.FrontendEventType;
import com.alibaba.assistant.agent.api.protocol.FrontendStage;
import com.alibaba.assistant.agent.runtime.execution.ExecutionEvent;
import com.alibaba.assistant.agent.runtime.execution.ExecutionEventStreamRegistry;
import com.alibaba.assistant.agent.runtime.execution.ExecutionEventType;
import com.alibaba.assistant.agent.runtime.execution.ExecutionLifecycleStatus;
import com.alibaba.cloud.ai.agent.studio.dto.messages.ToolResponseMessageDTO;
import com.alibaba.cloud.ai.agent.studio.loader.AgentLoader;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class ChatControllerArtifactExecutionEventsTest {

    private final ExecutionEventStreamRegistry executionEventStreamRegistry = new ExecutionEventStreamRegistry();

    private final ChatController controller = new ChatController(
            mock(AgentLoader.class),
            "grayscale_agent",
            "",
            executionEventStreamRegistry);

    @Test
    void shouldExtractExecutionProgressAndTaskEventsFromArtifactToolResponseData() {
        ToolResponseMessageDTO message = new ToolResponseMessageDTO();
        ToolResponseMessageDTO.ToolResponseDTO response = new ToolResponseMessageDTO.ToolResponseDTO();
        response.setName("artifact_execute");
        response.setResponseData("""
                {"success":true,"executionEvents":[
                  {"runId":"RUN-1","artifactCode":"oa.leave.apply","artifactType":"WORKFLOW","stepId":null,
                   "sequence":1,"eventType":"RUN_STARTED","lifecycleStatus":"RUNNING","occurredAt":"2026-03-10T10:00:00Z","payload":{"source":"artifact-runtime"}},
                  {"runId":"RUN-1","artifactCode":"oa.leave.apply","artifactType":"WORKFLOW","stepId":"submit_approval",
                   "sequence":2,"eventType":"STEP_COMPLETED","lifecycleStatus":"COMPLETED","occurredAt":"2026-03-10T10:00:01Z","payload":{"stepName":"提交审批"}}
                ]}
                """);
        message.setResponses(List.of(response));

        List<FrontendEvent> events = controller.extractArtifactExecutionEvents("T-1", message);

        assertEquals(4, events.size());
        assertEquals(2, events.stream().filter(event -> event.eventType() == FrontendEventType.EXECUTION_PROGRESS).count());
        assertEquals(2, events.stream().filter(event -> event.eventType() == FrontendEventType.TASK_STATE).count());
        FrontendEvent taskEvent = events.stream()
                .filter(event -> event.eventType() == FrontendEventType.TASK_STATE)
                .findFirst()
                .orElseThrow();
        assertEquals("RUN-1", taskEvent.payload().get("taskId"));
        assertEquals("oa.leave.apply", taskEvent.payload().get("sourceCode"));
        assertEquals(Boolean.TRUE, taskEvent.payload().get("collapsible"));
        assertTrue(taskEvent.payload().containsKey("liveOutput"));
    }

    @Test
    void shouldIgnoreInternalExecutionEventsEmbeddedInArtifactResponse() {
        ToolResponseMessageDTO message = new ToolResponseMessageDTO();
        ToolResponseMessageDTO.ToolResponseDTO response = new ToolResponseMessageDTO.ToolResponseDTO();
        response.setName("artifact_execute");
        response.setResponseData("""
                {"success":true,"executionEvents":[
                  {"runId":"RUN-INTERNAL-1","artifactCode":"oa.user.query","artifactType":"WORKFLOW","stepId":"query_user",
                   "sequence":1,"eventType":"STEP_COMPLETED","lifecycleStatus":"COMPLETED","occurredAt":"2026-03-10T10:00:00Z",
                   "payload":{"stepName":"查询用户","internal":true,"toolType":"QUERY","visibility":"INTERNAL","invocationPolicy":"DEPENDENCY_ONLY"}}
                ]}
                """);
        message.setResponses(List.of(response));

        List<FrontendEvent> events = controller.extractArtifactExecutionEvents("T-internal", message);

                assertTrue(events.isEmpty());
    }

    @Test
    void shouldFilterInternalPlanningNarrationFromToolResponseEvents() {
        ToolResponseMessageDTO message = new ToolResponseMessageDTO();

        ToolResponseMessageDTO.ToolResponseDTO replyResponse = new ToolResponseMessageDTO.ToolResponseDTO();
        replyResponse.setName("reply");
        replyResponse.setResponseData("""
                {"message":"用户明确表示“我要写汇报”，根据执行策略需先调用 slot_collect 收集必要参数。"}
                """);

        ToolResponseMessageDTO.ToolResponseDTO formResponse = new ToolResponseMessageDTO.ToolResponseDTO();
        formResponse.setName("slot_collect");
        formResponse.setResponseData("""
                {
                  "status":"COLLECTING",
                  "phase":"COLLECTING",
                  "message":"Missing required slots, continue collecting.",
                  "round":1,
                  "collected":{"to_uids":"1"},
                  "missing":[{"name":"types","title":"汇报类型"}],
                  "enrichedSlots":[]
                }
                """);

        message.setResponses(List.of(replyResponse, formResponse));

        List<FrontendEvent> events = controller.adaptToolResponseEvents("T-WORK-1", message);

        assertEquals(1, events.size());
        assertEquals(FrontendEventType.FORM_STATE, events.get(0).eventType());
        assertFalse(events.stream().anyMatch(event -> event.eventType() == FrontendEventType.MESSAGE));
    }

    @Test
    void shouldStreamLiveExecutionProgressAndTaskEventsFromRegistry() throws Exception {
        ChatController.ExecutionEventDeduplicator deduplicator = new ChatController.ExecutionEventDeduplicator();
        ExecutionEvent event = new ExecutionEvent(
                "RUN-LIVE-1",
                "oa.leave.apply",
                "WORKFLOW",
                "create_leave",
                1L,
                ExecutionEventType.STEP_STARTED,
                ExecutionLifecycleStatus.RUNNING,
                Instant.parse("2026-03-10T10:00:00Z"),
                Map.of("stepName", "创建请假记录"));

        var future = controller.liveExecutionProgressFlux("T-1", deduplicator)
                .take(2)
                .collectList()
                .toFuture();
        executionEventStreamRegistry.publish("T-1", event);
        List<FrontendEvent> events = future.get(2, TimeUnit.SECONDS);

        assertEquals(2, events.size());
        assertEquals(1, events.stream().filter(it -> it.eventType() == FrontendEventType.EXECUTION_PROGRESS).count());
        assertEquals(1, events.stream().filter(it -> it.eventType() == FrontendEventType.TASK_STATE).count());
        FrontendEvent taskEvent = events.stream()
                .filter(it -> it.eventType() == FrontendEventType.TASK_STATE)
                .findFirst()
                .orElseThrow();
        assertEquals("RUN-LIVE-1", taskEvent.payload().get("taskId"));
        assertEquals("STEP_STARTED", taskEvent.payload().get("eventType"));
    }

    @Test
    void shouldIgnoreInternalLiveExecutionEventsAndKeepStreamOpen() throws Exception {
        ChatController.ExecutionEventDeduplicator deduplicator = new ChatController.ExecutionEventDeduplicator();
        var future = controller.liveExecutionProgressFlux("T-internal-live", deduplicator)
                .take(2)
                .collectList()
                .toFuture();

        executionEventStreamRegistry.publish("T-internal-live", new ExecutionEvent(
                "RUN-INTERNAL-2",
                "oa.user.query",
                "WORKFLOW",
                "query_user",
                1L,
                ExecutionEventType.STEP_COMPLETED,
                ExecutionLifecycleStatus.COMPLETED,
                Instant.parse("2026-03-10T10:00:00Z"),
                Map.of("stepName", "查询用户", "internal", true)));
        executionEventStreamRegistry.publish("T-internal-live", new ExecutionEvent(
                "RUN-INTERNAL-2",
                "oa.leave.apply",
                "WORKFLOW",
                null,
                2L,
                ExecutionEventType.RUN_COMPLETED,
                ExecutionLifecycleStatus.COMPLETED,
                Instant.parse("2026-03-10T10:00:01Z"),
                Map.of("finalOutputs", Map.of("leaveId", "L-1"))));

        List<FrontendEvent> events = future.get(2, TimeUnit.SECONDS);

        assertEquals(2, events.size());
        assertEquals(1, events.stream().filter(it -> it.eventType() == FrontendEventType.EXECUTION_PROGRESS).count());
        assertEquals(1, events.stream().filter(it -> it.eventType() == FrontendEventType.TASK_STATE).count());
        assertEquals("RUN_COMPLETED", events.get(0).payload().get("eventType"));
    }

    @Test
    void shouldCompleteLiveExecutionFluxWhenTerminalExecutionEventArrives() throws Exception {
        ChatController.ExecutionEventDeduplicator deduplicator = new ChatController.ExecutionEventDeduplicator();
        var future = controller.liveExecutionProgressFlux("T-2", deduplicator)
                .collectList()
                .toFuture();

        executionEventStreamRegistry.publish("T-2", new ExecutionEvent(
                "RUN-LIVE-2",
                "oa.leave.apply",
                "WORKFLOW",
                null,
                1L,
                ExecutionEventType.RUN_STARTED,
                ExecutionLifecycleStatus.RUNNING,
                Instant.parse("2026-03-10T10:00:00Z"),
                Map.of("source", "artifact-runtime")));
        executionEventStreamRegistry.publish("T-2", new ExecutionEvent(
                "RUN-LIVE-2",
                "oa.leave.apply",
                "WORKFLOW",
                null,
                2L,
                ExecutionEventType.RUN_COMPLETED,
                ExecutionLifecycleStatus.COMPLETED,
                Instant.parse("2026-03-10T10:00:01Z"),
                Map.of("finalOutputs", Map.of("leaveId", "L-1"))));

        List<FrontendEvent> events = future.get(2, TimeUnit.SECONDS);

        assertEquals(4, events.size());
        assertEquals(2, events.stream().filter(it -> it.eventType() == FrontendEventType.EXECUTION_PROGRESS).count());
        assertEquals(2, events.stream().filter(it -> it.eventType() == FrontendEventType.TASK_STATE).count());
        assertEquals("RUN_COMPLETED", events.get(events.size() - 2).payload().get("eventType"));
    }

    @Test
    void shouldDeduplicateEmbeddedExecutionEventsWhenAlreadySeenLive() {
        ChatController.ExecutionEventDeduplicator deduplicator = new ChatController.ExecutionEventDeduplicator();
        deduplicator.shouldEmit(new ExecutionEvent(
                "RUN-1",
                "oa.leave.apply",
                "WORKFLOW",
                null,
                1L,
                ExecutionEventType.RUN_STARTED,
                ExecutionLifecycleStatus.RUNNING,
                Instant.parse("2026-03-10T10:00:00Z"),
                Map.of("source", "artifact-runtime")));
        ToolResponseMessageDTO message = new ToolResponseMessageDTO();
        ToolResponseMessageDTO.ToolResponseDTO response = new ToolResponseMessageDTO.ToolResponseDTO();
        response.setName("artifact_execute");
        response.setResponseData("""
                {"success":true,"executionEvents":[
                  {"runId":"RUN-1","artifactCode":"oa.leave.apply","artifactType":"WORKFLOW","stepId":null,
                   "sequence":1,"eventType":"RUN_STARTED","lifecycleStatus":"RUNNING","occurredAt":"2026-03-10T10:00:00Z","payload":{"source":"artifact-runtime"}},
                  {"runId":"RUN-1","artifactCode":"oa.leave.apply","artifactType":"WORKFLOW","stepId":"submit_approval",
                   "sequence":2,"eventType":"STEP_COMPLETED","lifecycleStatus":"COMPLETED","occurredAt":"2026-03-10T10:00:01Z","payload":{"stepName":"提交审批"}}
                ]}
                """);
        message.setResponses(List.of(response));

        List<FrontendEvent> events = controller.extractArtifactExecutionEvents("T-1", message, deduplicator);

        assertEquals(2, events.size());
        assertEquals(1, events.stream().filter(it -> it.eventType() == FrontendEventType.EXECUTION_PROGRESS).count());
        assertEquals(1, events.stream().filter(it -> it.eventType() == FrontendEventType.TASK_STATE).count());
        assertEquals("STEP_COMPLETED", events.stream()
                .filter(it -> it.eventType() == FrontendEventType.TASK_STATE)
                .findFirst()
                .orElseThrow()
                .payload()
                .get("eventType"));
    }

    @Test
    void shouldMapWaitingApprovalExecutionEventToWaitingApprovalTaskStage() {
        ExecutionEvent waitingApprovalEvent = new ExecutionEvent(
                "RUN-WAIT-2",
                "oa.expense.submit",
                "WORKFLOW",
                "submit_approval",
                3L,
                ExecutionEventType.STEP_WAITING_APPROVAL,
                ExecutionLifecycleStatus.WAITING_APPROVAL,
                Instant.parse("2026-03-10T10:00:03Z"),
                Map.of(
                        "stepName", "提交审批",
                        "approvalRequestId", "RUN-WAIT-2:submit_approval"));

        List<FrontendEvent> events = controller.toExecutionFrontendEvents("T-3", waitingApprovalEvent);

        assertEquals(2, events.size());
        FrontendEvent taskEvent = events.stream()
                .filter(it -> it.eventType() == FrontendEventType.TASK_STATE)
                .findFirst()
                .orElseThrow();
        assertEquals(FrontendStage.WAITING_APPROVAL, taskEvent.stage());
        assertEquals("WAITING_APPROVAL", taskEvent.payload().get("status"));
        assertEquals("STEP_WAITING_APPROVAL", taskEvent.payload().get("eventType"));
    }

    @Test
    void shouldDropInternalExecutionEventWhenConvertingFrontendEvents() {
        ExecutionEvent internalEvent = new ExecutionEvent(
                "RUN-INTERNAL-3",
                "oa.user.query",
                "WORKFLOW",
                "query_user",
                1L,
                ExecutionEventType.STEP_COMPLETED,
                ExecutionLifecycleStatus.COMPLETED,
                Instant.parse("2026-03-10T10:00:03Z"),
                Map.of("stepName", "查询用户", "internal", true));

        List<FrontendEvent> events = controller.toExecutionFrontendEvents("T-4", internalEvent);

        assertTrue(events.isEmpty());
    }

    @Test
    void shouldTreatFormStateAsConversationBoundary() {
        FrontendEvent event = new FrontendEvent(
                "2026-03-13",
                "evt-form",
                "T-9",
                "2026-03-13T10:00:00Z",
                FrontendEventType.FORM_STATE,
                FrontendStage.CONFIRMING,
                Map.of("status", "WAITING_CONFIRMATION"));

        assertTrue(controller.isConversationBoundaryEvent(event));
    }

    @Test
    void shouldTreatBackgroundTaskAsConversationBoundary() {
        FrontendEvent event = new FrontendEvent(
                "2026-03-13",
                "evt-task",
                "T-10",
                "2026-03-13T10:00:01Z",
                FrontendEventType.TASK_STATE,
                FrontendStage.EXECUTING,
                Map.of(
                        "taskId", "task-1",
                        "status", "RUNNING",
                        "background", true));

        assertTrue(controller.isConversationBoundaryEvent(event));
    }

    @Test
    void shouldKeepForegroundRunningTaskStreamOpen() {
        FrontendEvent event = new FrontendEvent(
                "2026-03-13",
                "evt-task-foreground",
                "T-11",
                "2026-03-13T10:00:02Z",
                FrontendEventType.TASK_STATE,
                FrontendStage.EXECUTING,
                Map.of(
                        "taskId", "task-2",
                        "status", "RUNNING"));

        assertFalse(controller.isConversationBoundaryEvent(event));
    }

    @Test
    void shouldKeepForegroundCompletedTaskStreamOpen() {
        FrontendEvent event = new FrontendEvent(
                "2026-03-13",
                "evt-task-completed",
                "T-12",
                "2026-03-13T10:00:03Z",
                FrontendEventType.TASK_STATE,
                FrontendStage.DONE,
                Map.of(
                        "taskId", "task-3",
                        "status", "COMPLETED"));

        assertFalse(controller.isConversationBoundaryEvent(event));
    }
}

