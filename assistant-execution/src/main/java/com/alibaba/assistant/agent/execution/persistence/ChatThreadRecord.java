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
package com.alibaba.assistant.agent.execution.persistence;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * Persisted chat-thread summary for user conversation history.
 */
@TableName("chat_thread")
public class ChatThreadRecord {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String threadId;

    private String assistantUid;

    private String appName;

    private String systemCode;

    private String title;

    private String status;

    private String phase;

    private Boolean unfinished;

    private Boolean canResume;

    private String toolCode;

    private String rolePackageCode;

    private String rolePackageVersion;

    private String roleScenarioCode;

    private String pendingCardType;

    private String lastUserMessage;

    private String lastAssistantMessage;

    private String lastMessagePreview;

    private String lastEventType;

    private LocalDateTime lastMessageAt;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getThreadId() { return threadId; }
    public void setThreadId(String threadId) { this.threadId = threadId; }
    public String getAssistantUid() { return assistantUid; }
    public void setAssistantUid(String assistantUid) { this.assistantUid = assistantUid; }
    public String getAppName() { return appName; }
    public void setAppName(String appName) { this.appName = appName; }
    public String getSystemCode() { return systemCode; }
    public void setSystemCode(String systemCode) { this.systemCode = systemCode; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getPhase() { return phase; }
    public void setPhase(String phase) { this.phase = phase; }
    public Boolean getUnfinished() { return unfinished; }
    public void setUnfinished(Boolean unfinished) { this.unfinished = unfinished; }
    public Boolean getCanResume() { return canResume; }
    public void setCanResume(Boolean canResume) { this.canResume = canResume; }
    public String getToolCode() { return toolCode; }
    public void setToolCode(String toolCode) { this.toolCode = toolCode; }
    public String getRolePackageCode() { return rolePackageCode; }
    public void setRolePackageCode(String rolePackageCode) { this.rolePackageCode = rolePackageCode; }
    public String getRolePackageVersion() { return rolePackageVersion; }
    public void setRolePackageVersion(String rolePackageVersion) { this.rolePackageVersion = rolePackageVersion; }
    public String getRoleScenarioCode() { return roleScenarioCode; }
    public void setRoleScenarioCode(String roleScenarioCode) { this.roleScenarioCode = roleScenarioCode; }
    public String getPendingCardType() { return pendingCardType; }
    public void setPendingCardType(String pendingCardType) { this.pendingCardType = pendingCardType; }
    public String getLastUserMessage() { return lastUserMessage; }
    public void setLastUserMessage(String lastUserMessage) { this.lastUserMessage = lastUserMessage; }
    public String getLastAssistantMessage() { return lastAssistantMessage; }
    public void setLastAssistantMessage(String lastAssistantMessage) { this.lastAssistantMessage = lastAssistantMessage; }
    public String getLastMessagePreview() { return lastMessagePreview; }
    public void setLastMessagePreview(String lastMessagePreview) { this.lastMessagePreview = lastMessagePreview; }
    public String getLastEventType() { return lastEventType; }
    public void setLastEventType(String lastEventType) { this.lastEventType = lastEventType; }
    public LocalDateTime getLastMessageAt() { return lastMessageAt; }
    public void setLastMessageAt(LocalDateTime lastMessageAt) { this.lastMessageAt = lastMessageAt; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
