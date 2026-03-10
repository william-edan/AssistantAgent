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

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.util.StringUtils;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Maps authenticated migration-profile users to Spring Security authorities.
 *
 * @author Assistant Agent Team
 * @since 1.0.0
 */
public final class AuthenticatedUserAuthorityMapper {

	public static final String PERMISSION_CHAT = "assistant:chat";

	public static final String PERMISSION_CONTROLPLANE = "assistant:controlplane";

	public static final String ROLE_CONTROLPLANE_ADMIN = "ROLE_assistant_controlplane_admin";

	public static final String ROLE_SPACE_ADMIN = "ROLE_assistant_space_admin";

	public static final String ROLE_AGENT_APP_ADMIN = "ROLE_assistant_agent_app_admin";

	private static final String ROLE_PREFIX = "ROLE_";

	private AuthenticatedUserAuthorityMapper() {
	}

	/**
	 * Convert migration-profile roles and permissions into Spring Security authorities.
	 *
	 * @param context authenticated user context
	 * @return ordered unique authorities
	 */
	public static List<GrantedAuthority> toAuthorities(AuthenticatedUserContext context) {
		if (context == null) {
			return List.of();
		}
		Set<String> authorityCodes = new LinkedHashSet<>();
		for (String permission : context.permissions()) {
			if (StringUtils.hasText(permission)) {
				authorityCodes.add(permission.trim());
			}
		}
		for (String role : context.roles()) {
			if (StringUtils.hasText(role)) {
				authorityCodes.add(ROLE_PREFIX + role.trim());
			}
		}
		return authorityCodes.stream().map(SimpleGrantedAuthority::new).map(GrantedAuthority.class::cast).toList();
	}

}
