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
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelCallHandler;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelInterceptor;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelRequest;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelResponse;
import com.alibaba.cloud.ai.graph.agent.tools.ToolContextConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import org.springframework.ai.chat.messages.AssistantMessage;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Policy guard for model tool-call decisions.
 * Enforces allowlist from state key {@link CodeactStateKeys#AVAILABLE_TOOL_NAMES}.
 *
 * @author Assistant Agent Team
 * @since 1.0.0
 */
@Component
@Profile("migration")
public class PolicyCheckModelInterceptor extends ModelInterceptor {

	private static final Logger logger = LoggerFactory.getLogger(PolicyCheckModelInterceptor.class);

	@Override
	public ModelResponse interceptModel(ModelRequest request, ModelCallHandler handler) {
		Set<String> allowlist = resolveAllowlist(request);
		if (allowlist.isEmpty()) {
			return handler.call(request);
		}

		ModelRequest sanitizedRequest = sanitizeRequestByAllowlist(request, allowlist);
		ModelResponse response = handler.call(sanitizedRequest);
		validateToolCalls(response, allowlist);
		return response;
	}

	@Override
	public String getName() {
		return "PolicyCheckModelInterceptor";
	}

	private Set<String> resolveAllowlist(ModelRequest request) {
		Map<String, Object> context = request != null ? request.getContext() : null;
		if (CollectionUtils.isEmpty(context)) {
			return Collections.emptySet();
		}

		Object stateObject = context.get(ToolContextConstants.AGENT_STATE_CONTEXT_KEY);
		if (!(stateObject instanceof OverAllState state)) {
			return Collections.emptySet();
		}

		Object raw = state.value(CodeactStateKeys.AVAILABLE_TOOL_NAMES).orElse(null);
		if (!(raw instanceof List<?> rawList)) {
			return Collections.emptySet();
		}

		Set<String> allowlist = new LinkedHashSet<>();
		for (Object item : rawList) {
			String toolName = item != null ? String.valueOf(item).trim() : null;
			if (StringUtils.hasText(toolName)) {
				allowlist.add(toolName);
			}
		}
		return allowlist;
	}

	private ModelRequest sanitizeRequestByAllowlist(ModelRequest request, Set<String> allowlist) {
		Map<String, String> filteredToolDescriptions = filterToolDescriptions(request.getToolDescriptions(), allowlist);
		List<String> filteredTools = filterToolNames(request.getTools(), allowlist);
		List<ToolCallback> filteredDynamicTools = filterDynamicToolCallbacks(request.getDynamicToolCallbacks(), allowlist);

		return ModelRequest.builder(request)
				.toolDescriptions(filteredToolDescriptions)
				.tools(filteredTools)
				.dynamicToolCallbacks(filteredDynamicTools)
				.build();
	}

	private Map<String, String> filterToolDescriptions(Map<String, String> toolDescriptions, Set<String> allowlist) {
		if (CollectionUtils.isEmpty(toolDescriptions)) {
			return Collections.emptyMap();
		}
		Map<String, String> filtered = new LinkedHashMap<>();
		for (Map.Entry<String, String> entry : toolDescriptions.entrySet()) {
			if (allowlist.contains(entry.getKey())) {
				filtered.put(entry.getKey(), entry.getValue());
			}
		}
		return filtered;
	}

	private List<String> filterToolNames(List<String> source, Set<String> allowlist) {
		if (source == null) {
			return null;
		}
		List<String> filtered = new ArrayList<>();
		for (String toolName : source) {
			if (!StringUtils.hasText(toolName) || allowlist.contains(toolName)) {
				filtered.add(toolName);
			}
		}
		return filtered;
	}

	private List<ToolCallback> filterDynamicToolCallbacks(List<ToolCallback> source, Set<String> allowlist) {
		if (source == null) {
			return null;
		}
		List<ToolCallback> filtered = new ArrayList<>();
		for (ToolCallback callback : source) {
			String name = callback != null && callback.getToolDefinition() != null
					? callback.getToolDefinition().name()
					: null;
			if (!StringUtils.hasText(name) || allowlist.contains(name)) {
				filtered.add(callback);
			}
		}
		return filtered;
	}

	private void validateToolCalls(ModelResponse response, Set<String> allowlist) {
		if (response == null || response.getMessage() == null) {
			return;
		}

		Object messageObject = response.getMessage();
		if (!(messageObject instanceof AssistantMessage assistantMessage) || !assistantMessage.hasToolCalls()) {
			return;
		}

		for (AssistantMessage.ToolCall toolCall : assistantMessage.getToolCalls()) {
			if (!allowlist.contains(toolCall.name())) {
				logger.warn(
						"PolicyCheckModelInterceptor#validateToolCalls - blocked unauthorized tool, toolName={}, allowlist={}",
						toolCall.name(), allowlist);
				throw new IllegalStateException("Unauthorized tool call blocked: " + toolCall.name());
			}
		}
	}

}
