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
package com.alibaba.assistant.agent.api.security;

import java.util.List;

/**
 * Authenticated user context resolved from current system token introspection.
 *
 * @author Assistant Agent Team
 * @since 1.0.0
 */
public class AuthenticatedUserContext {

	private final String userId;

	private final Long tenantId;

	private final String systemCode;

	private final String clientId;

	private final String accessToken;

	private final String username;

	private final String displayName;

	private final List<String> roles;

	private final List<String> permissions;

	public AuthenticatedUserContext(String userId, Long tenantId, String systemCode, String clientId, String accessToken) {
		this(userId, tenantId, systemCode, clientId, accessToken, null, null, List.of(), List.of());
	}

	public AuthenticatedUserContext(
			String userId,
			Long tenantId,
			String systemCode,
			String clientId,
			String accessToken,
			String username,
			String displayName,
			List<String> roles,
			List<String> permissions) {
		this.userId = userId;
		this.tenantId = tenantId;
		this.systemCode = systemCode;
		this.clientId = clientId;
		this.accessToken = accessToken;
		this.username = username;
		this.displayName = displayName;
		this.roles = roles == null ? List.of() : List.copyOf(roles);
		this.permissions = permissions == null ? List.of() : List.copyOf(permissions);
	}

	public String userId() {
		return userId;
	}

	public Long tenantId() {
		return tenantId;
	}

	public String systemCode() {
		return systemCode;
	}

	public String clientId() {
		return clientId;
	}

	public String accessToken() {
		return accessToken;
	}

	public String username() {
		return username;
	}

	public String displayName() {
		return displayName;
	}

	public List<String> roles() {
		return roles;
	}

	public List<String> permissions() {
		return permissions;
	}

}
