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

import com.alibaba.assistant.agent.common.chat.FrontendTaskStateSupport;
import com.alibaba.assistant.agent.api.protocol.FrontendEvent;
import com.alibaba.assistant.agent.api.protocol.FrontendEventType;
import com.alibaba.assistant.agent.api.protocol.FrontendFormStateSupport;
import com.alibaba.assistant.agent.api.protocol.FrontendMessageVisibilitySupport;
import com.alibaba.assistant.agent.api.protocol.FrontendStage;
import com.alibaba.assistant.agent.execution.persistence.ChatMessageRecord;
import com.alibaba.assistant.agent.execution.persistence.ChatMessageRecordService;
import com.alibaba.assistant.agent.execution.persistence.ChatThreadRecord;
import com.alibaba.assistant.agent.execution.persistence.ChatThreadRecordService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 聊天记录持久化服务。
 *
 * <p>负责把前端可见事件落到线程表和消息表中。
 * 这里的原则是：只持久化前端真正需要展示和恢复的内容，不把内部规划噪音写进聊天记录。</p>
 */
@Service
@Profile("migration")
public class ChatTranscriptPersistenceService {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final ChatThreadRecordService chatThreadRecordService;

    private final ChatMessageRecordService chatMessageRecordService;

    private final ObjectMapper objectMapper;

    public ChatTranscriptPersistenceService(
            ChatThreadRecordService chatThreadRecordService,
            ChatMessageRecordService chatMessageRecordService,
            ObjectMapper objectMapper) {
        this.chatThreadRecordService = chatThreadRecordService;
        this.chatMessageRecordService = chatMessageRecordService;
        this.objectMapper = objectMapper;
    }

    public void recordUserMessage(
            String threadId,
            String assistantUid,
            String appName,
            String systemCode,
            String turnId,
            String text) {
        recordUserMessage(threadId, assistantUid, appName, systemCode, turnId, text, Map.of());
    }

    public void recordUserMessage(
            String threadId,
            String assistantUid,
            String appName,
            String systemCode,
            String turnId,
            String text,
            Map<String, Object> roleBindingAttributes) {
        if (!hasThreadContext(threadId, assistantUid) || !StringUtils.hasText(text)) {
            return;
        }
        RoleBinding roleBinding = RoleBinding.from(roleBindingAttributes);
        ChatThreadRecord threadRecord = loadOrCreateThread(threadId, assistantUid, appName, systemCode);
        applyRoleBinding(threadRecord, roleBinding);
        threadRecord.setTitle(firstText(threadRecord.getTitle(), abbreviate(text, 80)));
        threadRecord.setStatus("UNDERSTANDING");
        threadRecord.setPhase("UNDERSTANDING");
        threadRecord.setUnfinished(Boolean.TRUE);
        threadRecord.setCanResume(Boolean.FALSE);
        threadRecord.setLastUserMessage(text.trim());
        threadRecord.setLastMessagePreview(text.trim());
        threadRecord.setLastEventType("USER_MESSAGE");
        threadRecord.setLastMessageAt(LocalDateTime.now());
        chatThreadRecordService.saveOrUpdateByThreadId(threadRecord);

        ChatMessageRecord userMessage = new ChatMessageRecord();
        userMessage.setMessageId(UUID.randomUUID().toString());
        userMessage.setThreadId(threadId.trim());
        userMessage.setAssistantUid(assistantUid.trim());
        userMessage.setTurnId(turnId);
        userMessage.setMessageType("USER_MESSAGE");
        userMessage.setEventType("USER_MESSAGE");
        userMessage.setStage("UNDERSTANDING");
        userMessage.setStatus("COMPLETED");
        userMessage.setTitle("用户消息");
        userMessage.setSummaryText(text.trim());
        userMessage.setPayloadJson(serialize(Map.of(
                "messageType", "user",
                "text", text.trim())));
        userMessage.setCollapsed(Boolean.FALSE);
        userMessage.setRevisionNo(1);
        chatMessageRecordService.save(userMessage);
    }

    public void recordResumeAction(
            String threadId,
            String assistantUid,
            String appName,
            String systemCode,
            String turnId,
            String actionText) {
        recordResumeAction(threadId, assistantUid, appName, systemCode, turnId, actionText, Map.of());
    }

    public void recordResumeAction(
            String threadId,
            String assistantUid,
            String appName,
            String systemCode,
            String turnId,
            String actionText,
            Map<String, Object> roleBindingAttributes) {
        if (!StringUtils.hasText(actionText)) {
            return;
        }
        recordUserMessage(threadId, assistantUid, appName, systemCode, turnId, actionText, roleBindingAttributes);
    }

    public void recordFrontendEvent(
            String threadId,
            String assistantUid,
            String appName,
            String systemCode,
            String turnId,
            FrontendEvent event) {
        recordFrontendEvent(threadId, assistantUid, appName, systemCode, turnId, event, Map.of());
    }

    public void recordFrontendEvent(
            String threadId,
            String assistantUid,
            String appName,
            String systemCode,
            String turnId,
            FrontendEvent event,
            Map<String, Object> roleBindingAttributes) {
        if (!hasThreadContext(threadId, assistantUid) || event == null || event.eventType() == null) {
            return;
        }
        RoleBinding roleBinding = RoleBinding.from(roleBindingAttributes);
        FrontendEvent normalizedEvent = normalizeEvent(event);
        ChatThreadRecord threadRecord = loadOrCreateThread(threadId, assistantUid, appName, systemCode);
        applyRoleBinding(threadRecord, roleBinding);
        if (shouldSkipAssistantMessage(threadRecord, normalizedEvent)) {
            return;
        }
        applyEventToThread(threadRecord, normalizedEvent);
        chatThreadRecordService.saveOrUpdateByThreadId(threadRecord);

        // 不同事件类型会投影成不同的聊天卡片，前端恢复时依赖这里的 messageType。
        switch (normalizedEvent.eventType()) {
            case STAGE, EXECUTION_PROGRESS -> {
                // 线程摘要已经更新，这两类事件不需要额外生成聊天卡片。
            }
            case MESSAGE -> appendAssistantMessage(threadId, assistantUid, turnId, normalizedEvent, roleBinding);
            case FORM_STATE -> upsertCardMessage(
                    threadId,
                    assistantUid,
                    turnId,
                    buildFormSourceKey(turnId, normalizedEvent),
                    "FORM_CARD",
                    normalizedEvent,
                    false,
                    roleBinding);
            case TASK_STATE -> upsertCardMessage(
                    threadId,
                    assistantUid,
                    turnId,
                    buildTaskSourceKey(normalizedEvent),
                    "TASK_CARD",
                    normalizedEvent,
                    !Boolean.FALSE.equals(normalizedEvent.payload().get("collapsible")),
                    roleBinding);
            case RESULT -> appendCardMessage(threadId, assistantUid, turnId, "RESULT_CARD", normalizedEvent, false, roleBinding);
            case ERROR -> appendCardMessage(threadId, assistantUid, turnId, "ERROR_CARD", normalizedEvent, false, roleBinding);
        }
    }

    private boolean shouldSkipAssistantMessage(ChatThreadRecord threadRecord, FrontendEvent event) {
        if (threadRecord == null || event == null || event.eventType() != FrontendEventType.MESSAGE) {
            return false;
        }
        if (FrontendMessageVisibilitySupport.isInternalPlanningNarration(textValue(event.payload().get("text")))) {
            return true;
        }
        if (event.stage() != FrontendStage.DONE) {
            return false;
        }
        if (!StringUtils.hasText(threadRecord.getPendingCardType())) {
            return false;
        }
        return "FORM_STATE".equalsIgnoreCase(threadRecord.getLastEventType())
                || "TASK_STATE".equalsIgnoreCase(threadRecord.getLastEventType())
                || "RESULT".equalsIgnoreCase(threadRecord.getLastEventType())
                || "ERROR".equalsIgnoreCase(threadRecord.getLastEventType());
    }

    public void finishTurn(
            String threadId,
            String assistantUid,
            String appName,
            String systemCode,
            String turnId) {
        if (!hasThreadContext(threadId, assistantUid)) {
            return;
        }
        chatThreadRecordService.findByThreadId(threadId.trim()).ifPresent(threadRecord -> {
            if ("UNDERSTANDING".equalsIgnoreCase(threadRecord.getStatus())
                    && "MESSAGE".equalsIgnoreCase(threadRecord.getLastEventType())) {
                threadRecord.setStatus("COMPLETED");
                threadRecord.setPhase("DONE");
                threadRecord.setUnfinished(Boolean.FALSE);
                threadRecord.setCanResume(Boolean.FALSE);
                threadRecord.setPendingCardType(null);
                chatThreadRecordService.saveOrUpdateByThreadId(threadRecord);
            }
        });
    }

    private void appendAssistantMessage(
            String threadId,
            String assistantUid,
            String turnId,
            FrontendEvent event,
            RoleBinding roleBinding) {
        String chunk = textValue(event.payload().get("text"));
        if (!StringUtils.hasText(chunk)) {
            return;
        }
        String sourceKey = "ASSISTANT_STREAM:" + turnId;
        ChatMessageRecord messageRecord = chatMessageRecordService.findBySourceKey(sourceKey).orElseGet(ChatMessageRecord::new);
        String fullText = chunk;
        if (messageRecord.getId() != null && StringUtils.hasText(messageRecord.getPayloadJson())) {
            Map<String, Object> existingPayload = readMap(messageRecord.getPayloadJson());
            fullText = mergeAssistantText(textValue(existingPayload.get("text")), chunk);
        }
        messageRecord.setMessageId(firstText(messageRecord.getMessageId(), UUID.randomUUID().toString()));
        messageRecord.setThreadId(threadId.trim());
        messageRecord.setAssistantUid(assistantUid.trim());
        messageRecord.setTurnId(turnId);
        messageRecord.setSourceKey(sourceKey);
        messageRecord.setMessageType("ASSISTANT_MESSAGE");
        messageRecord.setEventType(FrontendEventType.MESSAGE.name());
        messageRecord.setStage(event.stage() != null ? event.stage().name() : FrontendStage.DONE.name());
        messageRecord.setStatus("COMPLETED");
        messageRecord.setTitle("助手回复");
        messageRecord.setSummaryText(abbreviate(fullText, 300));
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("eventType", FrontendEventType.MESSAGE.name());
        envelope.put("stage", event.stage() != null ? event.stage().name() : FrontendStage.DONE.name());
        envelope.put("text", fullText);
        roleBinding.applyToEnvelope(envelope);
        messageRecord.setPayloadJson(serialize(envelope));
        messageRecord.setCollapsed(Boolean.FALSE);
        messageRecord.setRevisionNo(messageRecord.getRevisionNo() != null ? messageRecord.getRevisionNo() + 1 : 1);
        chatMessageRecordService.saveOrUpdateBySourceKey(messageRecord);

        String persistedFullText = fullText;
        chatThreadRecordService.findByThreadId(threadId.trim()).ifPresent(threadRecord -> {
            threadRecord.setLastAssistantMessage(abbreviate(persistedFullText, 500));
            threadRecord.setLastMessagePreview(abbreviate(persistedFullText, 160));
            threadRecord.setLastEventType(FrontendEventType.MESSAGE.name());
            threadRecord.setLastMessageAt(LocalDateTime.now());
            chatThreadRecordService.saveOrUpdateByThreadId(threadRecord);
        });
    }

    private String mergeAssistantText(String existingText, String incomingText) {
        String existing = existingText != null ? existingText.trim() : null;
        String incoming = incomingText != null ? incomingText.trim() : null;
        if (!StringUtils.hasText(existing)) {
            return incoming;
        }
        if (!StringUtils.hasText(incoming)) {
            return existing;
        }
        if (incoming.equals(existing)) {
            return existing;
        }
        if (incoming.startsWith(existing)) {
            return incoming;
        }
        if (existing.endsWith(incoming)) {
            return existing;
        }
        return existing + incoming;
    }
    private void upsertCardMessage(
            String threadId,
            String assistantUid,
            String turnId,
            String sourceKey,
            String messageType,
            FrontendEvent event,
            boolean collapsed,
            RoleBinding roleBinding) {
        ChatMessageRecord messageRecord = chatMessageRecordService.findBySourceKey(sourceKey).orElseGet(ChatMessageRecord::new);
        messageRecord.setMessageId(firstText(messageRecord.getMessageId(), UUID.randomUUID().toString()));
        messageRecord.setThreadId(threadId.trim());
        messageRecord.setAssistantUid(assistantUid.trim());
        messageRecord.setTurnId(turnId);
        messageRecord.setSourceKey(sourceKey);
        messageRecord.setMessageType(messageType);
        messageRecord.setEventType(event.eventType().name());
        messageRecord.setStage(event.stage() != null ? event.stage().name() : null);
        messageRecord.setStatus(resolveEventStatus(event));
        messageRecord.setTitle(resolveEventTitle(event, messageType));
        messageRecord.setSummaryText(resolveEventSummary(event));
        messageRecord.setPayloadJson(serialize(toPersistedEnvelope(event, roleBinding)));
        messageRecord.setCollapsed(collapsed);
        messageRecord.setRevisionNo(messageRecord.getRevisionNo() != null ? messageRecord.getRevisionNo() + 1 : 1);
        chatMessageRecordService.saveOrUpdateBySourceKey(messageRecord);
    }

    private void appendCardMessage(
            String threadId,
            String assistantUid,
            String turnId,
            String messageType,
            FrontendEvent event,
            boolean collapsed,
            RoleBinding roleBinding) {
        ChatMessageRecord messageRecord = new ChatMessageRecord();
        messageRecord.setMessageId(UUID.randomUUID().toString());
        messageRecord.setThreadId(threadId.trim());
        messageRecord.setAssistantUid(assistantUid.trim());
        messageRecord.setTurnId(turnId);
        messageRecord.setMessageType(messageType);
        messageRecord.setEventType(event.eventType().name());
        messageRecord.setStage(event.stage() != null ? event.stage().name() : null);
        messageRecord.setStatus(resolveEventStatus(event));
        messageRecord.setTitle(resolveEventTitle(event, messageType));
        messageRecord.setSummaryText(resolveEventSummary(event));
        messageRecord.setPayloadJson(serialize(toPersistedEnvelope(event, roleBinding)));
        messageRecord.setCollapsed(collapsed);
        messageRecord.setRevisionNo(1);
        chatMessageRecordService.save(messageRecord);
    }

    private void applyEventToThread(ChatThreadRecord threadRecord, FrontendEvent event) {
        if (threadRecord == null || event == null || event.eventType() == null) {
            return;
        }
        threadRecord.setLastEventType(event.eventType().name());
        threadRecord.setLastMessageAt(LocalDateTime.now());
        threadRecord.setLastMessagePreview(firstText(resolveEventSummary(event), threadRecord.getLastMessagePreview()));

        switch (event.eventType()) {
            case STAGE -> applyStageState(threadRecord, event.stage());
            case FORM_STATE -> applyFormState(threadRecord, event);
            case TASK_STATE -> applyTaskState(threadRecord, event);
            case RESULT -> applyResultState(threadRecord, event);
            case ERROR -> {
                threadRecord.setStatus("FAILED");
                threadRecord.setPhase("ERROR");
                threadRecord.setUnfinished(Boolean.FALSE);
                threadRecord.setCanResume(Boolean.FALSE);
                threadRecord.setPendingCardType("ERROR_CARD");
            }
            case MESSAGE -> {
                if (event.stage() != null && event.stage() != FrontendStage.DONE) {
                    threadRecord.setPhase(event.stage().name());
                }
            }
            default -> {
            }
        }
    }

    private void applyStageState(ChatThreadRecord threadRecord, FrontendStage stage) {
        if (threadRecord == null || stage == null) {
            return;
        }
        threadRecord.setPhase(stage.name());
        switch (stage) {
            case COLLECTING -> {
                threadRecord.setStatus("WAITING_INPUT");
                threadRecord.setUnfinished(Boolean.TRUE);
                threadRecord.setCanResume(Boolean.TRUE);
            }
            case CONFIRMING -> {
                threadRecord.setStatus("WAITING_CONFIRMATION");
                threadRecord.setUnfinished(Boolean.TRUE);
                threadRecord.setCanResume(Boolean.TRUE);
            }
            case EXECUTING -> {
                threadRecord.setStatus("RUNNING");
                threadRecord.setUnfinished(Boolean.TRUE);
                threadRecord.setCanResume(Boolean.FALSE);
            }
            case WAITING_APPROVAL -> {
                threadRecord.setStatus("WAITING_APPROVAL");
                threadRecord.setUnfinished(Boolean.TRUE);
                threadRecord.setCanResume(Boolean.TRUE);
                threadRecord.setPendingCardType("TASK_CARD");
            }
            case DONE -> {
                threadRecord.setStatus("COMPLETED");
                threadRecord.setUnfinished(Boolean.FALSE);
                threadRecord.setCanResume(Boolean.FALSE);
            }
            case ERROR -> {
                threadRecord.setStatus("FAILED");
                threadRecord.setUnfinished(Boolean.FALSE);
                threadRecord.setCanResume(Boolean.FALSE);
            }
            default -> {
                threadRecord.setStatus("UNDERSTANDING");
                threadRecord.setUnfinished(Boolean.TRUE);
                threadRecord.setCanResume(Boolean.FALSE);
            }
        }
    }

    private void applyFormState(ChatThreadRecord threadRecord, FrontendEvent event) {
        String normalizedPhase = FrontendFormStateSupport.normalizedPhase(
                event.payload(),
                event.stage() != null ? event.stage().name() : null,
                textValue(event.payload().get("status")));
        String normalizedStatus = FrontendFormStateSupport.normalizedStatus(
                event.payload(),
                event.stage() != null ? event.stage().name() : null,
                textValue(event.payload().get("status")));
        boolean unfinished = !ChatThreadActionSupport.isTerminalStatus(normalizedStatus);
        threadRecord.setPhase(normalizedPhase);
        threadRecord.setStatus(normalizedStatus);
        threadRecord.setUnfinished(unfinished);
        threadRecord.setCanResume(ChatThreadActionSupport.canResume(normalizedStatus, normalizedPhase, unfinished));
        threadRecord.setToolCode(firstText(event.payload().get("toolCode"), threadRecord.getToolCode()));
        threadRecord.setPendingCardType("FORM_CARD");
    }

    private void applyTaskState(ChatThreadRecord threadRecord, FrontendEvent event) {
        String status = textValue(event.payload().get("status"));
        String phase = event.stage() != null ? event.stage().name() : FrontendStage.EXECUTING.name();
        threadRecord.setPhase(phase);
        threadRecord.setStatus(firstText(status, "RUNNING"));
        boolean unfinished = !ChatThreadActionSupport.isTerminalStatus(status);
        threadRecord.setUnfinished(unfinished);
        threadRecord.setCanResume(ChatThreadActionSupport.canResume(status, phase, unfinished));
        threadRecord.setToolCode(firstText(event.payload().get("sourceCode"), threadRecord.getToolCode()));
        threadRecord.setPendingCardType("TASK_CARD");
    }

    private void applyResultState(ChatThreadRecord threadRecord, FrontendEvent event) {
        boolean success = !Boolean.FALSE.equals(event.payload().get("success"))
                && !StringUtils.hasText(textValue(event.payload().get("error")));
        threadRecord.setPhase(success ? "DONE" : "ERROR");
        threadRecord.setStatus(success ? "COMPLETED" : "FAILED");
        threadRecord.setUnfinished(Boolean.FALSE);
        threadRecord.setCanResume(Boolean.FALSE);
        threadRecord.setToolCode(firstText(event.payload().get("artifactCode"), threadRecord.getToolCode()));
        threadRecord.setPendingCardType("RESULT_CARD");
    }

    private ChatThreadRecord loadOrCreateThread(String threadId, String assistantUid, String appName, String systemCode) {
        ChatThreadRecord threadRecord = chatThreadRecordService.findByThreadId(threadId.trim()).orElseGet(ChatThreadRecord::new);
        threadRecord.setThreadId(threadId.trim());
        threadRecord.setAssistantUid(assistantUid.trim());
        threadRecord.setAppName(firstText(appName, threadRecord.getAppName()));
        threadRecord.setSystemCode(firstText(systemCode, threadRecord.getSystemCode()));
        threadRecord.setStatus(firstText(threadRecord.getStatus(), "UNDERSTANDING"));
        threadRecord.setPhase(firstText(threadRecord.getPhase(), "UNDERSTANDING"));
        threadRecord.setUnfinished(threadRecord.getUnfinished() != null ? threadRecord.getUnfinished() : Boolean.TRUE);
        threadRecord.setCanResume(threadRecord.getCanResume() != null ? threadRecord.getCanResume() : Boolean.FALSE);
        return threadRecord;
    }

    private void applyRoleBinding(ChatThreadRecord threadRecord, RoleBinding roleBinding) {
        if (threadRecord == null || roleBinding == null || roleBinding.isEmpty()) {
            return;
        }
        threadRecord.setRolePackageCode(firstText(roleBinding.rolePackageCode(), threadRecord.getRolePackageCode()));
        threadRecord.setRolePackageVersion(firstText(roleBinding.rolePackageVersion(), threadRecord.getRolePackageVersion()));
        threadRecord.setRoleScenarioCode(firstText(roleBinding.roleScenarioCode(), threadRecord.getRoleScenarioCode()));
    }

    private String buildFormSourceKey(String turnId, FrontendEvent event) {
        String toolCode = textValue(event.payload().get("toolCode"));
        String mode = textValue(event.payload().get("mode"));
        return "FORM_CARD:" + turnId + ":" + firstText(toolCode, "unknown") + ":" + firstText(mode, "COLLECT");
    }

    private String buildTaskSourceKey(FrontendEvent event) {
        String taskId = textValue(event.payload().get("taskId"));
        return "TASK_CARD:" + firstText(taskId, UUID.randomUUID().toString());
    }

    private Map<String, Object> toPersistedEnvelope(FrontendEvent event, RoleBinding roleBinding) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("protocolVersion", event.protocolVersion());
        envelope.put("eventId", event.eventId());
        envelope.put("threadId", event.threadId());
        envelope.put("timestamp", event.timestamp());
        envelope.put("eventType", event.eventType() != null ? event.eventType().name() : null);
        envelope.put("stage", event.stage() != null ? event.stage().name() : null);
        envelope.put("payload", event.payload());
        if (roleBinding != null) {
            roleBinding.applyToEnvelope(envelope);
        }
        return envelope;
    }

    private record RoleBinding(String rolePackageCode, String rolePackageVersion, String roleScenarioCode) {

        static RoleBinding from(Map<String, Object> attributes) {
            if (attributes == null || attributes.isEmpty()) {
                return empty();
            }
            return new RoleBinding(
                    firstText(attributes.get("rolePackageCode"), attributes.get("role_package_code")),
                    firstText(attributes.get("rolePackageVersion"), attributes.get("role_package_version")),
                    firstText(attributes.get("roleScenarioCode"), attributes.get("role_scenario_code")));
        }

        static RoleBinding empty() {
            return new RoleBinding(null, null, null);
        }

        boolean isEmpty() {
            return !StringUtils.hasText(rolePackageCode)
                    && !StringUtils.hasText(rolePackageVersion)
                    && !StringUtils.hasText(roleScenarioCode);
        }

        void applyToEnvelope(Map<String, Object> envelope) {
            if (envelope == null || isEmpty()) {
                return;
            }
            putIfText(envelope, "rolePackageCode", rolePackageCode);
            putIfText(envelope, "rolePackageVersion", rolePackageVersion);
            putIfText(envelope, "roleScenarioCode", roleScenarioCode);
        }

        private static void putIfText(Map<String, Object> envelope, String key, String value) {
            if (StringUtils.hasText(value)) {
                envelope.put(key, value);
            }
        }

        private static String firstText(Object... values) {
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
    }

    private String resolveEventStatus(FrontendEvent event) {
        if (event == null || event.eventType() == null) {
            return null;
        }
        return switch (event.eventType()) {
            case FORM_STATE -> FrontendFormStateSupport.normalizedStatus(
                    event.payload(),
                    event.stage() != null ? event.stage().name() : null,
                    textValue(event.payload().get("status")));
            case TASK_STATE -> FrontendTaskStateSupport.normalizedStatus(event.payload());
            case RESULT -> Boolean.FALSE.equals(event.payload().get("success")) ? "FAILED" : "COMPLETED";
            case ERROR -> "FAILED";
            case MESSAGE -> "COMPLETED";
            default -> null;
        };
    }

    private FrontendEvent normalizeEvent(FrontendEvent event) {
        if (event == null) {
            return event;
        }
        if (event.eventType() == FrontendEventType.FORM_STATE) {
            Map<String, Object> normalizedPayload = FrontendFormStateSupport.normalizePayload(
                    event.payload(),
                    event.stage() != null ? event.stage().name() : null,
                    textValue(event.payload().get("status")));
            FrontendStage normalizedStage = FrontendFormStateSupport.normalizedStage(
                    normalizedPayload,
                    event.stage() != null ? event.stage().name() : null,
                    textValue(event.payload().get("status")));
            return new FrontendEvent(
                    event.protocolVersion(),
                    event.eventId(),
                    event.threadId(),
                    event.timestamp(),
                    event.eventType(),
                    normalizedStage,
                    normalizedPayload);
        }
        if (event.eventType() != FrontendEventType.TASK_STATE) {
            return event;
        }
        Map<String, Object> normalizedPayload = FrontendTaskStateSupport.normalizePayload(event.payload());
        FrontendStage normalizedStage = resolveTaskStage(normalizedPayload);
        return new FrontendEvent(
                event.protocolVersion(),
                event.eventId(),
                event.threadId(),
                event.timestamp(),
                event.eventType(),
                normalizedStage,
                normalizedPayload);
    }

    private String resolveEventTitle(FrontendEvent event, String messageType) {
        if (event == null) {
            return null;
        }
        return switch (event.eventType()) {
            case FORM_STATE -> firstText(event.payload().get("message"), event.payload().get("toolCode"), "待处理表单");
            case TASK_STATE -> firstText(
                    FrontendTaskStateSupport.normalizePayload(event.payload()).get("title"),
                    event.payload().get("title"),
                    event.payload().get("sourceCode"),
                    "后台任务");
            case RESULT -> {
                Map<String, Object> result = asMap(event.payload().get("result"));
                yield firstText(
                        result.get("title"),
                        event.payload().get("title"),
                        event.payload().get("sourceLabel"),
                        event.payload().get("artifactCode"),
                        "执行结果");
            }
            case ERROR -> "执行异常";
            case MESSAGE -> "助手回复";
            default -> firstText(messageType, "聊天消息");
        };
    }

    private String resolveEventSummary(FrontendEvent event) {
        if (event == null || event.payload() == null) {
            return null;
        }
        if (event.eventType() == FrontendEventType.MESSAGE) {
            return textValue(event.payload().get("text"));
        }
        if (event.eventType() == FrontendEventType.FORM_STATE) {
            List<Map<String, Object>> summaryItems = asListOfMaps(asMap(event.payload().get("summary")).get("summaryItems"));
            if (!summaryItems.isEmpty()) {
                String summaryText = summaryItems.stream()
                        .limit(2)
                        .map(this::renderSummaryItem)
                        .filter(StringUtils::hasText)
                        .reduce((left, right) -> left + "，" + right)
                        .orElse(null);
                if (StringUtils.hasText(summaryText)) {
                    return summaryText;
                }
            }
            return textValue(event.payload().get("message"));
        }
        if (event.eventType() == FrontendEventType.TASK_STATE) {
            Map<String, Object> normalizedPayload = FrontendTaskStateSupport.normalizePayload(event.payload());
            return firstText(
                    FrontendTaskStateSupport.summaryText(normalizedPayload),
                    normalizedPayload.get("title"),
                    normalizedPayload.get("status"));
        }
        if (event.eventType() == FrontendEventType.RESULT) {
            String error = textValue(event.payload().get("error"));
            if (StringUtils.hasText(error)) {
                return error;
            }
            Map<String, Object> result = asMap(event.payload().get("result"));
            return firstText(result.get("summary"), result.get("reportId"), result.get("leave_id"), "执行完成");
        }
        if (event.eventType() == FrontendEventType.ERROR) {
            return firstText(event.payload().get("error"), event.payload().get("code"), "执行失败");
        }
        return null;
    }

    private String renderSummaryItem(Map<String, Object> item) {
        if (item == null || item.isEmpty()) {
            return null;
        }
        String label = textValue(item.get("label"));
        String value = textValue(item.get("value"));
        if (StringUtils.hasText(label) && StringUtils.hasText(value)) {
            return label + "：" + value;
        }
        return firstText(label, value);
    }

    private FrontendStage resolveTaskStage(Map<String, Object> payload) {
        String phase = FrontendTaskStateSupport.normalizedPhase(payload);
        return switch (phase) {
            case "WAITING_APPROVAL" -> FrontendStage.WAITING_APPROVAL;
            case "DONE" -> FrontendStage.DONE;
            case "ERROR" -> FrontendStage.ERROR;
            default -> FrontendStage.EXECUTING;
        };
    }

    private boolean hasThreadContext(String threadId, String assistantUid) {
        return StringUtils.hasText(threadId) && StringUtils.hasText(assistantUid);
    }

    private String textValue(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return StringUtils.hasText(text) ? text : null;
    }

    private String firstText(Object... values) {
        if (values == null) {
            return null;
        }
        for (Object value : values) {
            String text = textValue(value);
            if (StringUtils.hasText(text)) {
                return text;
            }
        }
        return null;
    }

    private String abbreviate(String text, int maxLength) {
        if (!StringUtils.hasText(text)) {
            return null;
        }
        String trimmed = text.trim();
        if (trimmed.length() <= maxLength) {
            return trimmed;
        }
        return trimmed.substring(0, Math.max(0, maxLength - 3)) + "...";
    }

    private String serialize(Object value) {
        try {
            return objectMapper.writeValueAsString(value != null ? value : Map.of());
        }
        catch (Exception e) {
            return "{}";
        }
    }

    private Map<String, Object> readMap(String json) {
        if (!StringUtils.hasText(json)) {
            return Map.of();
        }
        try {
            Map<String, Object> parsed = objectMapper.readValue(json, MAP_TYPE);
            return parsed != null ? new LinkedHashMap<>(parsed) : Map.of();
        }
        catch (Exception e) {
            return Map.of();
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            return new LinkedHashMap<>((Map<String, Object>) map);
        }
        return Map.of();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> asListOfMaps(Object value) {
        if (!(value instanceof List<?> list) || list.isEmpty()) {
            return List.of();
        }
        return list.stream()
                .filter(Map.class::isInstance)
                .<Map<String, Object>>map(item -> new LinkedHashMap<>((Map<String, Object>) item))
                .toList();
    }
}









