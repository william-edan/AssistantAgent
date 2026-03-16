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
import com.alibaba.assistant.agent.api.security.AuthenticatedUserContext;
import com.alibaba.assistant.agent.api.service.ChatThreadStateService;
import com.alibaba.assistant.agent.api.stream.FrontendEventStreamRegistry;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.security.core.Authentication;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;

import java.security.Principal;

/**
 * 线程级前端事件实时订阅接口，供长任务在聊天会话中持续刷新。
 */
@RestController
@Profile("migration")
@RequestMapping("/api/chat/threads")
public class ChatThreadEventStreamController {

    private final FrontendEventStreamRegistry frontendEventStreamRegistry;

    private final ChatThreadStateService chatThreadStateService;

    public ChatThreadEventStreamController(
            FrontendEventStreamRegistry frontendEventStreamRegistry,
            ChatThreadStateService chatThreadStateService) {
        this.frontendEventStreamRegistry = frontendEventStreamRegistry;
        this.chatThreadStateService = chatThreadStateService;
    }

    /**
     * 订阅线程级 SSE 事件流。
     */
    @GetMapping(path = "/{threadId}/events_sse", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> streamThreadEvents(
            @PathVariable String threadId,
            Principal principal) {
        AuthenticatedUserContext authenticatedUser = requireAuthenticatedUser(principal);
        chatThreadStateService.getThreadState(threadId, authenticatedUser.userId());
        FrontendEventStreamRegistry.FrontendEventSubscription subscription = frontendEventStreamRegistry.open(threadId);
        return subscription.flux()
                .map(this::toSse)
                .doFinally(signalType -> subscription.close());
    }

    private ServerSentEvent<String> toSse(FrontendEvent event) {
        try {
            String data = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(event);
            return ServerSentEvent.<String>builder(data).build();
        }
        catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "frontend_event_serialize_failed", e);
        }
    }

    private AuthenticatedUserContext requireAuthenticatedUser(Principal principal) {
        if (principal instanceof Authentication authentication
                && authentication.getPrincipal() instanceof AuthenticatedUserContext authenticatedUser
                && StringUtils.hasText(authenticatedUser.userId())) {
            return authenticatedUser;
        }
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "unauthenticated_user");
    }
}
