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

import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallHandler;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallRequest;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallResponse;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolInterceptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Locale;

/**
 * Guards internal prompt-contribution pseudo tool calls from reaching ToolCallback lookup.
 *
 * <p>The prompt contributor hook injects context messages and may leave internal tool names
 * in chat history. Some models may replay such names as executable tool calls.
 * This interceptor short-circuits those calls and returns a no-op response.
 *
 * @author Assistant Agent Team
 * @since 1.0.0
 */
@Component
@Profile("migration")
public class InternalPromptContributionToolInterceptor extends ToolInterceptor {

	private static final Logger logger = LoggerFactory.getLogger(InternalPromptContributionToolInterceptor.class);

	private static final String INTERNAL_TOOL_NAME = "__prompt_contribution__";

	private static final String INTERNAL_TOOL_KEYWORD = "prompt_contribution";

	private static final String NO_OP_RESULT =
			"{\"ignored\":true,\"reason\":\"internal_prompt_contribution_context_only\"}";

	@Override
	public ToolCallResponse interceptToolCall(ToolCallRequest request, ToolCallHandler handler) {
		String toolName = request != null ? request.getToolName() : null;
		if (!isInternalPromptContributionTool(toolName)) {
			return handler.call(request);
		}

		logger.warn(
				"InternalPromptContributionToolInterceptor#interceptToolCall - skip internal pseudo tool call, toolName={}",
				toolName);
		return ToolCallResponse.of(toolName, request.getToolCallId(), NO_OP_RESULT);
	}

	@Override
	public String getName() {
		return "InternalPromptContributionToolInterceptor";
	}

	private boolean isInternalPromptContributionTool(String toolName) {
		if (!StringUtils.hasText(toolName)) {
			return false;
		}
		String normalized = toolName.trim().toLowerCase(Locale.ROOT);
		return INTERNAL_TOOL_NAME.equals(normalized) || normalized.contains(INTERNAL_TOOL_KEYWORD);
	}

}
