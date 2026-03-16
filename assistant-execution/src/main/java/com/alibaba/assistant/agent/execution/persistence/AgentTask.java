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
 * Persisted user-visible task projected from agent/runtime activity.
 */
@TableName("agent_task")
public class AgentTask {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String taskId;

    private String threadId;

    private String assistantUid;

    private String runId;

    private String taskType;

    private String sourceType;

    private String sourceCode;

    private String title;

    private String status;

    private Integer progressPercent;

    private Boolean collapsible;

    private Boolean background;

    private Boolean detached;

    private Boolean resultReady;

    private String latestOutputJson;

    private String resultPreviewJson;

    private String actionJson;

    private LocalDateTime createdAt;

    private LocalDateTime completedAt;

    private LocalDateTime updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getTaskId() { return taskId; }
    public void setTaskId(String taskId) { this.taskId = taskId; }
    public String getThreadId() { return threadId; }
    public void setThreadId(String threadId) { this.threadId = threadId; }
    public String getAssistantUid() { return assistantUid; }
    public void setAssistantUid(String assistantUid) { this.assistantUid = assistantUid; }
    public String getRunId() { return runId; }
    public void setRunId(String runId) { this.runId = runId; }
    public String getTaskType() { return taskType; }
    public void setTaskType(String taskType) { this.taskType = taskType; }
    public String getSourceType() { return sourceType; }
    public void setSourceType(String sourceType) { this.sourceType = sourceType; }
    public String getSourceCode() { return sourceCode; }
    public void setSourceCode(String sourceCode) { this.sourceCode = sourceCode; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Integer getProgressPercent() { return progressPercent; }
    public void setProgressPercent(Integer progressPercent) { this.progressPercent = progressPercent; }
    public Boolean getCollapsible() { return collapsible; }
    public void setCollapsible(Boolean collapsible) { this.collapsible = collapsible; }
    public Boolean getBackground() { return background; }
    public void setBackground(Boolean background) { this.background = background; }
    public Boolean getDetached() { return detached; }
    public void setDetached(Boolean detached) { this.detached = detached; }
    public Boolean getResultReady() { return resultReady; }
    public void setResultReady(Boolean resultReady) { this.resultReady = resultReady; }
    public String getLatestOutputJson() { return latestOutputJson; }
    public void setLatestOutputJson(String latestOutputJson) { this.latestOutputJson = latestOutputJson; }
    public String getResultPreviewJson() { return resultPreviewJson; }
    public void setResultPreviewJson(String resultPreviewJson) { this.resultPreviewJson = resultPreviewJson; }
    public String getActionJson() { return actionJson; }
    public void setActionJson(String actionJson) { this.actionJson = actionJson; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getCompletedAt() { return completedAt; }
    public void setCompletedAt(LocalDateTime completedAt) { this.completedAt = completedAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
