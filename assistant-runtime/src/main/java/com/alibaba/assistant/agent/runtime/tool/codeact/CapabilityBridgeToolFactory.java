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
package com.alibaba.assistant.agent.runtime.tool.codeact;

import com.alibaba.assistant.agent.common.tools.CodeactTool;
import com.alibaba.assistant.agent.controlplane.toolregistry.ToolMeta;
import com.alibaba.assistant.agent.controlplane.toolregistry.ToolMetaService;
import com.alibaba.assistant.agent.execution.flow.DAGFlowExecutor;
import com.alibaba.assistant.agent.execution.flow.FlowDefinitionConverter;
import com.alibaba.assistant.agent.execution.step.HttpStepExecutor;
import com.alibaba.assistant.agent.extension.dynamic.spi.DynamicCodeactToolFactory;
import com.alibaba.assistant.agent.extension.dynamic.spi.DynamicToolFactoryContext;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Dynamic bridge tool factory that loads enabled ToolMeta records and creates
 * corresponding CapabilityBridgeTool instances.
 *
 * @author Assistant Agent Team
 * @since 1.0.0
 */
@Component
@Profile("migration")
public class CapabilityBridgeToolFactory implements DynamicCodeactToolFactory {

	private static final Logger logger = LoggerFactory.getLogger(CapabilityBridgeToolFactory.class);

	private final ToolMetaService toolMetaService;

	private final FlowDefinitionConverter flowDefinitionConverter;

	private final DAGFlowExecutor dagFlowExecutor;

	private final HttpStepExecutor httpStepExecutor;

	private final ObjectMapper objectMapper;

	public CapabilityBridgeToolFactory(ToolMetaService toolMetaService,
			FlowDefinitionConverter flowDefinitionConverter,
			DAGFlowExecutor dagFlowExecutor,
			HttpStepExecutor httpStepExecutor,
			ObjectMapper objectMapper) {
		this.toolMetaService = toolMetaService;
		this.flowDefinitionConverter = flowDefinitionConverter;
		this.dagFlowExecutor = dagFlowExecutor;
		this.httpStepExecutor = httpStepExecutor;
		this.objectMapper = objectMapper;
	}

	@Override
	public String factoryId() {
		return "capability-bridge";
	}

	@Override
	public List<CodeactTool> createTools(DynamicToolFactoryContext context) {
		return createToolsForTenant("default");
	}

	public List<CodeactTool> createToolsForTenant(String tenantId) {
		List<ToolMeta> metas = queryEnabledTools(tenantId);
		List<CodeactTool> tools = new ArrayList<>(metas.size());
		Set<String> usedNames = new HashSet<>();

		for (ToolMeta meta : metas) {
			String bridgeToolName = ensureUniqueName(resolveBridgeToolName(meta), usedNames);
			String targetClassName = resolveTargetClassName(meta);
			CapabilityBridgeTool tool = new CapabilityBridgeTool(
					objectMapper,
					meta,
					flowDefinitionConverter,
					dagFlowExecutor,
					httpStepExecutor,
					bridgeToolName,
					targetClassName);
			tools.add(tool);
		}

		logger.info("CapabilityBridgeToolFactory#createToolsForTenant - tenantId={}, toolCount={}",
				tenantId, tools.size());
		return tools;
	}

	private List<ToolMeta> queryEnabledTools(String tenantId) {
		LambdaQueryWrapper<ToolMeta> query = new LambdaQueryWrapper<>();
		if (StringUtils.hasText(tenantId)) {
			query.eq(ToolMeta::getTenantId, tenantId);
		}
		query.and(wrapper -> wrapper.isNull(ToolMeta::getStatus).or().eq(ToolMeta::getStatus, "enabled"));
		query.orderByAsc(ToolMeta::getId);
		return toolMetaService.list(query);
	}

	private String resolveBridgeToolName(ToolMeta meta) {
		String raw = firstNonBlank(meta.getToolCode(), meta.getToolName(), "capability_" + meta.getId());
		String normalized = normalizeIdentifier(raw);
		if (!normalized.endsWith("_execute")) {
			normalized = normalized + "_execute";
		}
		return normalized;
	}

	private String resolveTargetClassName(ToolMeta meta) {
		String rawSystemCode = firstNonBlank(meta.getSystemCode(), "capability");
		return normalizeIdentifier(rawSystemCode) + "_tools";
	}

	private String ensureUniqueName(String baseName, Set<String> usedNames) {
		String candidate = baseName;
		int idx = 2;
		while (usedNames.contains(candidate)) {
			candidate = baseName + "_" + idx++;
		}
		usedNames.add(candidate);
		return candidate;
	}

	private String normalizeIdentifier(String raw) {
		if (!StringUtils.hasText(raw)) {
			return "capability";
		}
		String normalized = raw.toLowerCase(Locale.ROOT)
				.replaceAll("[^a-z0-9_]+", "_")
				.replaceAll("_+", "_")
				.replaceAll("^_", "")
				.replaceAll("_$", "");
		if (!StringUtils.hasText(normalized)) {
			normalized = "capability";
		}
		if (Character.isDigit(normalized.charAt(0))) {
			normalized = "tool_" + normalized;
		}
		return normalized;
	}

	private String firstNonBlank(String... values) {
		if (values == null) {
			return null;
		}
		for (String value : values) {
			if (StringUtils.hasText(value)) {
				return value;
			}
		}
		return null;
	}
}
