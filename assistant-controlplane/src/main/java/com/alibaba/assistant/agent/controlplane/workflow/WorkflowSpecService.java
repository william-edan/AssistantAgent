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
package com.alibaba.assistant.agent.controlplane.workflow;

import com.alibaba.assistant.agent.controlplane.workflow.mapper.WorkflowSpecMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Optional;

@Service
public class WorkflowSpecService extends ServiceImpl<WorkflowSpecMapper, WorkflowSpec> {

	private static final String STATUS_ENABLED = "enabled";

	public Optional<WorkflowSpec> findLatestEnabledByCode(Long spaceId, String workflowCode) {
		if (spaceId == null || !StringUtils.hasText(workflowCode)) {
			return Optional.empty();
		}

		LambdaQueryWrapper<WorkflowSpec> query = new LambdaQueryWrapper<>();
		query.eq(WorkflowSpec::getSpaceId, spaceId);
		query.eq(WorkflowSpec::getWorkflowCode, workflowCode);
		query.eq(WorkflowSpec::getStatus, STATUS_ENABLED);
		query.orderByDesc(WorkflowSpec::getVersion);
		query.orderByDesc(WorkflowSpec::getId);
		return Optional.ofNullable(getOne(query, false));
	}

	public List<WorkflowSpec> listEnabledBySpace(Long spaceId) {
		if (spaceId == null) {
			return List.of();
		}

		LambdaQueryWrapper<WorkflowSpec> query = new LambdaQueryWrapper<>();
		query.eq(WorkflowSpec::getSpaceId, spaceId);
		query.eq(WorkflowSpec::getStatus, STATUS_ENABLED);
		query.orderByAsc(WorkflowSpec::getWorkflowCode);
		query.orderByDesc(WorkflowSpec::getVersion);
		query.orderByDesc(WorkflowSpec::getId);
		return list(query);
	}

}
