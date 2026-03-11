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
package com.alibaba.assistant.agent.controlplane.query;

import com.alibaba.assistant.agent.controlplane.query.mapper.PreconditionCheckMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Optional;

@Service
public class PreconditionCheckService extends ServiceImpl<PreconditionCheckMapper, PreconditionCheck> {

	private static final String STATUS_ENABLED = "enabled";

	public Optional<PreconditionCheck> findLatestEnabledByCode(Long spaceId, String checkCode) {
		if (spaceId == null || !StringUtils.hasText(checkCode)) {
			return Optional.empty();
		}

		LambdaQueryWrapper<PreconditionCheck> query = new LambdaQueryWrapper<>();
		query.eq(PreconditionCheck::getSpaceId, spaceId);
		query.eq(PreconditionCheck::getCheckCode, checkCode);
		query.eq(PreconditionCheck::getStatus, STATUS_ENABLED);
		query.orderByDesc(PreconditionCheck::getVersion);
		query.orderByDesc(PreconditionCheck::getId);
		return Optional.ofNullable(getOne(query, false));
	}

	public List<PreconditionCheck> listEnabledByConnector(Long connectorId) {
		if (connectorId == null) {
			return List.of();
		}
		LambdaQueryWrapper<PreconditionCheck> query = new LambdaQueryWrapper<>();
		query.eq(PreconditionCheck::getConnectorId, connectorId);
		query.eq(PreconditionCheck::getStatus, STATUS_ENABLED);
		query.orderByAsc(PreconditionCheck::getCheckCode);
		query.orderByDesc(PreconditionCheck::getVersion);
		query.orderByDesc(PreconditionCheck::getId);
		return list(query);
	}

}
