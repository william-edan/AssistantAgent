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
package com.alibaba.assistant.agent.runtime.planner;

import com.alibaba.assistant.agent.controlplane.toolregistry.ToolMeta;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Deterministic dependency resolver based on tool interactionPolicy JSON.
 *
 * <p>Example:
 * <ul>
 *   <li>target tool: {@code leave_apply}</li>
 *   <li>interactionPolicy.dependsOn: {@code ["current_user"]}</li>
 *   <li>output order: {@code [current_user, leave_apply]}</li>
 * </ul>
 *
 * @author Assistant Agent Team
 * @since 1.0.0
 */
@Component
@Profile("migration")
public class DependencyResolver {

	private static final int DEFAULT_MAX_DEPTH = 3;

	private final ObjectMapper objectMapper;

	private final int maxDepth;

	@Autowired
	public DependencyResolver(ObjectMapper objectMapper) {
		this(objectMapper, DEFAULT_MAX_DEPTH);
	}

	DependencyResolver(ObjectMapper objectMapper, int maxDepth) {
		this.objectMapper = objectMapper;
		this.maxDepth = Math.max(1, maxDepth);
	}

	/**
	 * Resolve tool dependencies with topological order.
	 *
	 * @param toolCode target tool code
	 * @param provider tool provider
	 * @return dependency chain with target step as last item
	 */
	public List<ResolvedStep> resolve(String toolCode, ToolMetaProvider provider) {
		if (!StringUtils.hasText(toolCode)) {
			return Collections.emptyList();
		}
		ToolMeta root = provider.findByToolCode(toolCode)
				.orElseThrow(() -> new IllegalArgumentException("Tool not found: " + toolCode));
		List<FieldMapping> rootMappings = parseFieldMappings(root.getInteractionPolicy());
		List<ResolvedStep> result = new ArrayList<>();
		Deque<String> stack = new ArrayDeque<>();
		Set<String> visiting = new LinkedHashSet<>();
		Set<String> visited = new LinkedHashSet<>();
		dfsResolve(root.getToolCode(), provider, 0, visiting, visited, stack, result, rootMappings);
		return result;
	}

	private void dfsResolve(
			String toolCode,
			ToolMetaProvider provider,
			int depth,
			Set<String> visiting,
			Set<String> visited,
			Deque<String> stack,
			List<ResolvedStep> result,
			List<FieldMapping> rootMappings) {
		if (visited.contains(toolCode)) {
			return;
		}
		if (depth > maxDepth) {
			throw new IllegalStateException("Dependency depth exceeded maxDepth=" + maxDepth + ", toolCode=" + toolCode);
		}
		if (!visiting.add(toolCode)) {
			throw new IllegalStateException("Cyclic dependency detected: " + String.join(" -> ", stack) + " -> " + toolCode);
		}
		stack.push(toolCode);

		ToolMeta meta = provider.findByToolCode(toolCode)
				.orElseThrow(() -> new IllegalStateException("Missing dependency tool: " + toolCode));
		List<String> dependencies = parseDependsOn(meta.getInteractionPolicy());
		for (String dep : dependencies) {
			dfsResolve(dep, provider, depth + 1, visiting, visited, stack, result, rootMappings);
		}

		stack.pop();
		visiting.remove(toolCode);
		visited.add(toolCode);
		result.add(new ResolvedStep(
				toolCode,
				meta.getDescription(),
				filterMappingsBySource(rootMappings, toolCode)));
	}

	private List<String> parseDependsOn(String interactionPolicy) {
		if (!StringUtils.hasText(interactionPolicy)) {
			return Collections.emptyList();
		}
		try {
			JsonNode root = objectMapper.readTree(interactionPolicy);
			JsonNode dependsOn = root.get("dependsOn");
			if (dependsOn == null || !dependsOn.isArray()) {
				return Collections.emptyList();
			}
			List<String> deps = new ArrayList<>();
			for (JsonNode item : dependsOn) {
				String dep = item != null ? item.asText(null) : null;
				if (StringUtils.hasText(dep)) {
					deps.add(dep.trim());
				}
			}
			return deps;
		}
		catch (Exception e) {
			return Collections.emptyList();
		}
	}

	private List<FieldMapping> parseFieldMappings(String interactionPolicy) {
		if (!StringUtils.hasText(interactionPolicy)) {
			return Collections.emptyList();
		}
		try {
			JsonNode root = objectMapper.readTree(interactionPolicy);
			JsonNode mappingsNode = root.get("fieldMappings");
			if (mappingsNode == null || !mappingsNode.isArray()) {
				return Collections.emptyList();
			}
			List<FieldMapping> mappings = new ArrayList<>();
			for (JsonNode item : mappingsNode) {
				String fromTool = text(item, "fromTool");
				String fromField = text(item, "fromField");
				String toField = text(item, "toField");
				if (StringUtils.hasText(fromTool) && StringUtils.hasText(fromField) && StringUtils.hasText(toField)) {
					mappings.add(new FieldMapping(fromTool.trim(), fromField.trim(), toField.trim()));
				}
			}
			return mappings;
		}
		catch (Exception e) {
			return Collections.emptyList();
		}
	}

	private List<FieldMapping> filterMappingsBySource(List<FieldMapping> all, String sourceToolCode) {
		if (all == null || all.isEmpty() || !StringUtils.hasText(sourceToolCode)) {
			return Collections.emptyList();
		}
		List<FieldMapping> filtered = new ArrayList<>();
		for (FieldMapping mapping : all) {
			if (sourceToolCode.equalsIgnoreCase(mapping.fromTool())) {
				filtered.add(mapping);
			}
		}
		return filtered;
	}

	private String text(JsonNode node, String field) {
		if (node == null || !StringUtils.hasText(field)) {
			return null;
		}
		JsonNode value = node.get(field);
		return value != null ? value.asText(null) : null;
	}

	/**
	 * Provider abstraction for dependency lookup.
	 */
	@FunctionalInterface
	public interface ToolMetaProvider {
		Optional<ToolMeta> findByToolCode(String toolCode);
	}

	/**
	 * Field mapping metadata from dependency output to target slot field.
	 */
	public record FieldMapping(String fromTool, String fromField, String toField) {
		public FieldMapping {
			if (StringUtils.hasText(fromTool)) {
				fromTool = fromTool.trim().toLowerCase(Locale.ROOT);
			}
		}
	}

	/**
	 * Resolved dependency step.
	 */
	public record ResolvedStep(String toolCode, String purpose, List<FieldMapping> mappings) {
	}

}
