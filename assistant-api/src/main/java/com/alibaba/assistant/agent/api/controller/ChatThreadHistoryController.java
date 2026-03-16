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

import com.alibaba.assistant.agent.api.controller.dto.ChatMessageListResponse;
import com.alibaba.assistant.agent.api.controller.dto.ChatThreadListResponse;
import com.alibaba.assistant.agent.api.security.AuthenticatedUserContext;
import com.alibaba.assistant.agent.api.service.ChatConversationHistoryService;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.security.Principal;

/**
 * 聊天历史控制器。
 *
 * <p>对前端暴露线程列表和消息历史接口，
 * 供会话列表页与聊天详情页恢复历史数据使用。</p>
 */
@RestController
@Profile("migration")
@RequestMapping("/api/chat")
public class ChatThreadHistoryController {

    private final ChatConversationHistoryService chatConversationHistoryService;

    public ChatThreadHistoryController(ChatConversationHistoryService chatConversationHistoryService) {
        this.chatConversationHistoryService = chatConversationHistoryService;
    }

    @GetMapping("/threads")
    public ResponseEntity<ChatThreadListResponse> listThreads(
            @RequestParam(required = false) Integer limit,
            Principal principal) {
        AuthenticatedUserContext authenticatedUser = requireAuthenticatedUser(principal);
        return ResponseEntity.ok(ChatThreadListResponse.ok(
                chatConversationHistoryService.listThreads(authenticatedUser.userId(), limit)));
    }

    @GetMapping("/threads/{threadId}/messages")
    public ResponseEntity<ChatMessageListResponse> listMessages(
            @PathVariable String threadId,
            @RequestParam(required = false) Integer limit,
            Principal principal) {
        AuthenticatedUserContext authenticatedUser = requireAuthenticatedUser(principal);
        return ResponseEntity.ok(ChatMessageListResponse.ok(
                chatConversationHistoryService.listMessages(authenticatedUser.userId(), threadId, limit)));
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
