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

import com.alibaba.assistant.agent.controlplane.query.mapper.BusinessQueryActionMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Optional;

@Service
public class BusinessQueryActionService extends ServiceImpl<BusinessQueryActionMapper, BusinessQueryAction> {

	private static final String STATUS_ENABLED = "enabled";

	public Optional<BusinessQueryAction> findLatestEnabledByCode(Long spaceId, String queryActionCode) {
		if (spaceId == null || !StringUtils.hasText(queryActionCode)) {
			return Optional.empty();
		}

		LambdaQueryWrapper<BusinessQueryAction> query = new LambdaQueryWrapper<>();
		query.eq(BusinessQueryAction::getSpaceId, spaceId);
		query.eq(BusinessQueryAction::getQueryActionCode, queryActionCode);
		query.eq(BusinessQueryAction::getStatus, STATUS_ENABLED);
		query.orderByDesc(BusinessQueryAction::getVersion);
		query.orderByDesc(BusinessQueryAction::getId);
		return Optional.ofNullable(getOne(query, false));
	}

	public List<BusinessQueryAction> listEnabledByConnector(Long connectorId) {
		if (connectorId == null) {
			return List.of();
		}
		LambdaQueryWrapper<BusinessQueryAction> query = new LambdaQueryWrapper<>();
		query.eq(BusinessQueryAction::getConnectorId, connectorId);
		query.eq(BusinessQueryAction::getStatus, STATUS_ENABLED);
		query.orderByAsc(BusinessQueryAction::getQueryActionCode);
		query.orderByDesc(BusinessQueryAction::getVersion);
		query.orderByDesc(BusinessQueryAction::getId);
		return list(query);
	}

}
