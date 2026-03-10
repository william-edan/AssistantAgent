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

@TableName("workflow_step")
public class WorkflowStep {

	@TableId(type = IdType.AUTO)
	private Long id;
	private Long workflowId;
	private String stepId;
	private String stepName;
	private String stepType;
	private Long connectorId;
	private String targetRef;
	private String allowedAuthProfilesJson;
	private String bindingStrategiesJson;
	private String inputMappingJson;
	private String outputMappingJson;
	private String dependsOnJson;
	private String conditionJson;
	private String joinPolicyJson;
	private String retryPolicyJson;
	private String timeoutPolicyJson;
	private String approvalGateJson;
	private String compensationTargetRef;
	private String resumePolicyJson;
	private Integer stepOrder;
	private String status;
	private LocalDateTime createdAt;
	private LocalDateTime updatedAt;

	public Long getId() { return id; }
	public void setId(Long id) { this.id = id; }
	public Long getWorkflowId() { return workflowId; }
	public void setWorkflowId(Long workflowId) { this.workflowId = workflowId; }
	public String getStepId() { return stepId; }
	public void setStepId(String stepId) { this.stepId = stepId; }
	public String getStepName() { return stepName; }
	public void setStepName(String stepName) { this.stepName = stepName; }
	public String getStepType() { return stepType; }
	public void setStepType(String stepType) { this.stepType = stepType; }
	public Long getConnectorId() { return connectorId; }
	public void setConnectorId(Long connectorId) { this.connectorId = connectorId; }
	public String getTargetRef() { return targetRef; }
	public void setTargetRef(String targetRef) { this.targetRef = targetRef; }
	public String getAllowedAuthProfilesJson() { return allowedAuthProfilesJson; }
	public void setAllowedAuthProfilesJson(String allowedAuthProfilesJson) { this.allowedAuthProfilesJson = allowedAuthProfilesJson; }
	public String getBindingStrategiesJson() { return bindingStrategiesJson; }
	public void setBindingStrategiesJson(String bindingStrategiesJson) { this.bindingStrategiesJson = bindingStrategiesJson; }
	public String getInputMappingJson() { return inputMappingJson; }
	public void setInputMappingJson(String inputMappingJson) { this.inputMappingJson = inputMappingJson; }
	public String getOutputMappingJson() { return outputMappingJson; }
	public void setOutputMappingJson(String outputMappingJson) { this.outputMappingJson = outputMappingJson; }
	public String getDependsOnJson() { return dependsOnJson; }
	public void setDependsOnJson(String dependsOnJson) { this.dependsOnJson = dependsOnJson; }
	public String getConditionJson() { return conditionJson; }
	public void setConditionJson(String conditionJson) { this.conditionJson = conditionJson; }
	public String getJoinPolicyJson() { return joinPolicyJson; }
	public void setJoinPolicyJson(String joinPolicyJson) { this.joinPolicyJson = joinPolicyJson; }
	public String getRetryPolicyJson() { return retryPolicyJson; }
	public void setRetryPolicyJson(String retryPolicyJson) { this.retryPolicyJson = retryPolicyJson; }
	public String getTimeoutPolicyJson() { return timeoutPolicyJson; }
	public void setTimeoutPolicyJson(String timeoutPolicyJson) { this.timeoutPolicyJson = timeoutPolicyJson; }
	public String getApprovalGateJson() { return approvalGateJson; }
	public void setApprovalGateJson(String approvalGateJson) { this.approvalGateJson = approvalGateJson; }
	public String getCompensationTargetRef() { return compensationTargetRef; }
	public void setCompensationTargetRef(String compensationTargetRef) { this.compensationTargetRef = compensationTargetRef; }
	public String getResumePolicyJson() { return resumePolicyJson; }
	public void setResumePolicyJson(String resumePolicyJson) { this.resumePolicyJson = resumePolicyJson; }
	public Integer getStepOrder() { return stepOrder; }
	public void setStepOrder(Integer stepOrder) { this.stepOrder = stepOrder; }
	public String getStatus() { return status; }
	public void setStatus(String status) { this.status = status; }
	public LocalDateTime getCreatedAt() { return createdAt; }
	public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
	public LocalDateTime getUpdatedAt() { return updatedAt; }
	public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }

}
