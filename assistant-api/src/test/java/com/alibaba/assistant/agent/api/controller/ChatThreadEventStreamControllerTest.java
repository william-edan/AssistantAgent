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
import com.alibaba.assistant.agent.api.protocol.FrontendStage;
import com.alibaba.assistant.agent.api.service.ChatThreadStateService;
import com.alibaba.assistant.agent.api.stream.FrontendEventStreamRegistry;
import com.alibaba.assistant.agent.api.security.AuthenticatedUserContext;
import org.junit.jupiter.api.Test;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

import java.security.Principal;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ChatThreadEventStreamControllerTest {

    @Test
    void shouldStreamBufferedFrontendEventsForOwnedThread() throws Exception {
        FrontendEventStreamRegistry streamRegistry = new FrontendEventStreamRegistry();
        ChatThreadStateService chatThreadStateService = mock(ChatThreadStateService.class);
        when(chatThreadStateService.getThreadState("thread-live-1", "1001")).thenReturn(new ChatThreadStateData(
                "thread-live-1",
                null,
                "RUNNING",
                "EXECUTING",
                true,
                false,
                "mcp:data-agent",
                null,
                null,
                null,
                "TASK_CARD",
                1,
                0,
                null,
                null,
                Map.of(),
                Map.of(),
                List.of(),
                List.of(),
                Map.of()));
        ChatThreadEventStreamController controller = new ChatThreadEventStreamController(streamRegistry, chatThreadStateService);

        var future = controller.streamThreadEvents("thread-live-1", authenticatedPrincipal("1001"))
                .take(1)
                .map(ServerSentEvent::data)
                .collectList()
                .toFuture();

        streamRegistry.publish("thread-live-1", new FrontendEvent(
                "2026-03-13",
                "evt-live-1",
                "thread-live-1",
                "2026-03-15T11:00:00Z",
                FrontendEventType.TASK_STATE,
                FrontendStage.EXECUTING,
                Map.of(
                        "taskId", "TASK-LIVE-1",
                        "status", "RUNNING",
                        "summaryText", "已完成 2/5 批")));

        List<String> payloads = future.get(2, TimeUnit.SECONDS);
        assertTrue(payloads.get(0).contains("\"eventType\":\"TASK_STATE\""));
        assertTrue(payloads.get(0).contains("\"taskId\":\"TASK-LIVE-1\""));
    }

    private Principal authenticatedPrincipal(String userId) {
        return new UsernamePasswordAuthenticationToken(authenticatedUser(userId), "token-chat", List.of());
    }

    private AuthenticatedUserContext authenticatedUser(String userId) {
        return new AuthenticatedUserContext(
                userId,
                1L,
                "gougu_oa",
                "assistant-ui",
                "token-chat",
                "admin",
                "管理员",
                List.of("assistant_user"),
                List.of("assistant:chat"));
    }
}


