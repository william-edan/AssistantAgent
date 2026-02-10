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
package com.alibaba.assistant.agent.start.saas.app.service;

import com.alibaba.assistant.agent.start.saas.infrastructure.mybatis.dataobject.ConversationSessionDO;
import com.alibaba.assistant.agent.start.saas.infrastructure.mybatis.mapper.ConversationSessionMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

/**
 * Conversation session persistence service.
 *
 * @author Assistant Agent Team
 * @since 1.0.0
 */
@Service
public class ConversationSessionService {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<Map<String, Object>>() {
    };

    private final ConversationSessionMapper conversationSessionMapper;

    private final ObjectMapper objectMapper;

    public ConversationSessionService(ConversationSessionMapper conversationSessionMapper, ObjectMapper objectMapper) {
        this.conversationSessionMapper = conversationSessionMapper;
        this.objectMapper = objectMapper;
    }

    /**
     * Load existing session or create one when absent.
     *
     * @param tenantId tenant id
     * @param sessionId session id
     * @param capabilityId capability id
     * @param capabilityVersion capability version
     * @return session object
     */
    public ConversationSessionDO loadOrCreate(
            String tenantId, String sessionId, String capabilityId, Integer capabilityVersion) {
        ConversationSessionDO session = conversationSessionMapper.selectOne(Wrappers.lambdaQuery(ConversationSessionDO.class)
                .eq(ConversationSessionDO::getSessionId, sessionId)
                .eq(ConversationSessionDO::getCapabilityId, capabilityId));
        if (session != null) {
            session.setCapabilityVersion(capabilityVersion);
            conversationSessionMapper.updateById(session);
            return session;
        }
        ConversationSessionDO created = new ConversationSessionDO();
        created.setTenantId(tenantId);
        created.setSessionId(sessionId);
        created.setCapabilityId(capabilityId);
        created.setCapabilityVersion(capabilityVersion);
        created.setSlotSnapshotJson("{}");
        created.setSessionStatus("IDLE");
        created.setCreatedBy("system");
        created.setUpdatedBy("system");
        conversationSessionMapper.insert(created);
        return created;
    }

    /**
     * Parse slot snapshot json.
     *
     * @param session session
     * @return snapshot map
     */
    public Map<String, Object> getSlotSnapshot(ConversationSessionDO session) {
        if (session.getSlotSnapshotJson() == null || session.getSlotSnapshotJson().isBlank()) {
            return new HashMap<>();
        }
        try {
            return objectMapper.readValue(session.getSlotSnapshotJson(), MAP_TYPE);
        }
        catch (JsonProcessingException ex) {
            return new HashMap<>();
        }
    }

    /**
     * Mark session as collecting and save current snapshot.
     *
     * @param session session
     * @param slotSnapshot slot snapshot
     * @param errorCode error code
     * @param errorMessage error message
     */
    public void markCollecting(ConversationSessionDO session, Map<String, Object> slotSnapshot, String errorCode,
            String errorMessage) {
        session.setSlotSnapshotJson(toJsonSafe(slotSnapshot));
        session.setSessionStatus("COLLECTING");
        session.setLastErrorCode(errorCode);
        session.setLastErrorMessage(errorMessage);
        conversationSessionMapper.updateById(session);
    }

    /**
     * Mark session as done and save final slot snapshot.
     *
     * @param session session
     * @param slotSnapshot slot snapshot
     */
    public void markDone(ConversationSessionDO session, Map<String, Object> slotSnapshot) {
        session.setSlotSnapshotJson(toJsonSafe(slotSnapshot));
        session.setSessionStatus("DONE");
        session.setLastErrorCode(null);
        session.setLastErrorMessage(null);
        conversationSessionMapper.updateById(session);
    }

    /**
     * Mark session as failed.
     *
     * @param session session
     * @param slotSnapshot slot snapshot
     * @param errorCode error code
     * @param errorMessage error message
     */
    public void markFailed(ConversationSessionDO session, Map<String, Object> slotSnapshot, String errorCode,
            String errorMessage) {
        session.setSlotSnapshotJson(toJsonSafe(slotSnapshot));
        session.setSessionStatus("FAILED");
        session.setLastErrorCode(errorCode);
        session.setLastErrorMessage(errorMessage);
        conversationSessionMapper.updateById(session);
    }

    private String toJsonSafe(Object value) {
        try {
            return objectMapper.writeValueAsString(value == null ? Map.of() : value);
        }
        catch (JsonProcessingException ex) {
            return "{}";
        }
    }
}
