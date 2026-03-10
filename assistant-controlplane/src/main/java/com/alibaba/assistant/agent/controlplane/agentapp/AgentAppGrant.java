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

@TableName("agent_app_grant")
public class AgentAppGrant {

	@TableId(type = IdType.AUTO)
	private Long id;
	private Long agentAppId;
	private String targetType;
	private String targetCode;
	private String grantMode;
	private String constraintsJson;
	private LocalDateTime createdAt;
	private LocalDateTime updatedAt;

	public Long getId() { return id; }
	public void setId(Long id) { this.id = id; }
	public Long getAgentAppId() { return agentAppId; }
	public void setAgentAppId(Long agentAppId) { this.agentAppId = agentAppId; }
	public String getTargetType() { return targetType; }
	public void setTargetType(String targetType) { this.targetType = targetType; }
	public String getTargetCode() { return targetCode; }
	public void setTargetCode(String targetCode) { this.targetCode = targetCode; }
	public String getGrantMode() { return grantMode; }
	public void setGrantMode(String grantMode) { this.grantMode = grantMode; }
	public String getConstraintsJson() { return constraintsJson; }
	public void setConstraintsJson(String constraintsJson) { this.constraintsJson = constraintsJson; }
	public LocalDateTime getCreatedAt() { return createdAt; }
	public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
	public LocalDateTime getUpdatedAt() { return updatedAt; }
	public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }

}
