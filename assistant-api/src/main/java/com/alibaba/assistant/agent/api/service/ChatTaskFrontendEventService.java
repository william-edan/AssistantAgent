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
package com.alibaba.assistant.agent.api.service;

import com.alibaba.assistant.agent.api.protocol.FrontendEvent;
import com.alibaba.assistant.agent.api.protocol.FrontendEventType;
import com.alibaba.assistant.agent.api.protocol.FrontendStage;
import com.alibaba.assistant.agent.api.protocol.V3ProtocolAdapter;
import com.alibaba.assistant.agent.common.chat.FrontendTaskStateSupport;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 统一构造面向前端的任务事件，供异步任务回调和审批恢复等链路复用。
 */
@Service
@Profile("migration")
public class ChatTaskFrontendEventService {

    private final V3ProtocolAdapter protocolAdapter;

    public ChatTaskFrontendEventService(V3ProtocolAdapter protocolAdapter) {
        this.protocolAdapter = protocolAdapter;
    }

    /**
     * 根据任务快照构造前端事件。终态任务会追加结果卡。
     */
    public List<FrontendEvent> buildEvents(String threadId, Map<String, Object> taskPayload) {
        if (!StringUtils.hasText(threadId) || taskPayload == null || taskPayload.isEmpty()) {
            return List.of();
        }
        FrontendEvent taskEvent = protocolAdapter.taskStateEvent(threadId, taskPayload);
        if (taskEvent == null) {
            return List.of();
        }
        List<FrontendEvent> events = new ArrayList<>();
        events.add(taskEvent);
        FrontendEvent resultEvent = buildTerminalResultEvent(threadId, taskEvent);
        if (resultEvent != null) {
            events.add(resultEvent);
        }
        return events;
    }

    private FrontendEvent buildTerminalResultEvent(String threadId, FrontendEvent taskEvent) {
        if (taskEvent == null || taskEvent.payload() == null || taskEvent.payload().isEmpty()) {
            return null;
        }
        Map<String, Object> normalizedPayload = taskEvent.payload();
        String status = FrontendTaskStateSupport.normalizedStatus(normalizedPayload);
        if (!FrontendTaskStateSupport.isTerminalStatus(status)) {
            return null;
        }

        Map<String, Object> resultPayload = new LinkedHashMap<>();
        boolean success = FrontendTaskStateSupport.STATUS_COMPLETED.equalsIgnoreCase(status);
        resultPayload.put("success", success);
        putText(resultPayload, "artifactCode", firstText(
                normalizedPayload.get("sourceCode"),
                normalizedPayload.get("taskId"),
                normalizedPayload.get("title")));

        Map<String, Object> resultPreview = asMap(normalizedPayload.get("resultPreview"));
        if (!resultPreview.isEmpty()) {
            resultPayload.put("result", resultPreview);
        }
        Map<String, Object> notification = asMap(normalizedPayload.get("notification"));
        String error = firstText(
                resultPreview.get("error"),
                normalizedPayload.get("error"),
                notification.get("body"));
        if (!success && StringUtils.hasText(error)) {
            resultPayload.put("error", error);
        }
        if (normalizedPayload.containsKey("taskId")) {
            resultPayload.put("taskId", normalizedPayload.get("taskId"));
        }
        if (normalizedPayload.containsKey("taskType")) {
            resultPayload.put("taskType", normalizedPayload.get("taskType"));
        }
        return new FrontendEvent(
                V3ProtocolAdapter.PROTOCOL_VERSION,
                UUID.randomUUID().toString(),
                threadId,
                Instant.now().toString(),
                FrontendEventType.RESULT,
                success ? FrontendStage.DONE : FrontendStage.ERROR,
                resultPayload);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object value) {
        if (value instanceof Map<?, ?> map && !map.isEmpty()) {
            return new LinkedHashMap<>((Map<String, Object>) map);
        }
        return Map.of();
    }

    private String firstText(Object... values) {
        if (values == null) {
            return null;
        }
        for (Object value : values) {
            if (value == null) {
                continue;
            }
            String text = String.valueOf(value).trim();
            if (StringUtils.hasText(text)) {
                return text;
            }
        }
        return null;
    }

    private void putText(Map<String, Object> target, String key, String value) {
        if (StringUtils.hasText(value)) {
            target.put(key, value);
        }
    }
}
