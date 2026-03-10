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

import com.alibaba.assistant.agent.controlplane.connector.mapper.AuthProfileMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;

/**
 * Service for auth_profile lookup operations.
 *
 * @author Assistant Agent Team
 * @since 1.0.0
 */
@Service
public class AuthProfileService extends ServiceImpl<AuthProfileMapper, AuthProfile> {

	private static final String STATUS_ACTIVE = "active";

	/**
	 * List active auth profiles for a connector.
	 *
	 * @param connectorId connector id
	 * @return active auth profiles ordered by id
	 */
	public List<AuthProfile> listActiveByConnector(Long connectorId) {
		if (connectorId == null) {
			return Collections.emptyList();
		}

		LambdaQueryWrapper<AuthProfile> query = new LambdaQueryWrapper<>();
		query.eq(AuthProfile::getConnectorId, connectorId);
		query.eq(AuthProfile::getStatus, STATUS_ACTIVE);
		query.orderByAsc(AuthProfile::getId);
		return list(query);
	}

}
