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
import com.alibaba.assistant.agent.runtime.agent.AssistantStateKeys;
import com.alibaba.assistant.agent.runtime.task.AgentTaskProjector;
import org.springframework.context.annotation.Profile;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 异步任务更新服务。
 *
 * <p>负责接收子 Agent 或 MCP 长任务的状态更新，
 * 并把这些更新同时投影到聊天会话、任务中心和站内信三类前端读侧。</p>
 */
@Service
@Profile("migration")
public class ChatAsyncTaskUpdateService {

    private final ChatTaskFrontendEventService chatTaskFrontendEventService;

    private final ChatFrontendEventPublisher eventPublisher;

    @Nullable
    private final AgentTaskProjector agentTaskProjector;

    public ChatAsyncTaskUpdateService(
            ChatTaskFrontendEventService chatTaskFrontendEventService,
            ChatFrontendEventPublisher eventPublisher,
            @Nullable AgentTaskProjector agentTaskProjector) {
        this.chatTaskFrontendEventService = chatTaskFrontendEventService;
        this.eventPublisher = eventPublisher;
        this.agentTaskProjector = agentTaskProjector;
    }

    /**
     * 发布异步任务更新。
     *
     * <p>任务状态会先被规范化为前端协议，再发布到线程事件流；
     * 如果任务进入终态，读侧会同步生成结果卡和站内信。</p>
     */
    public List<FrontendEvent> publishUpdate(ChatAsyncTaskUpdateCommand command) {
        if (command == null || !StringUtils.hasText(command.threadId()) || !StringUtils.hasText(command.assistantUid())) {
            return List.of();
        }
        Map<String, Object> taskPayload = command.task() != null ? new LinkedHashMap<>(command.task()) : Map.of();
        if (taskPayload.isEmpty()) {
            return List.of();
        }
        String turnId = StringUtils.hasText(command.turnId())
                ? command.turnId().trim()
                : firstText(taskPayload.get("taskId"), UUID.randomUUID().toString());

        List<FrontendEvent> events = chatTaskFrontendEventService.buildEvents(command.threadId(), taskPayload);
        if (!events.isEmpty()) {
            projectTaskState(command, events.get(0));
        }
        for (FrontendEvent event : events) {
            eventPublisher.publish(
                    command.threadId(),
                    command.assistantUid(),
                    command.appName(),
                    command.systemCode(),
                    turnId,
                    event);
        }
        return events;
    }

    private void projectTaskState(ChatAsyncTaskUpdateCommand command, FrontendEvent taskEvent) {
        if (agentTaskProjector == null || taskEvent == null || taskEvent.payload() == null || taskEvent.payload().isEmpty()) {
            return;
        }
        Map<String, Object> stateSnapshot = new LinkedHashMap<>();
        stateSnapshot.put(AssistantStateKeys.ASSISTANT_UID, command.assistantUid());
        stateSnapshot.put(AssistantStateKeys.THREAD_ID, command.threadId());
        putText(stateSnapshot, AssistantStateKeys.SYSTEM_CODE, command.systemCode());
        putText(stateSnapshot, AssistantStateKeys.AGENT_APP_CODE, command.appName());
        agentTaskProjector.recordTaskState(command.threadId(), stateSnapshot, taskEvent.payload());
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

