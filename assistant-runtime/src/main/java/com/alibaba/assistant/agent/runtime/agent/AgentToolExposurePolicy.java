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
package com.alibaba.assistant.agent.runtime.agent;

import com.alibaba.assistant.agent.common.tools.CodeactTool;
import com.alibaba.assistant.agent.runtime.registry.TenantAwareToolRegistry;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Applies profile-specific tool exposure rules.
 */
public class AgentToolExposurePolicy {

	/**
	 * Filter React callbacks according to the resolved profile.
	 *
	 * @param profile resolved profile
	 * @param reactToolCallbacks registered callbacks
	 * @param tenantAwareToolRegistry publication-aware registry
	 * @return filtered immutable callback list
	 */
	public List<ToolCallback> filterReactToolCallbacks(
			AgentProfile profile,
			List<ToolCallback> reactToolCallbacks,
			TenantAwareToolRegistry tenantAwareToolRegistry) {
		if (reactToolCallbacks == null || reactToolCallbacks.isEmpty()) {
			return List.of();
		}
		List<ToolCallback> filtered = new ArrayList<>();
		for (ToolCallback callback : reactToolCallbacks) {
			if (callback == null || callback.getToolDefinition() == null) {
				continue;
			}
			String toolName = callback.getToolDefinition().name();
			if (!shouldExposeReactToolCallback(profile, toolName, tenantAwareToolRegistry)) {
				continue;
			}
			filtered.add(callback);
		}
		return List.copyOf(filtered);
	}

	/**
	 * Collect Codeact tools that should also be visible as React callbacks.
	 *
	 * @param profile resolved profile
	 * @param codeactTools registered codeact tools
	 * @param tenantAwareToolRegistry publication-aware registry
	 * @return filtered immutable codeact tool list
	 */
	public List<CodeactTool> collectReactAccessibleCodeactTools(
			AgentProfile profile,
			List<CodeactTool> codeactTools,
			TenantAwareToolRegistry tenantAwareToolRegistry) {
		List<CodeactTool> merged = new ArrayList<>();
		if (codeactTools != null && !codeactTools.isEmpty()) {
			for (CodeactTool tool : codeactTools) {
				if (tool == null || tool.getToolDefinition() == null) {
					continue;
				}
				String toolName = tool.getToolDefinition().name();
				if (profile != null && profile.isRolePackageChat()
						&& !isBuiltInReactTool(profile, toolName)) {
					continue;
				}
				merged.add(tool);
			}
		}
		if (profile != null && profile.isRolePackageChat()) {
			return List.copyOf(merged);
		}
		if (tenantAwareToolRegistry != null) {
			List<CodeactTool> tenantTools = tenantAwareToolRegistry.getReactAccessibleTools();
			if (tenantTools != null && !tenantTools.isEmpty()) {
				merged.addAll(tenantTools);
			}
		}
		return List.copyOf(merged);
	}

	private boolean shouldExposeReactToolCallback(
			AgentProfile profile,
			String toolName,
			TenantAwareToolRegistry tenantAwareToolRegistry) {
		if (!StringUtils.hasText(toolName)) {
			return false;
		}
		if (isBuiltInReactTool(profile, toolName)) {
			return true;
		}
		if (profile != null && profile.isRolePackageChat()) {
			return false;
		}
		if (tenantAwareToolRegistry == null) {
			return false;
		}
		return tenantAwareToolRegistry.getTool(toolName.trim()).isPresent();
	}

	private boolean isBuiltInReactTool(AgentProfile profile, String toolName) {
		if (profile == null || !StringUtils.hasText(toolName)) {
			return false;
		}
		return new LinkedHashSet<>(profile.builtinReactTools()).contains(toolName.trim());
	}
}
