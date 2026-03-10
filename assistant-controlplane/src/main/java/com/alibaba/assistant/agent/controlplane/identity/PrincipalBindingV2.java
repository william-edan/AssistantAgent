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
package com.alibaba.assistant.agent.controlplane.identity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * Principal binding entity for platform principal to downstream principal mapping.
 *
 * @author Assistant Agent Team
 * @since 1.0.0
 */
@TableName("principal_binding_v2")
public class PrincipalBindingV2 {

	@TableId(type = IdType.AUTO)
	private Long id;

	private Long spaceId;

	private Long connectorId;

	private String platformPrincipalId;

	private String platformPrincipalType;

	private String targetPrincipalType;

	private String targetPrincipalId;

	private String scopeConstraintsJson;

	private Integer priority;

	private String status;

	private LocalDateTime createdAt;

	private LocalDateTime updatedAt;

	public Long getId() {
		return id;
	}

	public void setId(Long id) {
		this.id = id;
	}

	public Long getSpaceId() {
		return spaceId;
	}

	public void setSpaceId(Long spaceId) {
		this.spaceId = spaceId;
	}

	public Long getConnectorId() {
		return connectorId;
	}

	public void setConnectorId(Long connectorId) {
		this.connectorId = connectorId;
	}

	public String getPlatformPrincipalId() {
		return platformPrincipalId;
	}

	public void setPlatformPrincipalId(String platformPrincipalId) {
		this.platformPrincipalId = platformPrincipalId;
	}

	public String getPlatformPrincipalType() {
		return platformPrincipalType;
	}

	public void setPlatformPrincipalType(String platformPrincipalType) {
		this.platformPrincipalType = platformPrincipalType;
	}

	public String getTargetPrincipalType() {
		return targetPrincipalType;
	}

	public void setTargetPrincipalType(String targetPrincipalType) {
		this.targetPrincipalType = targetPrincipalType;
	}

	public String getTargetPrincipalId() {
		return targetPrincipalId;
	}

	public void setTargetPrincipalId(String targetPrincipalId) {
		this.targetPrincipalId = targetPrincipalId;
	}

	public String getScopeConstraintsJson() {
		return scopeConstraintsJson;
	}

	public void setScopeConstraintsJson(String scopeConstraintsJson) {
		this.scopeConstraintsJson = scopeConstraintsJson;
	}

	public Integer getPriority() {
		return priority;
	}

	public void setPriority(Integer priority) {
		this.priority = priority;
	}

	public String getStatus() {
		return status;
	}

	public void setStatus(String status) {
		this.status = status;
	}

	public LocalDateTime getCreatedAt() {
		return createdAt;
	}

	public void setCreatedAt(LocalDateTime createdAt) {
		this.createdAt = createdAt;
	}

	public LocalDateTime getUpdatedAt() {
		return updatedAt;
	}

	public void setUpdatedAt(LocalDateTime updatedAt) {
		this.updatedAt = updatedAt;
	}

}
