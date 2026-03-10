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

import com.alibaba.assistant.agent.controlplane.identity.mapper.LocalUserGrantMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Collections;
import java.util.List;

/**
 * Service for resolving local roles and permissions in migration profile.
 *
 * @author Assistant Agent Team
 * @since 1.0.0
 */
@Service
public class LocalUserGrantService extends ServiceImpl<LocalUserGrantMapper, LocalUserGrant> {

	public static final String STATUS_ACTIVE = "active";

	public static final String GRANT_TYPE_ROLE = "role";

	public static final String GRANT_TYPE_PERMISSION = "permission";

	public static final String SCOPE_TYPE_GLOBAL = "global";

	public static final String SCOPE_CODE_GLOBAL = "*";

	/**
	 * Resolve active role codes for a local user.
	 *
	 * @param localUserId local user id
	 * @return ordered unique role codes
	 */
	public List<String> findRoles(Long localUserId) {
		return findGrantCodes(localUserId, GRANT_TYPE_ROLE);
	}

	/**
	 * Resolve active permission codes for a local user.
	 *
	 * @param localUserId local user id
	 * @return ordered unique permission codes
	 */
	public List<String> findPermissions(Long localUserId) {
		return findGrantCodes(localUserId, GRANT_TYPE_PERMISSION);
	}

	/**
	 * Whether the user holds an active global grant.
	 *
	 * @param localUserId local user id
	 * @param grantType role or permission
	 * @param grantCode grant code
	 * @return true when the grant exists
	 */
	public boolean hasGrant(Long localUserId, String grantType, String grantCode) {
		return hasGrant(localUserId, grantType, grantCode, SCOPE_TYPE_GLOBAL, SCOPE_CODE_GLOBAL);
	}

	/**
	 * Whether the user holds an active scoped grant.
	 *
	 * @param localUserId local user id
	 * @param grantType role or permission
	 * @param grantCode grant code
	 * @param scopeType scope type
	 * @param scopeCode scope code
	 * @return true when the grant exists
	 */
	public boolean hasGrant(Long localUserId, String grantType, String grantCode, String scopeType, String scopeCode) {
		if (localUserId == null || !StringUtils.hasText(grantType) || !StringUtils.hasText(grantCode)) {
			return false;
		}
		LambdaQueryWrapper<LocalUserGrant> query = new LambdaQueryWrapper<>();
		query.eq(LocalUserGrant::getLocalUserId, localUserId);
		query.eq(LocalUserGrant::getGrantType, grantType.trim());
		query.eq(LocalUserGrant::getGrantCode, grantCode.trim());
		query.eq(LocalUserGrant::getScopeType, normalizeScopeType(scopeType));
		query.eq(LocalUserGrant::getScopeCode, normalizeScopeCode(scopeCode));
		query.eq(LocalUserGrant::getStatus, STATUS_ACTIVE);
		return count(query) > 0;
	}

	private List<String> findGrantCodes(Long localUserId, String grantType) {
		if (localUserId == null || !StringUtils.hasText(grantType)) {
			return Collections.emptyList();
		}

		LambdaQueryWrapper<LocalUserGrant> query = new LambdaQueryWrapper<>();
		query.eq(LocalUserGrant::getLocalUserId, localUserId);
		query.eq(LocalUserGrant::getGrantType, grantType);
		query.eq(LocalUserGrant::getStatus, STATUS_ACTIVE);
		query.orderByAsc(LocalUserGrant::getId);
		return list(query).stream()
				.filter(row -> row != null && STATUS_ACTIVE.equalsIgnoreCase(row.getStatus()))
				.filter(row -> grantType.equalsIgnoreCase(trimToEmpty(row.getGrantType())))
				.map(LocalUserGrant::getGrantCode)
				.filter(StringUtils::hasText)
				.map(String::trim)
				.distinct()
				.toList();
	}

	private String normalizeScopeType(String scopeType) {
		return StringUtils.hasText(scopeType) ? scopeType.trim().toLowerCase() : SCOPE_TYPE_GLOBAL;
	}

	private String normalizeScopeCode(String scopeCode) {
		return StringUtils.hasText(scopeCode) ? scopeCode.trim() : SCOPE_CODE_GLOBAL;
	}

	private String trimToEmpty(String value) {
		return value == null ? "" : value.trim();
	}

}
