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
package com.alibaba.assistant.agent.start.chat;

import com.alibaba.cloud.ai.agent.studio.controller.ExecutionController;
import com.alibaba.cloud.ai.agent.studio.dto.AgentResumeRequest;
import com.alibaba.cloud.ai.agent.studio.dto.AgentRunRequest;
import com.alibaba.cloud.ai.agent.studio.dto.messages.UserMessageDTO;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Unified chat endpoints that reuse studio run/resume SSE execution.
 */
@RestController
@RequestMapping("/api/chat")
@org.springframework.context.annotation.Profile("!migration")
public class UnifiedChatController {

    private static final String STATE_KEY_ASSISTANT_UID = "assistantUid";
    private static final String STATE_KEY_SYSTEM_CODE = "systemCode";

    private final ExecutionController executionController;
    private final String defaultAppName;

    public UnifiedChatController(
            ExecutionController executionController,
            @Value("${assistant.chat.default-app-name:grayscale_agent}") String defaultAppName) {
        this.executionController = executionController;
        this.defaultAppName = defaultAppName;
    }

    /**
     * Query-param friendly stream endpoint for compatibility with existing callers.
     */
    @GetMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> stream(
            @RequestParam("threadId") String threadId,
            @RequestParam("userId") String userId,
            @RequestParam("message") String message,
            @RequestParam(value = "appName", required = false) String appName,
            @RequestParam(value = "assistantUid", required = false) String assistantUid,
            @RequestParam(value = "systemCode", required = false) String systemCode) {
        AgentRunRequest request = new AgentRunRequest();
        request.appName = resolveAppName(appName, assistantUid);
        request.threadId = threadId;
        request.userId = userId;
        request.newMessage = new UserMessageDTO(message);
        request.streaming = true;
        request.stateDelta = mergeStateDelta(null, assistantUid, systemCode);
        return executionController.agentRunSse(request);
    }

    /**
     * Run endpoint aligned with studio AgentRunRequest.
     */
    @PostMapping(path = "/run_sse", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> runSse(
            @RequestBody AgentRunRequest request,
            @RequestParam(value = "appName", required = false) String appName,
            @RequestParam(value = "assistantUid", required = false) String assistantUid,
            @RequestParam(value = "systemCode", required = false) String systemCode) {
        normalizeRunRequest(request, appName, assistantUid, systemCode);
        return executionController.agentRunSse(request);
    }

    /**
     * Resume endpoint aligned with studio AgentResumeRequest.
     */
    @PostMapping(path = "/resume_sse", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> resumeSse(
            @RequestBody AgentResumeRequest request,
            @RequestParam(value = "appName", required = false) String appName,
            @RequestParam(value = "assistantUid", required = false) String assistantUid,
            @RequestParam(value = "systemCode", required = false) String systemCode) {
        normalizeResumeRequest(request, appName, assistantUid, systemCode);
        return executionController.agentResumeSse(request);
    }

    private void normalizeRunRequest(
            AgentRunRequest request,
            String appName,
            String assistantUid,
            String systemCode) {
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "request body cannot be null");
        }
        request.appName = resolveAppName(request.appName, appName, assistantUid);
        if (!StringUtils.hasText(request.threadId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "threadId cannot be null or empty");
        }
        if (request.newMessage == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "newMessage cannot be null");
        }
        request.streaming = true;
        request.stateDelta = mergeStateDelta(request.stateDelta, assistantUid, systemCode);
    }

    private void normalizeResumeRequest(
            AgentResumeRequest request,
            String appName,
            String assistantUid,
            String systemCode) {
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "request body cannot be null");
        }
        request.appName = resolveAppName(request.appName, appName, assistantUid);
        if (!StringUtils.hasText(request.threadId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "threadId cannot be null or empty");
        }
        request.streaming = true;
        request.stateDelta = mergeStateDelta(request.stateDelta, assistantUid, systemCode);
    }

    private String resolveAppName(String... candidates) {
        for (String candidate : candidates) {
            if (StringUtils.hasText(candidate)) {
                return candidate;
            }
        }
        if (!StringUtils.hasText(defaultAppName)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "appName cannot be null or empty");
        }
        return defaultAppName;
    }

    private Map<String, Object> mergeStateDelta(
            Map<String, Object> baseStateDelta,
            String assistantUid,
            String systemCode) {
        Map<String, Object> merged = new LinkedHashMap<>();
        if (baseStateDelta != null) {
            merged.putAll(baseStateDelta);
        }
        if (StringUtils.hasText(assistantUid)) {
            merged.putIfAbsent(STATE_KEY_ASSISTANT_UID, assistantUid);
        }
        if (StringUtils.hasText(systemCode)) {
            merged.putIfAbsent(STATE_KEY_SYSTEM_CODE, systemCode);
        }
        return merged.isEmpty() ? null : merged;
    }
}
