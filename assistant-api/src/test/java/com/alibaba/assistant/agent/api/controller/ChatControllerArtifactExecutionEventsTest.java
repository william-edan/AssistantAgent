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
import com.alibaba.cloud.ai.agent.studio.dto.messages.ToolResponseMessageDTO;
import com.alibaba.cloud.ai.agent.studio.loader.AgentLoader;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class ChatControllerArtifactExecutionEventsTest {

    private final ChatController controller = new ChatController(mock(AgentLoader.class), "grayscale_agent", "");

    @Test
    void shouldExtractExecutionProgressEventsFromArtifactToolResponseData() {
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

        List<AssistantEvent> events = controller.extractArtifactExecutionEvents(message);

        assertEquals(2, events.size());
        assertEquals("EXECUTION_PROGRESS", events.get(0).getType());
        assertEquals("RUN_STARTED", events.get(0).getPayload().get("eventType"));
        assertEquals("oa.leave.apply", events.get(0).getPayload().get("artifactCode"));
        assertEquals("STEP_COMPLETED", events.get(1).getPayload().get("eventType"));
        assertEquals("submit_approval", events.get(1).getPayload().get("stepId"));
        assertTrue(((java.util.Map<?, ?>) events.get(1).getPayload().get("payload")).containsKey("stepName"));
    }
}
