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

import com.alibaba.assistant.agent.controlplane.identity.mapper.PrincipalBindingV2Mapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Optional;

/**
 * Service for principal_binding_v2 lookup operations.
 *
 * @author Assistant Agent Team
 * @since 1.0.0
 */
@Service
public class PrincipalBindingV2Service extends ServiceImpl<PrincipalBindingV2Mapper, PrincipalBindingV2> {

	private static final String STATUS_ACTIVE = "active";

	/**
	 * Find the highest-priority active binding for the given platform principal.
	 *
	 * @param spaceId space id
	 * @param connectorId connector id
	 * @param platformPrincipalId platform principal id
	 * @return best matching active binding
	 */
	public Optional<PrincipalBindingV2> findHighestPriorityActiveBinding(
			Long spaceId, Long connectorId, String platformPrincipalId) {
		if (spaceId == null || connectorId == null || !StringUtils.hasText(platformPrincipalId)) {
			return Optional.empty();
		}

		LambdaQueryWrapper<PrincipalBindingV2> query = new LambdaQueryWrapper<>();
		query.eq(PrincipalBindingV2::getSpaceId, spaceId);
		query.eq(PrincipalBindingV2::getConnectorId, connectorId);
		query.eq(PrincipalBindingV2::getPlatformPrincipalId, platformPrincipalId);
		query.eq(PrincipalBindingV2::getStatus, STATUS_ACTIVE);
		query.orderByAsc(PrincipalBindingV2::getPriority);
		query.orderByAsc(PrincipalBindingV2::getId);
		return Optional.ofNullable(getOne(query, false));
	}

}
