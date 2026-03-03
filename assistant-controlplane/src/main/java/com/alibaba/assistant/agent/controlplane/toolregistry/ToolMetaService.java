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
package com.alibaba.assistant.agent.controlplane.toolregistry;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.alibaba.assistant.agent.controlplane.toolregistry.mapper.ToolMetaMapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Optional;

/**
 * Service for tool_meta CRUD operations.
 *
 * @author Assistant Agent Team
 * @since 1.0.0
 */
@Service
public class ToolMetaService extends ServiceImpl<ToolMetaMapper, ToolMeta> {

	private static final String STATUS_ENABLED = "enabled";

	private static final String DEFAULT_TENANT = "default";

	/**
	 * List enabled tool metadata for tenant.
	 * Includes historical rows with null status for backward compatibility.
	 *
	 * @param tenantId tenant id, blank means default tenant
	 * @return enabled tool list ordered by id asc
	 */
	public List<ToolMeta> listEnabledByTenant(String tenantId) {
		return list(buildEnabledQuery(tenantId, null));
	}

	/**
	 * List enabled tool metadata for tenant and system.
	 *
	 * @param tenantId tenant id, blank means default tenant
	 * @param systemCode optional system code
	 * @return enabled tool list ordered by id asc
	 */
	public List<ToolMeta> listEnabledByTenantAndSystem(String tenantId, String systemCode) {
		return list(buildEnabledQuery(tenantId, systemCode));
	}

	/**
	 * Find latest enabled version of specific tool code.
	 *
	 * @param tenantId tenant id, blank means default tenant
	 * @param toolCode tool code
	 * @return latest enabled record if exists
	 */
	public Optional<ToolMeta> findLatestEnabledByToolCode(String tenantId, String toolCode) {
		if (!StringUtils.hasText(toolCode)) {
			return Optional.empty();
		}

		LambdaQueryWrapper<ToolMeta> query = new LambdaQueryWrapper<>();
		query.eq(ToolMeta::getTenantId, normalizeTenant(tenantId));
		query.eq(ToolMeta::getToolCode, toolCode);
		query.and(wrapper -> wrapper.isNull(ToolMeta::getStatus).or().eq(ToolMeta::getStatus, STATUS_ENABLED));
		query.orderByDesc(ToolMeta::getVersion);
		query.orderByDesc(ToolMeta::getId);
		return Optional.ofNullable(getOne(query, false));
	}

	LambdaQueryWrapper<ToolMeta> buildEnabledQuery(String tenantId, String systemCode) {
		LambdaQueryWrapper<ToolMeta> query = new LambdaQueryWrapper<>();
		query.eq(ToolMeta::getTenantId, normalizeTenant(tenantId));
		if (StringUtils.hasText(systemCode)) {
			query.eq(ToolMeta::getSystemCode, systemCode);
		}
		query.and(wrapper -> wrapper.isNull(ToolMeta::getStatus).or().eq(ToolMeta::getStatus, STATUS_ENABLED));
		query.orderByAsc(ToolMeta::getId);
		return query;
	}

	String normalizeTenant(String tenantId) {
		return StringUtils.hasText(tenantId) ? tenantId : DEFAULT_TENANT;
	}

}
