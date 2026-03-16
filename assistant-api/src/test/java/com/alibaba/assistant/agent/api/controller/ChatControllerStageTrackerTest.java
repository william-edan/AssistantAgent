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
import com.alibaba.cloud.ai.agent.studio.dto.messages.AssistantMessageDTO;
import com.alibaba.cloud.ai.agent.studio.dto.messages.ToolRequestConfirmMessageDTO;
import com.alibaba.cloud.ai.agent.studio.dto.messages.ToolRequestMessageDTO;
import com.alibaba.cloud.ai.agent.studio.dto.messages.ToolResponseMessageDTO;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class ChatControllerStageTrackerTest {

    @Test
    void shouldEmitInitialUnderstandingStageOnlyOnce() {
        ChatController.StageTracker tracker = new ChatController.StageTracker();

        FrontendEvent first = tracker.emitInitial("UNDERSTANDING", "run_sse");
        FrontendEvent second = tracker.emitInitial("UNDERSTANDING", "run_sse");

        assertNotNull(first);
        assertEquals(FrontendEventType.STAGE, first.eventType());
        assertEquals(FrontendStage.UNDERSTANDING, first.stage());
        assertEquals("run_sse", first.payload().get("source"));
        assertNull(second);
    }

    @Test
    void shouldResolveCollectingStageFromSlotCollectToolRequest() {
        ChatController.StageTracker tracker = new ChatController.StageTracker();
        ToolRequestMessageDTO message = new ToolRequestMessageDTO();
        ToolRequestMessageDTO.ToolCallDTO toolCall = new ToolRequestMessageDTO.ToolCallDTO();
        toolCall.setName("slot_collect");
        message.setToolCalls(List.of(toolCall));

        FrontendEvent event = tracker.emitForMessage(message, "_AGENT_TOOL_", "T-1");

        assertNotNull(event);
        assertEquals(FrontendStage.COLLECTING, event.stage());
        assertEquals("tool-request", event.payload().get("messageType"));
        assertEquals("slot_collect", event.payload().get("toolName"));
        assertEquals("T-1", event.threadId());
    }

    @Test
    void shouldResolveConfirmingStageFromToolConfirmMessage() {
        ChatController.StageTracker tracker = new ChatController.StageTracker();

        FrontendEvent event = tracker.emitForMessage(new ToolRequestConfirmMessageDTO(), "_AGENT_INTERRUPT_", "T-1");

        assertNotNull(event);
        assertEquals(FrontendStage.CONFIRMING, event.stage());
        assertEquals("tool-confirm", event.payload().get("messageType"));
    }

    @Test
    void shouldResolveExecutingStageFromBusinessToolResponse() {
        ChatController.StageTracker tracker = new ChatController.StageTracker();
        ToolResponseMessageDTO message = new ToolResponseMessageDTO();
        ToolResponseMessageDTO.ToolResponseDTO response = new ToolResponseMessageDTO.ToolResponseDTO();
        response.setName("leave_application_execute");
        message.setResponses(List.of(response));

        FrontendEvent event = tracker.emitForMessage(message, "_AGENT_TOOL_", "T-1");

        assertNotNull(event);
        assertEquals(FrontendStage.EXECUTING, event.stage());
        assertEquals("tool", event.payload().get("messageType"));
        assertEquals("leave_application_execute", event.payload().get("toolName"));
    }

    @Test
    void shouldSuppressDuplicateCollectingStage() {
        ChatController.StageTracker tracker = new ChatController.StageTracker();
        ToolRequestMessageDTO message = new ToolRequestMessageDTO();
        ToolRequestMessageDTO.ToolCallDTO toolCall = new ToolRequestMessageDTO.ToolCallDTO();
        toolCall.setName("slot_collect");
        message.setToolCalls(List.of(toolCall));

        assertNotNull(tracker.emitForMessage(message, "_AGENT_TOOL_", "T-1"));
        assertNull(tracker.emitForMessage(message, "_AGENT_TOOL_", "T-1"));
    }

    @Test
    void shouldResolveDoneStageFromTerminalAssistantReply() {
        ChatController.StageTracker tracker = new ChatController.StageTracker();
        tracker.emitInitial("EXECUTING", "resume_sse");

        FrontendEvent event = tracker.emitForMessage(new AssistantMessageDTO("请假申请已提交"), "_AGENT_MODEL_", "T-1");

        assertNotNull(event);
        assertEquals(FrontendStage.DONE, event.stage());
        assertEquals("assistant", event.payload().get("messageType"));
    }

    @Test
    void shouldResolveWaitingApprovalStageFromInitialState() {
        ChatController.StageTracker tracker = new ChatController.StageTracker();

        FrontendEvent event = tracker.emitInitial("WAITING_APPROVAL", "resume_sse");

        assertNotNull(event);
        assertEquals(FrontendStage.WAITING_APPROVAL, event.stage());
        assertEquals("resume_sse", event.payload().get("source"));
    }

    @Test
    void shouldNotLeakInternalNodeNameInStagePayload() {
        ChatController.StageTracker tracker = new ChatController.StageTracker();
        ToolRequestMessageDTO message = new ToolRequestMessageDTO();
        ToolRequestMessageDTO.ToolCallDTO toolCall = new ToolRequestMessageDTO.ToolCallDTO();
        toolCall.setName("slot_collect");
        message.setToolCalls(List.of(toolCall));

        FrontendEvent event = tracker.emitForMessage(message, "_AGENT_HOOK_Internal", "T-1");

        assertNotNull(event);
        assertFalse(event.payload().containsKey("node"));
    }
}



