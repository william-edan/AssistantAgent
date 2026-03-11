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

import com.alibaba.assistant.agent.controlplane.query.mapper.ReferenceResolverMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Optional;

@Service
public class ReferenceResolverService extends ServiceImpl<ReferenceResolverMapper, ReferenceResolver> {

	private static final String STATUS_ENABLED = "enabled";

	public Optional<ReferenceResolver> findLatestEnabledByCode(Long spaceId, String resolverCode) {
		if (spaceId == null || !StringUtils.hasText(resolverCode)) {
			return Optional.empty();
		}

		LambdaQueryWrapper<ReferenceResolver> query = new LambdaQueryWrapper<>();
		query.eq(ReferenceResolver::getSpaceId, spaceId);
		query.eq(ReferenceResolver::getResolverCode, resolverCode);
		query.eq(ReferenceResolver::getStatus, STATUS_ENABLED);
		query.orderByDesc(ReferenceResolver::getVersion);
		query.orderByDesc(ReferenceResolver::getId);
		return Optional.ofNullable(getOne(query, false));
	}

	public List<ReferenceResolver> listEnabledByConnector(Long connectorId) {
		if (connectorId == null) {
			return List.of();
		}
		LambdaQueryWrapper<ReferenceResolver> query = new LambdaQueryWrapper<>();
		query.eq(ReferenceResolver::getConnectorId, connectorId);
		query.eq(ReferenceResolver::getStatus, STATUS_ENABLED);
		query.orderByAsc(ReferenceResolver::getResolverCode);
		query.orderByDesc(ReferenceResolver::getVersion);
		query.orderByDesc(ReferenceResolver::getId);
		return list(query);
	}

}
