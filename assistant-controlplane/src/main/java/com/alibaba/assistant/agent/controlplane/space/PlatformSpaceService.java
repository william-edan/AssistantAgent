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
package com.alibaba.assistant.agent.controlplane.space;

import com.alibaba.assistant.agent.controlplane.space.mapper.PlatformSpaceMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Optional;

/**
 * Service for platform_space lookup operations.
 *
 * @author Assistant Agent Team
 * @since 1.0.0
 */
@Service
public class PlatformSpaceService extends ServiceImpl<PlatformSpaceMapper, PlatformSpace> {

	private static final String STATUS_ACTIVE = "active";

	private static final String DEFAULT_ENVIRONMENT = "prod";

	/**
	 * Find active space by code and environment.
	 *
	 * @param spaceCode space code
	 * @param environment target environment, defaults to prod
	 * @return latest active matching row
	 */
	public Optional<PlatformSpace> findActiveByCode(String spaceCode, String environment) {
		if (!StringUtils.hasText(spaceCode)) {
			return Optional.empty();
		}

		LambdaQueryWrapper<PlatformSpace> query = new LambdaQueryWrapper<>();
		query.eq(PlatformSpace::getSpaceCode, spaceCode);
		query.eq(PlatformSpace::getEnvironment, normalizeEnvironment(environment));
		query.eq(PlatformSpace::getStatus, STATUS_ACTIVE);
		query.orderByDesc(PlatformSpace::getId);
		return Optional.ofNullable(getOne(query, false));
	}

	String normalizeEnvironment(String environment) {
		return StringUtils.hasText(environment) ? environment : DEFAULT_ENVIRONMENT;
	}

}
