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
import com.alibaba.assistant.agent.api.controller.dto.ChatMessageData;
import com.alibaba.assistant.agent.api.controller.dto.ChatMessageListData;
import com.alibaba.assistant.agent.api.controller.dto.ChatThreadListData;
import com.alibaba.assistant.agent.api.controller.dto.ChatThreadSummaryData;
import com.alibaba.assistant.agent.api.protocol.FrontendFormStateSupport;
import com.alibaba.assistant.agent.execution.persistence.AgentTaskService;
import com.alibaba.assistant.agent.execution.persistence.ChatMessageRecord;
import com.alibaba.assistant.agent.execution.persistence.ChatMessageRecordService;
import com.alibaba.assistant.agent.execution.persistence.ChatThreadRecord;
import com.alibaba.assistant.agent.execution.persistence.ChatThreadRecordService;
import com.alibaba.assistant.agent.execution.persistence.UserInboxNotificationService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 聊天历史读服务。
 *
 * <p>负责从持久化读模型中恢复线程列表、消息列表和线程摘要，
 * 是页面刷新后恢复聊天记录和会话列表的主要数据来源。</p>
 */
@Service
@Profile("migration")
public class ChatConversationHistoryService {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private static final int THREAD_SUMMARY_MESSAGE_LIMIT = 30;

    private final ChatThreadRecordService chatThreadRecordService;

    private final ChatMessageRecordService chatMessageRecordService;

    private final AgentTaskService agentTaskService;

    private final UserInboxNotificationService userInboxNotificationService;

    private final ObjectMapper objectMapper;

    public ChatConversationHistoryService(
            ChatThreadRecordService chatThreadRecordService,
            ChatMessageRecordService chatMessageRecordService,
            AgentTaskService agentTaskService,
            UserInboxNotificationService userInboxNotificationService,
            ObjectMapper objectMapper) {
        this.chatThreadRecordService = chatThreadRecordService;
        this.chatMessageRecordService = chatMessageRecordService;
        this.agentTaskService = agentTaskService;
        this.userInboxNotificationService = userInboxNotificationService;
        this.objectMapper = objectMapper;
    }

    public ChatThreadListData listThreads(String assistantUid, Integer limit) {
        return new ChatThreadListData(chatThreadRecordService.listByAssistantUid(assistantUid, limit).stream()
                .map(threadRecord -> toThreadSummary(assistantUid, threadRecord))
                .toList());
    }

    public ChatMessageListData listMessages(String assistantUid, String threadId, Integer limit) {
        ChatThreadRecord threadRecord = requireOwnedThread(assistantUid, threadId);
        return new ChatMessageListData(chatMessageRecordService
                .listByThreadId(threadRecord.getThreadId(), threadRecord.getAssistantUid(), limit)
                .stream()
                .map(this::toMessageData)
                .toList());
    }

    public Optional<Map<String, Object>> findThreadStateSnapshot(String assistantUid, String threadId) {
        if (!StringUtils.hasText(threadId)) {
            return Optional.empty();
        }
        ChatThreadRecord threadRecord = chatThreadRecordService.findByThreadId(threadId.trim()).orElse(null);
        if (threadRecord == null) {
            return Optional.empty();
        }
        authorizeOwnedThread(threadRecord, assistantUid);
        List<ChatMessageRecord> messages = chatMessageRecordService.listByThreadId(
                threadRecord.getThreadId(),
                threadRecord.getAssistantUid(),
                200);
        int activeTaskCount = agentTaskService.countActiveByAssistantUidAndThreadId(threadRecord.getAssistantUid(), threadRecord.getThreadId());
        int unreadNotificationCount = userInboxNotificationService.countUnreadByAssistantUidAndThreadId(
                threadRecord.getAssistantUid(), threadRecord.getThreadId());
        Map<String, Object> snapshot = buildThreadSnapshot(threadRecord, messages, activeTaskCount, unreadNotificationCount);
        return snapshot.isEmpty() ? Optional.empty() : Optional.of(snapshot);
    }

    private ChatThreadSummaryData toThreadSummary(String assistantUid, ChatThreadRecord threadRecord) {
        List<ChatMessageRecord> messages = chatMessageRecordService.listByThreadId(
                threadRecord.getThreadId(),
                threadRecord.getAssistantUid(),
                THREAD_SUMMARY_MESSAGE_LIMIT);
        int activeTaskCount = agentTaskService.countActiveByAssistantUidAndThreadId(threadRecord.getAssistantUid(), threadRecord.getThreadId());
        int unreadNotificationCount = userInboxNotificationService.countUnreadByAssistantUidAndThreadId(
                threadRecord.getAssistantUid(), threadRecord.getThreadId());
        Map<String, Object> snapshot = buildThreadSnapshot(threadRecord, messages, activeTaskCount, unreadNotificationCount);
        String status = firstText(snapshot.get("status"), threadRecord.getStatus(), "UNDERSTANDING");
        String phase = firstText(snapshot.get("phase"), threadRecord.getPhase(), "UNDERSTANDING");
        boolean unfinished = Boolean.TRUE.equals(snapshot.getOrDefault("unfinished", threadRecord.getUnfinished()));
        boolean canResume = snapshot.containsKey("canResume")
                ? Boolean.TRUE.equals(snapshot.get("canResume"))
                : ChatThreadActionSupport.canResume(status, phase, unfinished);
        return new ChatThreadSummaryData(
                threadRecord.getThreadId(),
                firstText(threadRecord.getTitle(), threadRecord.getLastMessagePreview(), threadRecord.getLastUserMessage(), "新会话"),
                status,
                phase,
                unfinished,
                canResume,
                firstText(snapshot.get("toolCode"), threadRecord.getToolCode()),
                firstText(snapshot.get("pendingCardType"), threadRecord.getPendingCardType()),
                activeTaskCount,
                unreadNotificationCount,
                firstText(snapshot.get("lastMessage"), threadRecord.getLastMessagePreview(), threadRecord.getLastAssistantMessage(), threadRecord.getLastUserMessage()),
                firstText(threadRecord.getLastEventType(), asText(snapshot.get("pendingCardType"))),
                asText(firstNonNull(threadRecord.getLastMessageAt(), threadRecord.getUpdatedAt(), threadRecord.getCreatedAt())),
                ChatThreadActionSupport.nextAction(threadRecord.getThreadId(), status, phase, unfinished));
    }

    private ChatMessageData toMessageData(ChatMessageRecord messageRecord) {
        Map<String, Object> envelope = readMap(messageRecord.getPayloadJson());
        Map<String, Object> payload = extractMessagePayload(envelope);
        String stage = messageRecord.getStage();
        String status = messageRecord.getStatus();
        if (isFormCard(messageRecord)) {
            payload = normalizeFormPayload(payload, stage, status);
            stage = FrontendFormStateSupport.normalizedPhase(payload, stage, status);
            status = FrontendFormStateSupport.normalizedStatus(payload, stage, status);
            envelope.put("payload", payload);
            envelope.put("stage", stage);
        }
        if (isTaskCard(messageRecord)) {
            payload = normalizeTaskPayload(payload);
            stage = FrontendTaskStateSupport.normalizedPhase(payload);
            status = FrontendTaskStateSupport.normalizedStatus(payload);
            envelope.put("payload", payload);
            envelope.put("stage", stage);
        }
        return new ChatMessageData(
                messageRecord.getMessageId(),
                messageRecord.getThreadId(),
                messageRecord.getTurnId(),
                messageRecord.getMessageType(),
                messageRecord.getEventType(),
                stage,
                status,
                messageRecord.getTitle(),
                messageRecord.getSummaryText(),
                Boolean.TRUE.equals(messageRecord.getCollapsed()),
                messageRecord.getRevisionNo(),
                asText(messageRecord.getCreatedAt()),
                asText(messageRecord.getUpdatedAt()),
                payload,
                extractMessageMeta(envelope));
    }

    private Map<String, Object> buildThreadSnapshot(
            ChatThreadRecord threadRecord,
            List<ChatMessageRecord> messages,
            int activeTaskCount,
            int unreadNotificationCount) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        String status = firstText(threadRecord.getStatus());
        String phase = firstText(threadRecord.getPhase());
        boolean unfinished = Boolean.TRUE.equals(threadRecord.getUnfinished());
        boolean canResume = Boolean.TRUE.equals(threadRecord.getCanResume());
        String toolCode = firstText(threadRecord.getToolCode());
        String pendingCardType = firstText(threadRecord.getPendingCardType());

        ChatMessageRecord latestFormCard = latestByType(messages, "FORM_CARD");
        Map<String, Object> latestFormPayload = latestFormCard != null
                ? normalizeFormPayload(
                        extractMessagePayload(readMap(latestFormCard.getPayloadJson())),
                        latestFormCard.getStage(),
                        latestFormCard.getStatus())
                : Map.of();
        if (!latestFormPayload.isEmpty() && (!StringUtils.hasText(status) || unfinished)) {
            status = FrontendFormStateSupport.normalizedStatus(
                    latestFormPayload,
                    latestFormCard != null ? latestFormCard.getStage() : null,
                    latestFormCard != null ? latestFormCard.getStatus() : null);
            phase = FrontendFormStateSupport.normalizedPhase(
                    latestFormPayload,
                    latestFormCard != null ? latestFormCard.getStage() : null,
                    latestFormCard != null ? latestFormCard.getStatus() : null);
            unfinished = true;
            canResume = true;
            toolCode = firstText(toolCode, latestFormPayload.get("toolCode"));
            pendingCardType = "FORM_CARD";
            snapshot.put("pendingForm", latestFormPayload);
        }

        ChatMessageRecord latestTaskCard = latestByType(messages, "TASK_CARD");
        Map<String, Object> latestTaskPayload = latestTaskCard != null
                ? normalizeTaskPayload(extractMessagePayload(readMap(latestTaskCard.getPayloadJson())))
                : Map.of();
        if (!latestTaskPayload.isEmpty() && (!StringUtils.hasText(status) || unfinished)) {
            String taskStatus = firstText(FrontendTaskStateSupport.normalizedStatus(latestTaskPayload), latestTaskCard.getStatus(), status);
            if (StringUtils.hasText(taskStatus)) {
                status = taskStatus;
            }
            phase = firstText(FrontendTaskStateSupport.normalizedPhase(latestTaskPayload), latestTaskCard.getStage(), phase, "EXECUTING");
            unfinished = !ChatThreadActionSupport.isTerminalStatus(status);
            canResume = ChatThreadActionSupport.canResume(status, phase, unfinished);
            toolCode = firstText(toolCode, latestTaskPayload.get("sourceCode"));
            pendingCardType = "TASK_CARD";
        }

        ChatMessageRecord latestResultCard = latestByType(messages, "RESULT_CARD");
        Map<String, Object> latestResultPayload = latestResultCard != null
                ? extractMessagePayload(readMap(latestResultCard.getPayloadJson()))
                : Map.of();
        if (!latestResultPayload.isEmpty()) {
            snapshot.put("lastResult", latestResultPayload);
            toolCode = firstText(toolCode, latestResultPayload.get("artifactCode"));
            if (!StringUtils.hasText(status) || ChatThreadActionSupport.isTerminalStatus(status)) {
                boolean success = !Boolean.FALSE.equals(latestResultPayload.get("success"))
                        && !StringUtils.hasText(asText(latestResultPayload.get("error")));
                status = success ? "COMPLETED" : "FAILED";
                phase = success ? "DONE" : "ERROR";
                unfinished = false;
                canResume = false;
                pendingCardType = "RESULT_CARD";
            }
        }

        if (!StringUtils.hasText(status) && !messages.isEmpty()) {
            status = unfinished ? "UNDERSTANDING" : "COMPLETED";
            phase = unfinished ? "UNDERSTANDING" : "DONE";
        }

        if (!StringUtils.hasText(status)) {
            return Map.of();
        }

        canResume = ChatThreadActionSupport.canResume(status, phase, unfinished) || canResume;
        snapshot.put("status", status);
        snapshot.put("phase", firstText(phase, "UNDERSTANDING"));
        snapshot.put("unfinished", unfinished);
        snapshot.put("canResume", canResume);
        snapshot.put("toolCode", toolCode);
        snapshot.put("pendingCardType", pendingCardType);
        snapshot.put("activeTaskCount", activeTaskCount);
        snapshot.put("unreadNotificationCount", unreadNotificationCount);
        RoleBindingSnapshot roleBinding = resolveRoleBinding(threadRecord, messages);
        roleBinding.applyTo(snapshot);
        snapshot.put("updatedAt", asText(firstNonNull(threadRecord.getLastMessageAt(), threadRecord.getUpdatedAt(), threadRecord.getCreatedAt())));
        snapshot.put("lastMessage", resolveLastMessagePreview(
                status,
                pendingCardType,
                threadRecord,
                latestFormCard,
                latestTaskCard,
                latestResultCard));
        return snapshot;
    }

    private RoleBindingSnapshot resolveRoleBinding(ChatThreadRecord threadRecord, List<ChatMessageRecord> messages) {
        RoleBindingSnapshot threadBinding = RoleBindingSnapshot.fromThread(threadRecord);
        if (!threadBinding.isEmpty()) {
            return threadBinding;
        }
        if (messages == null || messages.isEmpty()) {
            return RoleBindingSnapshot.empty();
        }
        for (int index = messages.size() - 1; index >= 0; index--) {
            Map<String, Object> envelope = readMap(messages.get(index).getPayloadJson());
            RoleBindingSnapshot envelopeBinding = RoleBindingSnapshot.fromEnvelope(envelope);
            if (!envelopeBinding.isEmpty()) {
                return envelopeBinding;
            }
        }
        return RoleBindingSnapshot.empty();
    }

    private String resolveLastMessagePreview(
            String status,
            String pendingCardType,
            ChatThreadRecord threadRecord,
            ChatMessageRecord latestFormCard,
            ChatMessageRecord latestTaskCard,
            ChatMessageRecord latestResultCard) {
        if ("RESULT_CARD".equalsIgnoreCase(pendingCardType) || ChatThreadActionSupport.isTerminalStatus(status)) {
            return firstText(
                    latestResultCard != null ? latestResultCard.getSummaryText() : null,
                    latestTaskCard != null ? latestTaskCard.getSummaryText() : null,
                    latestFormCard != null ? latestFormCard.getSummaryText() : null,
                    threadRecord != null ? threadRecord.getLastMessagePreview() : null,
                    threadRecord != null ? threadRecord.getLastAssistantMessage() : null,
                    threadRecord != null ? threadRecord.getLastUserMessage() : null);
        }
        if ("TASK_CARD".equalsIgnoreCase(pendingCardType) || "WAITING_APPROVAL".equalsIgnoreCase(status)) {
            return firstText(
                    latestTaskCard != null ? latestTaskCard.getSummaryText() : null,
                    latestFormCard != null ? latestFormCard.getSummaryText() : null,
                    latestResultCard != null ? latestResultCard.getSummaryText() : null,
                    threadRecord != null ? threadRecord.getLastMessagePreview() : null,
                    threadRecord != null ? threadRecord.getLastAssistantMessage() : null,
                    threadRecord != null ? threadRecord.getLastUserMessage() : null);
        }
        if ("FORM_CARD".equalsIgnoreCase(pendingCardType)
                || "WAITING_CONFIRMATION".equalsIgnoreCase(status)
                || "WAITING_INPUT".equalsIgnoreCase(status)) {
            return firstText(
                    latestFormCard != null ? latestFormCard.getSummaryText() : null,
                    latestTaskCard != null ? latestTaskCard.getSummaryText() : null,
                    latestResultCard != null ? latestResultCard.getSummaryText() : null,
                    threadRecord != null ? threadRecord.getLastMessagePreview() : null,
                    threadRecord != null ? threadRecord.getLastAssistantMessage() : null,
                    threadRecord != null ? threadRecord.getLastUserMessage() : null);
        }
        return firstText(
                threadRecord != null ? threadRecord.getLastMessagePreview() : null,
                threadRecord != null ? threadRecord.getLastAssistantMessage() : null,
                threadRecord != null ? threadRecord.getLastUserMessage() : null,
                latestResultCard != null ? latestResultCard.getSummaryText() : null,
                latestTaskCard != null ? latestTaskCard.getSummaryText() : null,
                latestFormCard != null ? latestFormCard.getSummaryText() : null);
    }

    private ChatThreadRecord requireOwnedThread(String assistantUid, String threadId) {
        if (!StringUtils.hasText(threadId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "threadId cannot be null or empty");
        }
        ChatThreadRecord threadRecord = chatThreadRecordService.findByThreadId(threadId.trim())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "chat_thread_not_found"));
        authorizeOwnedThread(threadRecord, assistantUid);
        return threadRecord;
    }

    private void authorizeOwnedThread(ChatThreadRecord threadRecord, String assistantUid) {
        if (threadRecord == null || !StringUtils.hasText(assistantUid) || !StringUtils.hasText(threadRecord.getAssistantUid())) {
            return;
        }
        if (!assistantUid.trim().equals(threadRecord.getAssistantUid().trim())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "chat_thread_scope_denied");
        }
    }

    private ChatMessageRecord latestByType(List<ChatMessageRecord> messages, String messageType) {
        if (messages == null || messages.isEmpty() || !StringUtils.hasText(messageType)) {
            return null;
        }
        for (int index = messages.size() - 1; index >= 0; index--) {
            ChatMessageRecord message = messages.get(index);
            if (message != null && messageType.equalsIgnoreCase(message.getMessageType())) {
                return message;
            }
        }
        return null;
    }

    private boolean isFormCard(ChatMessageRecord messageRecord) {
        if (messageRecord == null) {
            return false;
        }
        return "FORM_CARD".equalsIgnoreCase(messageRecord.getMessageType())
                || "FORM_STATE".equalsIgnoreCase(messageRecord.getEventType());
    }

    private boolean isTaskCard(ChatMessageRecord messageRecord) {
        if (messageRecord == null) {
            return false;
        }
        return "TASK_CARD".equalsIgnoreCase(messageRecord.getMessageType())
                || "TASK_STATE".equalsIgnoreCase(messageRecord.getEventType());
    }

    private Map<String, Object> normalizeFormPayload(Map<String, Object> payload, String stage, String status) {
        if (payload == null || payload.isEmpty()) {
            return Map.of();
        }
        return FrontendFormStateSupport.normalizePayload(payload, stage, status);
    }

    private Map<String, Object> normalizeTaskPayload(Map<String, Object> payload) {
        if (payload == null || payload.isEmpty()) {
            return Map.of();
        }
        return FrontendTaskStateSupport.normalizePayload(payload);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> extractMessagePayload(Map<String, Object> envelope) {
        if (envelope == null || envelope.isEmpty()) {
            return Map.of();
        }
        Object payload = envelope.get("payload");
        if (payload instanceof Map<?, ?> payloadMap) {
            return new LinkedHashMap<>((Map<String, Object>) payloadMap);
        }
        if (envelope.containsKey("text")) {
            return Map.of("text", envelope.get("text"));
        }
        return new LinkedHashMap<>(envelope);
    }

    private Map<String, Object> extractMessageMeta(Map<String, Object> envelope) {
        if (envelope == null || envelope.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> meta = new LinkedHashMap<>(envelope);
        if (meta.containsKey("payload")) {
            meta.remove("payload");
            return meta;
        }
        meta.remove("text");
        return meta;
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
            return Map.of("text", json);
        }
    }

    private Object firstNonNull(Object... values) {
        if (values == null) {
            return null;
        }
        for (Object value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private String asText(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof LocalDateTime timestamp) {
            return timestamp.toString();
        }
        String text = String.valueOf(value).trim();
        return StringUtils.hasText(text) ? text : null;
    }

    private String firstText(Object... values) {
        if (values == null) {
            return null;
        }
        for (Object value : values) {
            String text = asText(value);
            if (StringUtils.hasText(text)) {
                return text;
            }
        }
        return null;
    }

    private record RoleBindingSnapshot(
            String rolePackageCode,
            String rolePackageVersion,
            String roleScenarioCode) {

        static RoleBindingSnapshot fromThread(ChatThreadRecord threadRecord) {
            if (threadRecord == null) {
                return empty();
            }
            return new RoleBindingSnapshot(
                    threadRecord.getRolePackageCode(),
                    threadRecord.getRolePackageVersion(),
                    threadRecord.getRoleScenarioCode());
        }

        static RoleBindingSnapshot fromEnvelope(Map<String, Object> envelope) {
            if (envelope == null || envelope.isEmpty()) {
                return empty();
            }
            return new RoleBindingSnapshot(
                    text(envelope.get("rolePackageCode")),
                    text(envelope.get("rolePackageVersion")),
                    text(envelope.get("roleScenarioCode")));
        }

        static RoleBindingSnapshot empty() {
            return new RoleBindingSnapshot(null, null, null);
        }

        boolean isEmpty() {
            return !StringUtils.hasText(rolePackageCode)
                    && !StringUtils.hasText(rolePackageVersion)
                    && !StringUtils.hasText(roleScenarioCode);
        }

        void applyTo(Map<String, Object> snapshot) {
            if (snapshot == null || isEmpty()) {
                return;
            }
            putIfText(snapshot, "rolePackageCode", rolePackageCode);
            putIfText(snapshot, "rolePackageVersion", rolePackageVersion);
            putIfText(snapshot, "roleScenarioCode", roleScenarioCode);
        }

        private static String text(Object value) {
            if (value == null) {
                return null;
            }
            String text = String.valueOf(value).trim();
            return StringUtils.hasText(text) ? text : null;
        }

        private static void putIfText(Map<String, Object> snapshot, String key, String value) {
            if (StringUtils.hasText(value)) {
                snapshot.put(key, value);
            }
        }
    }
}








