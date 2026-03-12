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

import com.alibaba.assistant.agent.controlplane.toolregistry.ToolMeta;
import com.alibaba.assistant.agent.runtime.agent.AssistantStateKeys;
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
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Human-in-the-loop guard.
 * Blocks execution for high-risk tools until explicit confirmation is present in arguments.
 *
 * @author Assistant Agent Team
 * @since 1.0.0
 */
@Component
@Profile("migration")
public class HumanInTheLoopToolInterceptor extends ToolInterceptor {

	private static final Logger logger = LoggerFactory.getLogger(HumanInTheLoopToolInterceptor.class);

	private static final String CONFIRM_PENDING_KEY = "human_confirmation_pending";

	private static final String PENDING_TOOL_NAME_KEY = "human_confirmation_tool_name";

	private final ObjectMapper objectMapper;

	public HumanInTheLoopToolInterceptor(ObjectMapper objectMapper) {
		this.objectMapper = objectMapper;
	}

	@Override
	public ToolCallResponse interceptToolCall(ToolCallRequest request, ToolCallHandler handler) {
		if (isInternalDependencyCall(request)) {
			return handler.call(request);
		}
		OverAllState state = request.getExecutionContext().map(context -> context.state()).orElse(null);
		GovernanceRule governanceRule = resolveGovernanceRule(state);
		if (!governanceRule.requiresConfirm()) {
			return handler.call(request);
		}

		Map<String, Object> args = parseArgs(request.getArguments());
		boolean confirmed = readBoolean(args, "confirmed", "confirm", "user_confirmed", "approved");
		if (confirmed) {
			clearPendingConfirmation(state);
			return handler.call(request);
		}

		markPendingConfirmation(state, request.getToolName());
		String risk = StringUtils.hasText(governanceRule.riskLevel()) ? governanceRule.riskLevel() : "UNKNOWN";
		String message = "Human confirmation required before executing high-risk tool: "
				+ request.getToolName() + " (risk=" + risk + ")";
		logger.warn("HumanInTheLoopToolInterceptor#interceptToolCall - {}", message);
		return ToolCallResponse.error(request.getToolName(), request.getToolCallId(), message);
	}

	@Override
	public String getName() {
		return "HumanInTheLoopToolInterceptor";
	}

	private boolean isInternalDependencyCall(ToolCallRequest request) {
		if (request == null || request.getContext() == null) {
			return false;
		}
		Object marker = request.getContext().get(ToolExecutor.INTERNAL_DEPENDENCY_CALL_KEY);
		if (marker instanceof Boolean bool) {
			return bool;
		}
		if (marker == null) {
			return false;
		}
		return "true".equalsIgnoreCase(String.valueOf(marker));
	}
	private GovernanceRule resolveGovernanceRule(OverAllState state) {
		if (state == null) {
			return new GovernanceRule(false, null);
		}

		Object raw = state.value(AssistantStateKeys.MATCHED_TOOL_META, Object.class).orElse(null);
		if (raw == null) {
			return new GovernanceRule(false, null);
		}

		if (raw instanceof ToolMeta toolMeta) {
			return ruleFromValues(toolMeta.getRequiresConfirm(), toolMeta.getRiskLevel());
		}

		if (raw instanceof Map<?, ?> map) {
			Boolean requiresConfirm = asBoolean(readMapValue(map, "requiresConfirm", "requires_confirm"));
			String riskLevel = asString(readMapValue(map, "riskLevel", "risk_level"));
			return ruleFromValues(requiresConfirm, riskLevel);
		}

		try {
			ToolMeta converted = objectMapper.convertValue(raw, ToolMeta.class);
			return ruleFromValues(converted.getRequiresConfirm(), converted.getRiskLevel());
		}
		catch (Exception e) {
			logger.debug("HumanInTheLoopToolInterceptor#resolveGovernanceRule - convert failed, error={}",
					e.getMessage());
			return new GovernanceRule(false, null);
		}
	}

	private GovernanceRule ruleFromValues(Boolean requiresConfirm, String riskLevel) {
		boolean riskDrivenConfirm = isHighRisk(riskLevel);
		return new GovernanceRule(Boolean.TRUE.equals(requiresConfirm) || riskDrivenConfirm, riskLevel);
	}

	private boolean isHighRisk(String riskLevel) {
		if (!StringUtils.hasText(riskLevel)) {
			return false;
		}
		String normalized = riskLevel.trim().toUpperCase(Locale.ROOT);
		return "HIGH".equals(normalized) || "CRITICAL".equals(normalized);
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
			logger.warn("HumanInTheLoopToolInterceptor#parseArgs - parse failed, error={}", e.getMessage());
			return Collections.emptyMap();
		}
	}

	private boolean readBoolean(Map<String, Object> map, String... keys) {
		Object value = readMapValue(map, keys);
		return asBoolean(value) != null && asBoolean(value);
	}

	private Object readMapValue(Map<?, ?> map, String... keys) {
		if (map == null || keys == null) {
			return null;
		}
		for (String key : keys) {
			if (!StringUtils.hasText(key)) {
				continue;
			}
			if (map.containsKey(key)) {
				return map.get(key);
			}
			for (Map.Entry<?, ?> entry : map.entrySet()) {
				if (entry.getKey() != null && key.equalsIgnoreCase(String.valueOf(entry.getKey()))) {
					return entry.getValue();
				}
			}
		}
		return null;
	}

	private Boolean asBoolean(Object value) {
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

	private String asString(Object value) {
		if (value == null) {
			return null;
		}
		String text = String.valueOf(value).trim();
		return StringUtils.hasText(text) ? text : null;
	}

	private record GovernanceRule(boolean requiresConfirm, String riskLevel) {

	}

}

