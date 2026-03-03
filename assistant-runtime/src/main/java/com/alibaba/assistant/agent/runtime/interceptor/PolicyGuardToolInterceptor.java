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
package com.alibaba.assistant.agent.runtime.interceptor;

import com.alibaba.assistant.agent.common.constant.CodeactStateKeys;
import com.alibaba.assistant.agent.controlplane.toolregistry.ToolMeta;
import com.alibaba.assistant.agent.controlplane.toolregistry.ToolMetaService;
import com.alibaba.assistant.agent.runtime.agent.AssistantStateKeys;
import com.alibaba.assistant.agent.runtime.config.RuntimeConfigCompatibilityAdapter;
import com.alibaba.assistant.agent.runtime.guard.BudgetTracker;
import com.alibaba.assistant.agent.runtime.planner.ToolExecutor;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallHandler;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallRequest;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallResponse;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolInterceptor;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Tool boundary governance interceptor:
 * tenant isolation, allowlist, risk confirmation and budget guard.
 *
 * @author Assistant Agent Team
 * @since 1.0.0
 */
@Component
@Profile("migration")
public class PolicyGuardToolInterceptor extends ToolInterceptor {

	private static final Logger logger = LoggerFactory.getLogger(PolicyGuardToolInterceptor.class);

	private static final String CONFIRM_PENDING_KEY = "human_confirmation_pending";

	private static final String PENDING_TOOL_NAME_KEY = "human_confirmation_tool_name";

	private static final String INTERNAL_DEPENDENCY_CALL_KEY = ToolExecutor.INTERNAL_DEPENDENCY_CALL_KEY;

	private final ObjectMapper objectMapper;

	private final ToolMetaService toolMetaService;

	private final BudgetTracker budgetTracker;

	private final RuntimeConfigCompatibilityAdapter compatibilityAdapter;

	public PolicyGuardToolInterceptor(ObjectMapper objectMapper,
			@Nullable ToolMetaService toolMetaService,
			BudgetTracker budgetTracker,
			RuntimeConfigCompatibilityAdapter compatibilityAdapter) {
		this.objectMapper = objectMapper;
		this.toolMetaService = toolMetaService;
		this.budgetTracker = budgetTracker;
		this.compatibilityAdapter = compatibilityAdapter;
	}

	@Override
	public ToolCallResponse interceptToolCall(ToolCallRequest request, ToolCallHandler handler) {
		if (!compatibilityAdapter.policyGuardEnabled()) {
			return handler.call(request);
		}

		OverAllState state = request.getExecutionContext().map(context -> context.state()).orElse(null);
		ToolCallRequest normalizedRequest = normalizeToolCallRequest(request, state);
		String toolName = normalizedRequest.getToolName();
		budgetTracker.initIfAbsent(state);
		if (!budgetTracker.isWithinBudget(state)) {
			String message = "Tool budget exceeded: calls=" + budgetTracker.currentToolCalls(state)
					+ "/" + budgetTracker.maxToolCalls()
					+ ", elapsedMs=" + budgetTracker.elapsedMs(state)
					+ "/" + budgetTracker.maxLatencyMs();
			return ToolCallResponse.error(toolName, normalizedRequest.getToolCallId(), message);
		}

		boolean internalDependencyCall = isInternalDependencyCall(normalizedRequest);
		if (!internalDependencyCall && !isAllowedByNameWhitelist(state, toolName)) {
			String message = "Tool not allowed by whitelist: " + toolName;
			return ToolCallResponse.error(toolName, normalizedRequest.getToolCallId(), message);
		}

		// Execute tool confirmation is handled by HumanInTheLoopHook at the framework level.
		// Only governance-rule checks (tenant isolation, risk-based confirm) apply to non-execute tools here.

		GovernanceRule governanceRule = resolveGovernanceRule(state, toolName);
		if (!internalDependencyCall && !tenantAllowed(governanceRule.tenantId(), state)) {
			String message = "Tenant isolation blocked tool call: tool=" + toolName
					+ ", toolTenant=" + governanceRule.tenantId()
					+ ", sessionTenant=" + resolveTenantId(state);
			return ToolCallResponse.error(toolName, normalizedRequest.getToolCallId(), message);
		}

		if (!internalDependencyCall && !isExecuteToolName(toolName) && governanceRule.requiresConfirm()) {
			Map<String, Object> args = parseArgs(normalizedRequest.getArguments());
			boolean confirmed = readBoolean(args, "confirmed", "confirm", "user_confirmed", "approved");
			if (!confirmed) {
				markPendingConfirmation(state, toolName);
				String risk = StringUtils.hasText(governanceRule.riskLevel()) ? governanceRule.riskLevel() : "UNKNOWN";
				String message = "Confirmation required before executing tool: "
						+ toolName + " (risk=" + risk + ")";
				return ToolCallResponse.error(toolName, normalizedRequest.getToolCallId(), message);
			}
			clearPendingConfirmation(state);
		}

		try {
			return handler.call(normalizedRequest);
		}
		finally {
			budgetTracker.recordToolCall(state);
		}
	}

	@Override
	public String getName() {
		return "PolicyGuardToolInterceptor";
	}

	private boolean isInternalDependencyCall(ToolCallRequest request) {
		if (request == null || request.getContext() == null) {
			return false;
		}
		Object marker = request.getContext().get(INTERNAL_DEPENDENCY_CALL_KEY);
		if (marker instanceof Boolean bool) {
			return bool;
		}
		if (marker == null) {
			return false;
		}
		return "true".equalsIgnoreCase(String.valueOf(marker));
	}

	private boolean isAllowedByNameWhitelist(OverAllState state, String toolName) {
		if (state == null || !StringUtils.hasText(toolName)) {
			return true;
		}
		Object raw = state.value(CodeactStateKeys.AVAILABLE_TOOL_NAMES).orElse(null);
		if (!(raw instanceof List<?> allowlist)) {
			return true;
		}
		return StringUtils.hasText(findAllowlistMatch(allowlist, toolName));
	}

	private boolean isExecuteToolName(String toolName) {
		if (!StringUtils.hasText(toolName)) {
			return false;
		}
		String normalized = toolName.trim().toLowerCase(Locale.ROOT);
		return normalized.endsWith("_execute")
				|| normalized.matches(".*_execute_[0-9]+$");
	}

	private GovernanceRule resolveGovernanceRule(OverAllState state, String toolName) {
		ToolMeta stateMeta = extractStateToolMeta(state);
		if (matchesTool(stateMeta, toolName)) {
			return toRule(stateMeta);
		}
		ToolMeta queriedMeta = findToolMetaByCode(state, toolName);
		if (queriedMeta != null) {
			return toRule(queriedMeta);
		}
		return new GovernanceRule(false, null, null);
	}

	private ToolMeta extractStateToolMeta(OverAllState state) {
		if (state == null) {
			return null;
		}
		Object raw = state.value(AssistantStateKeys.MATCHED_TOOL_META, Object.class).orElse(null);
		if (raw == null) {
			return null;
		}
		if (raw instanceof ToolMeta toolMeta) {
			return toolMeta;
		}
		try {
			return objectMapper.convertValue(raw, ToolMeta.class);
		}
		catch (Exception e) {
			logger.debug("PolicyGuardToolInterceptor#extractStateToolMeta - convert failed, error={}", e.getMessage());
			return null;
		}
	}

	private ToolMeta findToolMetaByCode(OverAllState state, String toolName) {
		if (toolMetaService == null || !StringUtils.hasText(toolName)) {
			return null;
		}
		String tenantId = resolveTenantId(state);
		Optional<ToolMeta> exact = toolMetaService.findLatestEnabledByToolCode(tenantId, toolName);
		if (exact.isPresent()) {
			return exact.get();
		}
		String normalized = normalizeExecuteToolCode(toolName);
		if (StringUtils.hasText(normalized) && !normalized.equalsIgnoreCase(toolName)) {
			return toolMetaService.findLatestEnabledByToolCode(tenantId, normalized).orElse(null);
		}
		return null;
	}

	private boolean matchesTool(ToolMeta meta, String toolName) {
		if (meta == null || !StringUtils.hasText(toolName) || !StringUtils.hasText(meta.getToolCode())) {
			return false;
		}
		String metaCode = meta.getToolCode();
		String normalizedToolName = normalizeIdentifier(toolName);
		String normalizedMetaCode = normalizeIdentifier(metaCode);
		String normalizedExecuteName = normalizedMetaCode + "_execute";
		return toolName.equalsIgnoreCase(metaCode)
				|| toolName.equalsIgnoreCase(metaCode + "_execute")
				|| normalizedToolName.equalsIgnoreCase(normalizedMetaCode)
				|| normalizedToolName.equalsIgnoreCase(normalizedExecuteName)
				|| normalizedToolName.startsWith(normalizedExecuteName + "_");
	}

	private GovernanceRule toRule(ToolMeta meta) {
		if (meta == null) {
			return new GovernanceRule(false, null, null);
		}
		boolean riskDrivenConfirm = isMediumOrHigher(meta.getRiskLevel());
		return new GovernanceRule(Boolean.TRUE.equals(meta.getRequiresConfirm()) || riskDrivenConfirm,
				meta.getRiskLevel(), meta.getTenantId());
	}

	private boolean isMediumOrHigher(String riskLevel) {
		if (!StringUtils.hasText(riskLevel)) {
			return false;
		}
		String normalized = riskLevel.trim().toUpperCase(Locale.ROOT);
		return "MEDIUM".equals(normalized) || "HIGH".equals(normalized) || "CRITICAL".equals(normalized);
	}

	private String resolveTenantId(OverAllState state) {
		if (state == null) {
			return "default";
		}
		String tenant = firstNonBlank(
				readStateString(state, "tenant_id"),
				readStateString(state, "tenantId"));
		return StringUtils.hasText(tenant) ? tenant : "default";
	}

	private String readStateString(OverAllState state, String key) {
		if (state == null || !StringUtils.hasText(key)) {
			return null;
		}
		Object value = state.value(key, Object.class).orElse(null);
		if (value == null) {
			return null;
		}
		String text = String.valueOf(value).trim();
		return StringUtils.hasText(text) ? text : null;
	}

	private Boolean readStateBoolean(OverAllState state, String key) {
		if (state == null || !StringUtils.hasText(key)) {
			return null;
		}
		Object value = state.value(key, Object.class).orElse(null);
		if (value == null) {
			return null;
		}
		if (value instanceof Boolean bool) {
			return bool;
		}
		if (value instanceof Number number) {
			return number.intValue() != 0;
		}
		String text = String.valueOf(value).trim().toLowerCase(Locale.ROOT);
		if ("1".equals(text) || "true".equals(text) || "yes".equals(text) || "y".equals(text)) {
			return true;
		}
		if ("0".equals(text) || "false".equals(text) || "no".equals(text) || "n".equals(text)) {
			return false;
		}
		return null;
	}

	private boolean tenantAllowed(String toolTenantId, OverAllState state) {
		if (!StringUtils.hasText(toolTenantId)) {
			return true;
		}
		String sessionTenant = resolveTenantId(state);
		return toolTenantId.equals(sessionTenant);
	}

	private void markPendingConfirmation(OverAllState state, String toolName) {
		if (state == null) {
			return;
		}
		Map<String, Object> updates = new LinkedHashMap<>();
		updates.put(AssistantStateKeys.CONVERSATION_PHASE, "CONFIRMING");
		updates.put(CONFIRM_PENDING_KEY, true);
		updates.put(PENDING_TOOL_NAME_KEY, toolName);
		state.updateState(updates);
	}

	private void clearPendingConfirmation(OverAllState state) {
		if (state == null) {
			return;
		}
		Map<String, Object> updates = new LinkedHashMap<>();
		updates.put(CONFIRM_PENDING_KEY, false);
		updates.put(PENDING_TOOL_NAME_KEY, null);
		state.updateState(updates);
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> parseArgs(String arguments) {
		if (!StringUtils.hasText(arguments)) {
			return Collections.emptyMap();
		}
		try {
			Map<String, Object> parsed = objectMapper.readValue(arguments, Map.class);
			return parsed != null ? parsed : Collections.emptyMap();
		}
		catch (Exception e) {
			return Collections.emptyMap();
		}
	}

	private boolean readBoolean(Map<String, Object> map, String... keys) {
		Object value = readMapValue(map, keys);
		if (value == null) {
			return false;
		}
		if (value instanceof Boolean bool) {
			return bool;
		}
		if (value instanceof Number number) {
			return number.intValue() != 0;
		}
		String text = String.valueOf(value).trim().toLowerCase(Locale.ROOT);
		return "1".equals(text) || "true".equals(text) || "yes".equals(text) || "y".equals(text);
	}

	private Object readMapValue(Map<String, Object> map, String... keys) {
		if (map == null || map.isEmpty() || keys == null) {
			return null;
		}
		for (String key : keys) {
			if (!StringUtils.hasText(key)) {
				continue;
			}
			if (map.containsKey(key)) {
				return map.get(key);
			}
			for (Map.Entry<String, Object> entry : map.entrySet()) {
				if (entry.getKey() != null && key.equalsIgnoreCase(entry.getKey())) {
					return entry.getValue();
				}
			}
		}
		return null;
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

	private ToolCallRequest normalizeToolCallRequest(ToolCallRequest request, OverAllState state) {
		if (request == null || !StringUtils.hasText(request.getToolName())) {
			return request;
		}
		String originalName = request.getToolName().trim();
		String canonicalName = resolveCanonicalToolName(state, originalName);
		if (!StringUtils.hasText(canonicalName) || originalName.equals(canonicalName)) {
			return request;
		}
		logger.warn("PolicyGuardToolInterceptor#normalizeToolCallRequest - tool name normalized: {} -> {}",
				originalName, canonicalName);
		return ToolCallRequest.builder(request)
				.toolName(canonicalName)
				.build();
	}

	private String resolveCanonicalToolName(OverAllState state, String toolName) {
		if (!StringUtils.hasText(toolName)) {
			return toolName;
		}
		Object raw = state != null ? state.value(CodeactStateKeys.AVAILABLE_TOOL_NAMES).orElse(null) : null;
		if (raw instanceof List<?> allowlist && !allowlist.isEmpty()) {
			String allowlistHit = findAllowlistMatch(allowlist, toolName);
			if (StringUtils.hasText(allowlistHit)) {
				return allowlistHit;
			}
			String normalizedCandidate = normalizeExecuteToolName(toolName);
			String normalizedHit = findAllowlistMatch(allowlist, normalizedCandidate);
			if (StringUtils.hasText(normalizedHit)) {
				return normalizedHit;
			}
		}
		return normalizeExecuteToolName(toolName);
	}

	private String findAllowlistMatch(List<?> allowlist, String toolName) {
		if (allowlist == null || allowlist.isEmpty() || !StringUtils.hasText(toolName)) {
			return null;
		}
		for (Object item : allowlist) {
			if (item == null) {
				continue;
			}
			String name = String.valueOf(item).trim();
			if (StringUtils.hasText(name) && toolName.equalsIgnoreCase(name)) {
				return name;
			}
		}
		return null;
	}

	private String normalizeExecuteToolName(String toolName) {
		if (!StringUtils.hasText(toolName)) {
			return null;
		}
		String trimmed = toolName.trim();
		if (!containsUnsupportedChars(trimmed)) {
			return trimmed;
		}
		String normalized = normalizeIdentifier(trimmed);
		if (!looksLikeExecuteTool(normalized)) {
			return trimmed;
		}
		return normalized;
	}

	private boolean containsUnsupportedChars(String value) {
		for (int idx = 0; idx < value.length(); idx++) {
			char ch = value.charAt(idx);
			if (ch == '_' || Character.isLetterOrDigit(ch)) {
				continue;
			}
			return true;
		}
		return false;
	}

	private boolean looksLikeExecuteTool(String toolName) {
		String normalized = toolName.toLowerCase(Locale.ROOT);
		return normalized.endsWith("_execute")
				|| normalized.matches(".*_execute_[0-9]+$");
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

	private String normalizeExecuteToolCode(String toolName) {
		if (!StringUtils.hasText(toolName)) {
			return null;
		}
		if (toolName.endsWith("_execute")) {
			return toolName.substring(0, toolName.length() - "_execute".length());
		}
		return toolName;
	}

	private record GovernanceRule(boolean requiresConfirm, String riskLevel, String tenantId) {

	}

}
