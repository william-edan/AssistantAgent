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
package com.alibaba.assistant.agent.controlplane.toolregistry;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * Tool metadata entity mapped to the tool_meta table.
 *
 * @author Assistant Agent Team
 * @since 1.0.0
 */
@TableName("tool_meta")
public class ToolMeta {

	@TableId(type = IdType.AUTO)
	private Long id;

	private String tenantId;

	private String toolCode;

	private String toolName;

	private String description;

	private String systemCode;

	private String apiEndpoint;

	private String httpMethod;

	private String contentType;

	private String parameterSchema;

	private String executionPlan;

	private String interactionPolicy;

	private String riskLevel;

	private Boolean requiresAuth;

	private Boolean requiresConfirm;

	private String capabilityType;

	private Integer version;

	private String status;

	private LocalDateTime createdAt;

	private LocalDateTime updatedAt;

	public Long getId() {
		return id;
	}

	public void setId(Long id) {
		this.id = id;
	}

	public String getTenantId() {
		return tenantId;
	}

	public void setTenantId(String tenantId) {
		this.tenantId = tenantId;
	}

	public String getToolCode() {
		return toolCode;
	}

	public void setToolCode(String toolCode) {
		this.toolCode = toolCode;
	}

	public String getToolName() {
		return toolName;
	}

	public void setToolName(String toolName) {
		this.toolName = toolName;
	}

	public String getDescription() {
		return description;
	}

	public void setDescription(String description) {
		this.description = description;
	}

	public String getSystemCode() {
		return systemCode;
	}

	public void setSystemCode(String systemCode) {
		this.systemCode = systemCode;
	}

	public String getApiEndpoint() {
		return apiEndpoint;
	}

	public void setApiEndpoint(String apiEndpoint) {
		this.apiEndpoint = apiEndpoint;
	}

	public String getHttpMethod() {
		return httpMethod;
	}

	public void setHttpMethod(String httpMethod) {
		this.httpMethod = httpMethod;
	}

	public String getContentType() {
		return contentType;
	}

	public void setContentType(String contentType) {
		this.contentType = contentType;
	}

	public String getParameterSchema() {
		return parameterSchema;
	}

	public void setParameterSchema(String parameterSchema) {
		this.parameterSchema = parameterSchema;
	}

	public String getExecutionPlan() {
		return executionPlan;
	}

	public void setExecutionPlan(String executionPlan) {
		this.executionPlan = executionPlan;
	}

	public String getInteractionPolicy() {
		return interactionPolicy;
	}

	public void setInteractionPolicy(String interactionPolicy) {
		this.interactionPolicy = interactionPolicy;
	}

	public String getRiskLevel() {
		return riskLevel;
	}

	public void setRiskLevel(String riskLevel) {
		this.riskLevel = riskLevel;
	}

	public Boolean getRequiresAuth() {
		return requiresAuth;
	}

	public void setRequiresAuth(Boolean requiresAuth) {
		this.requiresAuth = requiresAuth;
	}

	public Boolean getRequiresConfirm() {
		return requiresConfirm;
	}

	public void setRequiresConfirm(Boolean requiresConfirm) {
		this.requiresConfirm = requiresConfirm;
	}

	public String getCapabilityType() {
		return capabilityType;
	}

	public void setCapabilityType(String capabilityType) {
		this.capabilityType = capabilityType;
	}

	public Integer getVersion() {
		return version;
	}

	public void setVersion(Integer version) {
		this.version = version;
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
