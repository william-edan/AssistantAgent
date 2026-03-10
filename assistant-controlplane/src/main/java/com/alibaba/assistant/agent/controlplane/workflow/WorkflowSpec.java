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
package com.alibaba.assistant.agent.controlplane.workflow;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("workflow_spec")
public class WorkflowSpec {

	@TableId(type = IdType.AUTO)
	private Long id;
	private Long spaceId;
	private String workflowCode;
	private String displayName;
	private Long interactionSpecId;
	private String riskAggregationPolicy;
	private String approvalAggregationPolicy;
	private String failurePolicyJson;
	private String auditPolicyJson;
	private String status;
	private Integer version;
	private String publishedArtifactRef;
	private LocalDateTime createdAt;
	private LocalDateTime updatedAt;

	public Long getId() { return id; }
	public void setId(Long id) { this.id = id; }
	public Long getSpaceId() { return spaceId; }
	public void setSpaceId(Long spaceId) { this.spaceId = spaceId; }
	public String getWorkflowCode() { return workflowCode; }
	public void setWorkflowCode(String workflowCode) { this.workflowCode = workflowCode; }
	public String getDisplayName() { return displayName; }
	public void setDisplayName(String displayName) { this.displayName = displayName; }
	public Long getInteractionSpecId() { return interactionSpecId; }
	public void setInteractionSpecId(Long interactionSpecId) { this.interactionSpecId = interactionSpecId; }
	public String getRiskAggregationPolicy() { return riskAggregationPolicy; }
	public void setRiskAggregationPolicy(String riskAggregationPolicy) { this.riskAggregationPolicy = riskAggregationPolicy; }
	public String getApprovalAggregationPolicy() { return approvalAggregationPolicy; }
	public void setApprovalAggregationPolicy(String approvalAggregationPolicy) { this.approvalAggregationPolicy = approvalAggregationPolicy; }
	public String getFailurePolicyJson() { return failurePolicyJson; }
	public void setFailurePolicyJson(String failurePolicyJson) { this.failurePolicyJson = failurePolicyJson; }
	public String getAuditPolicyJson() { return auditPolicyJson; }
	public void setAuditPolicyJson(String auditPolicyJson) { this.auditPolicyJson = auditPolicyJson; }
	public String getStatus() { return status; }
	public void setStatus(String status) { this.status = status; }
	public Integer getVersion() { return version; }
	public void setVersion(Integer version) { this.version = version; }
	public String getPublishedArtifactRef() { return publishedArtifactRef; }
	public void setPublishedArtifactRef(String publishedArtifactRef) { this.publishedArtifactRef = publishedArtifactRef; }
	public LocalDateTime getCreatedAt() { return createdAt; }
	public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
	public LocalDateTime getUpdatedAt() { return updatedAt; }
	public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }

}
