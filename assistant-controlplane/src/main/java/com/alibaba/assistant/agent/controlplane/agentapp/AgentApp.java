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
package com.alibaba.assistant.agent.controlplane.agentapp;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("agent_app")
public class AgentApp {

	@TableId(type = IdType.AUTO)
	private Long id;
	private Long spaceId;
	private String agentAppCode;
	private String displayName;
	private String promptPolicyJson;
	private String memoryPolicyJson;
	private String approvalStrategyJson;
	private String status;
	private LocalDateTime createdAt;
	private LocalDateTime updatedAt;

	public Long getId() { return id; }
	public void setId(Long id) { this.id = id; }
	public Long getSpaceId() { return spaceId; }
	public void setSpaceId(Long spaceId) { this.spaceId = spaceId; }
	public String getAgentAppCode() { return agentAppCode; }
	public void setAgentAppCode(String agentAppCode) { this.agentAppCode = agentAppCode; }
	public String getDisplayName() { return displayName; }
	public void setDisplayName(String displayName) { this.displayName = displayName; }
	public String getPromptPolicyJson() { return promptPolicyJson; }
	public void setPromptPolicyJson(String promptPolicyJson) { this.promptPolicyJson = promptPolicyJson; }
	public String getMemoryPolicyJson() { return memoryPolicyJson; }
	public void setMemoryPolicyJson(String memoryPolicyJson) { this.memoryPolicyJson = memoryPolicyJson; }
	public String getApprovalStrategyJson() { return approvalStrategyJson; }
	public void setApprovalStrategyJson(String approvalStrategyJson) { this.approvalStrategyJson = approvalStrategyJson; }
	public String getStatus() { return status; }
	public void setStatus(String status) { this.status = status; }
	public LocalDateTime getCreatedAt() { return createdAt; }
	public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
	public LocalDateTime getUpdatedAt() { return updatedAt; }
	public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }

}
