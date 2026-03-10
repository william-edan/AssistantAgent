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
package com.alibaba.assistant.agent.controlplane.connector;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * Auth profile entity for downstream connector credential strategies.
 *
 * @author Assistant Agent Team
 * @since 1.0.0
 */
@TableName("auth_profile")
public class AuthProfile {

	@TableId(type = IdType.AUTO)
	private Long id;

	private Long spaceId;

	private Long connectorId;

	private String authProfileCode;

	private String authType;

	private String usagePolicy;

	private String tokenEndpoint;

	private String tokenHeaderName;

	private String tokenHeaderPrefix;

	private String audience;

	private String scopesJson;

	private String credentialRef;

	private String refreshPolicyJson;

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

	public String getAuthProfileCode() {
		return authProfileCode;
	}

	public void setAuthProfileCode(String authProfileCode) {
		this.authProfileCode = authProfileCode;
	}

	public String getAuthType() {
		return authType;
	}

	public void setAuthType(String authType) {
		this.authType = authType;
	}

	public String getUsagePolicy() {
		return usagePolicy;
	}

	public void setUsagePolicy(String usagePolicy) {
		this.usagePolicy = usagePolicy;
	}

	public String getTokenEndpoint() {
		return tokenEndpoint;
	}

	public void setTokenEndpoint(String tokenEndpoint) {
		this.tokenEndpoint = tokenEndpoint;
	}

	public String getTokenHeaderName() {
		return tokenHeaderName;
	}

	public void setTokenHeaderName(String tokenHeaderName) {
		this.tokenHeaderName = tokenHeaderName;
	}

	public String getTokenHeaderPrefix() {
		return tokenHeaderPrefix;
	}

	public void setTokenHeaderPrefix(String tokenHeaderPrefix) {
		this.tokenHeaderPrefix = tokenHeaderPrefix;
	}

	public String getAudience() {
		return audience;
	}

	public void setAudience(String audience) {
		this.audience = audience;
	}

	public String getScopesJson() {
		return scopesJson;
	}

	public void setScopesJson(String scopesJson) {
		this.scopesJson = scopesJson;
	}

	public String getCredentialRef() {
		return credentialRef;
	}

	public void setCredentialRef(String credentialRef) {
		this.credentialRef = credentialRef;
	}

	public String getRefreshPolicyJson() {
		return refreshPolicyJson;
	}

	public void setRefreshPolicyJson(String refreshPolicyJson) {
		this.refreshPolicyJson = refreshPolicyJson;
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
