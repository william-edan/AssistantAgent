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

import com.alibaba.assistant.agent.api.controller.dto.ChatNotificationListResponse;
import com.alibaba.assistant.agent.api.controller.dto.ChatNotificationReadResponse;
import com.alibaba.assistant.agent.api.security.AuthenticatedUserContext;
import com.alibaba.assistant.agent.api.service.ChatNotificationService;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.security.Principal;

/**
 * 站内信控制器。
 *
 * <p>对前端暴露通知列表和标记已读接口，供消息中心或通知抽屉直接调用。</p>
 */
@RestController
@Profile("migration")
@RequestMapping("/api/chat/notifications")
public class ChatNotificationController {

    private final ChatNotificationService chatNotificationService;

    public ChatNotificationController(ChatNotificationService chatNotificationService) {
        this.chatNotificationService = chatNotificationService;
    }

    @GetMapping
    public ResponseEntity<ChatNotificationListResponse> listNotifications(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Integer limit,
            Principal principal) {
        AuthenticatedUserContext authenticatedUser = requireAuthenticatedUser(principal);
        return ResponseEntity.ok(ChatNotificationListResponse.ok(
                chatNotificationService.listNotifications(authenticatedUser.userId(), normalizeOptional(status), limit)));
    }

    @PostMapping("/{notificationId}/read")
    public ResponseEntity<ChatNotificationReadResponse> markRead(
            @PathVariable String notificationId,
            Principal principal) {
        AuthenticatedUserContext authenticatedUser = requireAuthenticatedUser(principal);
        return ResponseEntity.ok(ChatNotificationReadResponse.ok(
                chatNotificationService.markRead(authenticatedUser.userId(), notificationId)));
    }

    private AuthenticatedUserContext requireAuthenticatedUser(Principal principal) {
        if (principal instanceof Authentication authentication
                && authentication.getPrincipal() instanceof AuthenticatedUserContext authenticatedUser
                && StringUtils.hasText(authenticatedUser.userId())) {
            return authenticatedUser;
        }
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "unauthenticated_user");
    }

    private String normalizeOptional(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }
}
