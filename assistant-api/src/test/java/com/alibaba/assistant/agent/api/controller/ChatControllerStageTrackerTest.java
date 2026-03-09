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

import com.alibaba.assistant.agent.api.protocol.V3ProtocolAdapter.AssistantEvent;
import com.alibaba.cloud.ai.agent.studio.dto.messages.AssistantMessageDTO;
import com.alibaba.cloud.ai.agent.studio.dto.messages.ToolRequestConfirmMessageDTO;
import com.alibaba.cloud.ai.agent.studio.dto.messages.ToolRequestMessageDTO;
import com.alibaba.cloud.ai.agent.studio.dto.messages.ToolResponseMessageDTO;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class ChatControllerStageTrackerTest {

    @Test
    void shouldEmitInitialUnderstandingStageOnlyOnce() {
        ChatController.StageTracker tracker = new ChatController.StageTracker();

        AssistantEvent first = tracker.emitInitial("UNDERSTANDING", "run_sse");
        AssistantEvent second = tracker.emitInitial("UNDERSTANDING", "run_sse");

        assertNotNull(first);
        assertEquals("UNDERSTANDING", first.getStage());
        assertEquals("run_sse", first.getPayload().get("source"));
        assertNull(second);
    }

    @Test
    void shouldResolveCollectingStageFromSlotCollectToolRequest() {
        ChatController.StageTracker tracker = new ChatController.StageTracker();
        ToolRequestMessageDTO message = new ToolRequestMessageDTO();
        ToolRequestMessageDTO.ToolCallDTO toolCall = new ToolRequestMessageDTO.ToolCallDTO();
        toolCall.setName("slot_collect");
        message.setToolCalls(List.of(toolCall));

        AssistantEvent event = tracker.emitForMessage(message, "_AGENT_TOOL_");

        assertNotNull(event);
        assertEquals("COLLECTING", event.getStage());
        assertEquals("tool-request", event.getPayload().get("messageType"));
        assertEquals("slot_collect", event.getPayload().get("toolName"));
    }

    @Test
    void shouldResolveConfirmingStageFromToolConfirmMessage() {
        ChatController.StageTracker tracker = new ChatController.StageTracker();

        AssistantEvent event = tracker.emitForMessage(new ToolRequestConfirmMessageDTO(), "_AGENT_INTERRUPT_");

        assertNotNull(event);
        assertEquals("CONFIRMING", event.getStage());
        assertEquals("tool-confirm", event.getPayload().get("messageType"));
    }

    @Test
    void shouldResolveExecutingStageFromBusinessToolResponse() {
        ChatController.StageTracker tracker = new ChatController.StageTracker();
        ToolResponseMessageDTO message = new ToolResponseMessageDTO();
        ToolResponseMessageDTO.ToolResponseDTO response = new ToolResponseMessageDTO.ToolResponseDTO();
        response.setName("leave_application_execute");
        message.setResponses(List.of(response));

        AssistantEvent event = tracker.emitForMessage(message, "_AGENT_TOOL_");

        assertNotNull(event);
        assertEquals("EXECUTING", event.getStage());
        assertEquals("tool", event.getPayload().get("messageType"));
        assertEquals("leave_application_execute", event.getPayload().get("toolName"));
    }

    @Test
    void shouldSuppressDuplicateCollectingStage() {
        ChatController.StageTracker tracker = new ChatController.StageTracker();
        ToolRequestMessageDTO message = new ToolRequestMessageDTO();
        ToolRequestMessageDTO.ToolCallDTO toolCall = new ToolRequestMessageDTO.ToolCallDTO();
        toolCall.setName("slot_collect");
        message.setToolCalls(List.of(toolCall));

        assertNotNull(tracker.emitForMessage(message, "_AGENT_TOOL_"));
        assertNull(tracker.emitForMessage(message, "_AGENT_TOOL_"));
    }
    @Test
    void shouldResolveDoneStageFromTerminalAssistantReply() {
        ChatController.StageTracker tracker = new ChatController.StageTracker();
        tracker.emitInitial("EXECUTING", "resume_sse");

        AssistantEvent event = tracker.emitForMessage(new AssistantMessageDTO("请假申请已提交"), "_AGENT_MODEL_");

        assertNotNull(event);
        assertEquals("DONE", event.getStage());
        assertEquals("assistant", event.getPayload().get("messageType"));
    }
}