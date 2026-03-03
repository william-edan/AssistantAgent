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
package com.alibaba.assistant.agent.runtime.prompt;

import com.alibaba.assistant.agent.controlplane.toolregistry.ToolMeta;
import com.alibaba.assistant.agent.controlplane.toolregistry.ToolMetaService;
import com.alibaba.assistant.agent.runtime.config.RuntimeConfigCompatibilityAdapter;
import com.alibaba.assistant.agent.prompt.PromptContribution;
import com.alibaba.assistant.agent.prompt.PromptContributor;
import com.alibaba.assistant.agent.prompt.PromptContributorContext;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Injects tenant/system-scoped tool catalog into prompt context.
 *
 * <p>Current prompt hook path appends message content into conversation.
 *
 * @author Assistant Agent Team
 * @since 1.0.0
 */
@Component
@Profile("migration")
public class ToolCatalogContributor implements PromptContributor {

	private static final String DEFAULT_TENANT = "default";

	private final ToolMetaService toolMetaService;

	private final RuntimeConfigCompatibilityAdapter compatibilityAdapter;

	public ToolCatalogContributor(
			ToolMetaService toolMetaService,
			RuntimeConfigCompatibilityAdapter compatibilityAdapter) {
		this.toolMetaService = toolMetaService;
		this.compatibilityAdapter = compatibilityAdapter;
	}

	@Override
	public String getName() {
		return "tool-catalog";
	}

	@Override
	public int getPriority() {
		return 200;
	}

	@Override
	public boolean shouldContribute(PromptContributorContext context) {
		return compatibilityAdapter.promptDynamicEnabled();
	}

	@Override
	public PromptContribution contribute(PromptContributorContext context) {
		String tenantId = resolveTenantId(context);
		String systemCode = resolveSystemCode(context);
		List<ToolMeta> source = toolMetaService.listEnabledByTenantAndSystem(tenantId, systemCode);
		if (source == null || source.isEmpty()) {
			return PromptContribution.empty();
		}

		int limit = Math.max(1, compatibilityAdapter.promptMaxToolsInPrompt());
		boolean truncated = source.size() > limit;
		List<ToolMeta> tools = new ArrayList<>(source.subList(0, Math.min(limit, source.size())));
		String catalogText = renderCatalogText(tools, truncated, source.size());

		return PromptContribution.builder()
				.append(new UserMessage(catalogText))
				.build();
	}

	private String resolveTenantId(PromptContributorContext context) {
		Map<String, Object> attrs = context.getAttributes();
		String tenantId = firstNonBlank(
				asText(attrs.get("tenant_id")),
				asText(attrs.get("tenantId")));
		return StringUtils.hasText(tenantId) ? tenantId : DEFAULT_TENANT;
	}

	private String resolveSystemCode(PromptContributorContext context) {
		Map<String, Object> attrs = context.getAttributes();
		return firstNonBlank(
				asText(attrs.get("system_code")),
				asText(attrs.get("systemCode")));
	}

	private String renderCatalogText(List<ToolMeta> tools, boolean truncated, int originalSize) {
		StringBuilder sb = new StringBuilder();
		sb.append("【可用业务工具目录】\n");
		sb.append("调用 slot_collect 时，toolCode 必须使用下列值，不要编造：\n");
		for (ToolMeta tool : tools) {
			if (!StringUtils.hasText(tool.getToolCode())) {
				continue;
			}
			sb.append("- toolCode=\"").append(tool.getToolCode()).append("\"");
			if (StringUtils.hasText(tool.getToolName())) {
				sb.append(" (").append(tool.getToolName()).append(")");
			}
			if (StringUtils.hasText(tool.getDescription())) {
				sb.append("：").append(tool.getDescription());
			}
			sb.append("\n");
		}
		if (truncated) {
			sb.append("（工具目录已按上限截断，当前显示 ")
					.append(tools.size())
					.append("/")
					.append(originalSize)
					.append("）\n");
		}
		return sb.toString().trim();
	}

	private String asText(Object value) {
		if (value == null) {
			return null;
		}
		String text = String.valueOf(value).trim();
		return StringUtils.hasText(text) ? text : null;
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
