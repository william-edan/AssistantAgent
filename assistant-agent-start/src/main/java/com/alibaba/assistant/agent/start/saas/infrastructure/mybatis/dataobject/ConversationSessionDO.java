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
package com.alibaba.assistant.agent.start.saas.infrastructure.mybatis.dataobject;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * Conversation session state for slot collection.
 *
 * @author Assistant Agent Team
 * @since 1.0.0
 */
@TableName("assistant_conversation_session")
public class ConversationSessionDO extends BaseTenantDO {

    @TableField("session_id")
    private String sessionId;

    @TableField("capability_id")
    private String capabilityId;

    @TableField("capability_version")
    private Integer capabilityVersion;

    @TableField("slot_snapshot_json")
    private String slotSnapshotJson;

    @TableField("session_status")
    private String sessionStatus;

    @TableField("last_error_code")
    private String lastErrorCode;

    @TableField("last_error_message")
    private String lastErrorMessage;

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public String getCapabilityId() {
        return capabilityId;
    }

    public void setCapabilityId(String capabilityId) {
        this.capabilityId = capabilityId;
    }

    public Integer getCapabilityVersion() {
        return capabilityVersion;
    }

    public void setCapabilityVersion(Integer capabilityVersion) {
        this.capabilityVersion = capabilityVersion;
    }

    public String getSlotSnapshotJson() {
        return slotSnapshotJson;
    }

    public void setSlotSnapshotJson(String slotSnapshotJson) {
        this.slotSnapshotJson = slotSnapshotJson;
    }

    public String getSessionStatus() {
        return sessionStatus;
    }

    public void setSessionStatus(String sessionStatus) {
        this.sessionStatus = sessionStatus;
    }

    public String getLastErrorCode() {
        return lastErrorCode;
    }

    public void setLastErrorCode(String lastErrorCode) {
        this.lastErrorCode = lastErrorCode;
    }

    public String getLastErrorMessage() {
        return lastErrorMessage;
    }

    public void setLastErrorMessage(String lastErrorMessage) {
        this.lastErrorMessage = lastErrorMessage;
    }
}
