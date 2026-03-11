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
package com.alibaba.assistant.agent.controlplane.interaction;

import com.alibaba.assistant.agent.controlplane.interaction.mapper.InteractionSpecMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Optional;

@Service
public class InteractionSpecService extends ServiceImpl<InteractionSpecMapper, InteractionSpec> {

	private static final String STATUS_ENABLED = "enabled";

	public Optional<InteractionSpec> findLatestEnabledByCode(Long spaceId, String interactionCode) {
		if (spaceId == null || !StringUtils.hasText(interactionCode)) {
			return Optional.empty();
		}

		LambdaQueryWrapper<InteractionSpec> query = new LambdaQueryWrapper<>();
		query.eq(InteractionSpec::getSpaceId, spaceId);
		query.eq(InteractionSpec::getInteractionCode, interactionCode);
		query.eq(InteractionSpec::getStatus, STATUS_ENABLED);
		query.orderByDesc(InteractionSpec::getVersion);
		query.orderByDesc(InteractionSpec::getId);
		return Optional.ofNullable(getOne(query, false));
	}

	public List<InteractionSpec> listEnabledBySpace(Long spaceId) {
		if (spaceId == null) {
			return List.of();
		}
		LambdaQueryWrapper<InteractionSpec> query = new LambdaQueryWrapper<>();
		query.eq(InteractionSpec::getSpaceId, spaceId);
		query.eq(InteractionSpec::getStatus, STATUS_ENABLED);
		query.orderByAsc(InteractionSpec::getInteractionCode);
		query.orderByDesc(InteractionSpec::getVersion);
		query.orderByDesc(InteractionSpec::getId);
		return list(query);
	}

}
