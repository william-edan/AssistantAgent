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
import com.alibaba.assistant.agent.api.stream.FrontendEventStreamRegistry;
import org.springframework.context.annotation.Profile;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 统一负责前端事件的持久化和实时分发，避免聊天主链和异步回调各自处理一套逻辑。
 */
@Service
@Profile("migration")
public class ChatFrontendEventPublisher {

    @Nullable
    private final ChatTranscriptPersistenceService transcriptPersistenceService;

    @Nullable
    private final FrontendEventStreamRegistry frontendEventStreamRegistry;

    public ChatFrontendEventPublisher(
            @Nullable ChatTranscriptPersistenceService transcriptPersistenceService,
            @Nullable FrontendEventStreamRegistry frontendEventStreamRegistry) {
        this.transcriptPersistenceService = transcriptPersistenceService;
        this.frontendEventStreamRegistry = frontendEventStreamRegistry;
    }

    /**
     * 发布一个前端事件，并同步写入聊天记录与线程读模型。
     */
    public void publish(
            String threadId,
            String assistantUid,
            String appName,
            String systemCode,
            String turnId,
            FrontendEvent event) {
        if (!StringUtils.hasText(threadId) || event == null) {
            return;
        }
        if (transcriptPersistenceService != null && StringUtils.hasText(assistantUid)) {
            transcriptPersistenceService.recordFrontendEvent(threadId, assistantUid, appName, systemCode, turnId, event);
        }
        if (frontendEventStreamRegistry != null) {
            frontendEventStreamRegistry.publish(threadId, event);
        }
    }

    /**
     * 结束一个对话轮次，刷新线程汇总状态。
     */
    public void finishTurn(
            String threadId,
            String assistantUid,
            String appName,
            String systemCode,
            String turnId) {
        if (transcriptPersistenceService == null
                || !StringUtils.hasText(threadId)
                || !StringUtils.hasText(assistantUid)) {
            return;
        }
        transcriptPersistenceService.finishTurn(threadId, assistantUid, appName, systemCode, turnId);
    }
}
