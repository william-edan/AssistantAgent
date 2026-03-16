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

import com.alibaba.assistant.agent.api.controller.dto.ChatThreadStateData;
import com.alibaba.assistant.agent.api.protocol.FrontendEvent;
import com.alibaba.assistant.agent.api.protocol.FrontendEventType;
import com.alibaba.assistant.agent.api.service.ChatThreadStateService;
import com.alibaba.cloud.ai.agent.studio.loader.AgentLoader;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ChatControllerResumeInitialStageTest {

    private final AgentLoader agentLoader = mock(AgentLoader.class);

    private final ChatThreadStateService chatThreadStateService = mock(ChatThreadStateService.class);

    private final ChatController controller = new ChatController(
            agentLoader,
            "grayscale_agent",
            "",
            "",
            "prod",
            null,
            null,
            null,
            chatThreadStateService,
            null,
            null);

    @Test
    void shouldUsePersistedConfirmingPhaseWhenResumingWaitingConfirmationThread() {
        when(chatThreadStateService.getThreadState("thread-confirm", "1001"))
                .thenReturn(threadState("thread-confirm", "WAITING_CONFIRMATION", "CONFIRMING"));

        assertEquals("CONFIRMING", controller.resolveResumeInitialStage("thread-confirm", "1001"));
    }

    @Test
    void shouldMapWaitingConfirmationStatusToConfirmingWhenPhaseMissing() {
        when(chatThreadStateService.getThreadState("thread-status-only", "1001"))
                .thenReturn(threadState("thread-status-only", "WAITING_CONFIRMATION", null));

        assertEquals("CONFIRMING", controller.resolveResumeInitialStage("thread-status-only", "1001"));
    }

    @Test
    void shouldFallbackToExecutingWhenThreadStateUnavailable() {
        when(chatThreadStateService.getThreadState("thread-missing", "1001"))
                .thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "chat_thread_not_found"));

        assertEquals("EXECUTING", controller.resolveResumeInitialStage("thread-missing", "1001"));
    }

    @Test
    void shouldReplayPendingFormWhenResumingConfirmationThreadWithoutFeedback() {
        when(chatThreadStateService.getThreadState("thread-replay", "1001"))
                .thenReturn(new ChatThreadStateData(
                        "thread-replay",
                        null,
                        "WAITING_CONFIRMATION",
                        "CONFIRMING",
                        true,
                        true,
                        "gougu_oa.leave_application",
                        "FORM_CARD",
                        0,
                        0,
                        null,
                        null,
                        Map.of(
                                "mode", "CONFIRM",
                                "status", "WAITING_CONFIRMATION",
                                "phase", "CONFIRMING",
                                "toolCode", "gougu_oa.leave_application",
                                "values", Map.of("types", 2),
                                "fields", List.of(),
                                "missingFields", List.of(),
                                "summary", Map.of(),
                                "canSubmit", true),
                        Map.of(),
                        List.of(),
                        List.of(),
                        Map.of()));

        List<FrontendEvent> replayEvents = controller.resolveResumeReplayEvents("thread-replay", "1001", false);

        assertEquals(2, replayEvents.size());
        assertEquals(FrontendEventType.STAGE, replayEvents.get(0).eventType());
        assertEquals(FrontendEventType.FORM_STATE, replayEvents.get(1).eventType());
        assertEquals("WAITING_CONFIRMATION", replayEvents.get(1).payload().get("status"));
    }

    @Test
    void shouldSkipReplayWhenResumeContainsHumanFeedback() {
        List<FrontendEvent> replayEvents = controller.resolveResumeReplayEvents("thread-replay", "1001", true);

        assertEquals(List.of(), replayEvents);
    }

    private static ChatThreadStateData threadState(String threadId, String status, String phase) {
        return new ChatThreadStateData(
                threadId,
                null,
                status,
                phase,
                true,
                true,
                "gougu_oa.leave_application",
                "FORM_CARD",
                0,
                0,
                null,
                null,
                Map.of(),
                Map.of(),
                List.of(),
                List.of(),
                Map.of());
    }
}
