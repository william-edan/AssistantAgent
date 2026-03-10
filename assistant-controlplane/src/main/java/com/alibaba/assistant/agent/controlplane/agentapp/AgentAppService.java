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
package com.alibaba.assistant.agent.controlplane.agentapp;

import com.alibaba.assistant.agent.controlplane.agentapp.mapper.AgentAppMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Optional;

@Service
public class AgentAppService extends ServiceImpl<AgentAppMapper, AgentApp> {

	private static final String STATUS_ACTIVE = "active";

	public Optional<AgentApp> findActiveByCode(Long spaceId, String agentAppCode) {
		if (spaceId == null || !StringUtils.hasText(agentAppCode)) {
			return Optional.empty();
		}

		LambdaQueryWrapper<AgentApp> query = new LambdaQueryWrapper<>();
		query.eq(AgentApp::getSpaceId, spaceId);
		query.eq(AgentApp::getAgentAppCode, agentAppCode);
		query.eq(AgentApp::getStatus, STATUS_ACTIVE);
		query.orderByDesc(AgentApp::getId);
		return Optional.ofNullable(getOne(query, false));
	}

}
