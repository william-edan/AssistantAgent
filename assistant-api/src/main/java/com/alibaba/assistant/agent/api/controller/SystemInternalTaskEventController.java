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

import com.alibaba.assistant.agent.api.controller.dto.InternalTaskEventUpdateRequest;
import com.alibaba.assistant.agent.api.protocol.FrontendEvent;
import com.alibaba.assistant.agent.api.service.ChatAsyncTaskUpdateCommand;
import com.alibaba.assistant.agent.api.service.ChatAsyncTaskUpdateService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 内部异步任务回调入口，用于子 Agent / MCP 在后台推送任务进度和结果。
 */
@RestController
@Profile("migration")
@RequestMapping("/system/internal/chat/tasks")
public class SystemInternalTaskEventController {

    private static final String CALLBACK_TOKEN_HEADER = "X-Assistant-Callback-Token";

    private final ChatAsyncTaskUpdateService chatAsyncTaskUpdateService;

    private final String callbackToken;

    public SystemInternalTaskEventController(
            ChatAsyncTaskUpdateService chatAsyncTaskUpdateService,
            @Value("${assistant.chat.internal-callback-token:}") String callbackToken) {
        this.chatAsyncTaskUpdateService = chatAsyncTaskUpdateService;
        this.callbackToken = callbackToken;
    }

    /**
     * 接收内部任务状态更新，并返回标准化后的前端事件。
     */
    @PostMapping("/events")
    public ResponseEntity<Map<String, Object>> updateTaskEvents(
            @RequestHeader(value = CALLBACK_TOKEN_HEADER, required = false) String callbackTokenHeader,
            @RequestBody(required = false) InternalTaskEventUpdateRequest request) {
        ensureAuthorized(callbackTokenHeader);
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "request_body_required");
        }
        List<FrontendEvent> events = chatAsyncTaskUpdateService.publishUpdate(new ChatAsyncTaskUpdateCommand(
                request.getThreadId(),
                request.getAssistantUid(),
                request.getAppName(),
                request.getSystemCode(),
                request.getTurnId(),
                request.getTask()));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("events", events);
        return ResponseEntity.ok(Map.of("code", 0, "msg", "", "data", data));
    }

    private void ensureAuthorized(String callbackTokenHeader) {
        if (!StringUtils.hasText(callbackToken)) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "callback_token_not_configured");
        }
        byte[] expected = callbackToken.trim().getBytes(StandardCharsets.UTF_8);
        byte[] actual = StringUtils.hasText(callbackTokenHeader)
                ? callbackTokenHeader.trim().getBytes(StandardCharsets.UTF_8)
                : new byte[0];
        if (!MessageDigest.isEqual(expected, actual)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid_callback_token");
        }
    }
}
