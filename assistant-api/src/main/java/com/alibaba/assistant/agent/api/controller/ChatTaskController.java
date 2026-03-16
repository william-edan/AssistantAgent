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

import com.alibaba.assistant.agent.api.controller.dto.ChatTaskEventListResponse;
import com.alibaba.assistant.agent.api.controller.dto.ChatTaskListResponse;
import com.alibaba.assistant.agent.api.controller.dto.ChatTaskResponse;
import com.alibaba.assistant.agent.api.security.AuthenticatedUserContext;
import com.alibaba.assistant.agent.api.service.ChatTaskService;
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
 * 任务中心控制器。
 *
 * <p>对前端暴露任务列表、任务详情和任务事件时间线接口，
 * 供聊天详情页、任务中心页和任务详情抽屉直接调用。</p>
 */
@RestController
@Profile("migration")
@RequestMapping("/api/chat/tasks")
public class ChatTaskController {

    private final ChatTaskService chatTaskService;

    public ChatTaskController(ChatTaskService chatTaskService) {
        this.chatTaskService = chatTaskService;
    }

    @GetMapping
    public ResponseEntity<ChatTaskListResponse> listTasks(
            @RequestParam(required = false) String threadId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Integer limit,
            Principal principal) {
        AuthenticatedUserContext authenticatedUser = requireAuthenticatedUser(principal);
        return ResponseEntity.ok(ChatTaskListResponse.ok(
                chatTaskService.listTasks(authenticatedUser.userId(), normalizeOptional(threadId), normalizeOptional(status), limit)));
    }

    @GetMapping("/{taskId}")
    public ResponseEntity<ChatTaskResponse> getTask(@PathVariable String taskId, Principal principal) {
        AuthenticatedUserContext authenticatedUser = requireAuthenticatedUser(principal);
        return ResponseEntity.ok(ChatTaskResponse.ok(chatTaskService.getTask(authenticatedUser.userId(), taskId)));
    }

    @GetMapping("/{taskId}/events")
    public ResponseEntity<ChatTaskEventListResponse> getTaskEvents(
            @PathVariable String taskId,
            @RequestParam(required = false) Integer limit,
            Principal principal) {
        AuthenticatedUserContext authenticatedUser = requireAuthenticatedUser(principal);
        return ResponseEntity.ok(ChatTaskEventListResponse.ok(
                chatTaskService.listTaskEvents(authenticatedUser.userId(), taskId, limit)));
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
