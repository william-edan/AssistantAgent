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
 * Execution run persistence entity.
 */
@TableName("execution_run")
public class ExecutionRun {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String runId;

    private String artifactCode;

    private String artifactType;

    private Long spaceId;

    private String platformPrincipalId;

    private String threadId;

    private String contextSnapshotJson;

    private String status;

    private String pausedStepId;

    private String approvalRequestId;

    private LocalDateTime startedAt;

    private LocalDateTime completedAt;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getRunId() { return runId; }
    public void setRunId(String runId) { this.runId = runId; }
    public String getArtifactCode() { return artifactCode; }
    public void setArtifactCode(String artifactCode) { this.artifactCode = artifactCode; }
    public String getArtifactType() { return artifactType; }
    public void setArtifactType(String artifactType) { this.artifactType = artifactType; }
    public Long getSpaceId() { return spaceId; }
    public void setSpaceId(Long spaceId) { this.spaceId = spaceId; }
    public String getPlatformPrincipalId() { return platformPrincipalId; }
    public void setPlatformPrincipalId(String platformPrincipalId) { this.platformPrincipalId = platformPrincipalId; }
    public String getThreadId() { return threadId; }
    public void setThreadId(String threadId) { this.threadId = threadId; }
    public String getContextSnapshotJson() { return contextSnapshotJson; }
    public void setContextSnapshotJson(String contextSnapshotJson) { this.contextSnapshotJson = contextSnapshotJson; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getPausedStepId() { return pausedStepId; }
    public void setPausedStepId(String pausedStepId) { this.pausedStepId = pausedStepId; }
    public String getApprovalRequestId() { return approvalRequestId; }
    public void setApprovalRequestId(String approvalRequestId) { this.approvalRequestId = approvalRequestId; }
    public LocalDateTime getStartedAt() { return startedAt; }
    public void setStartedAt(LocalDateTime startedAt) { this.startedAt = startedAt; }
    public LocalDateTime getCompletedAt() { return completedAt; }
    public void setCompletedAt(LocalDateTime completedAt) { this.completedAt = completedAt; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
