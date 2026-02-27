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
package com.alibaba.assistant.agent.api.protocol;

import com.alibaba.assistant.agent.api.protocol.V3ProtocolAdapter.AssistantEvent;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallHandler;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallRequest;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallResponse;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolInterceptor;
import com.alibaba.cloud.ai.graph.agent.tools.ToolContextConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Tool interceptor that adapts tool outputs to v3 protocol events and stores
 * them in agent state for SSE/event-layer consumption.
 *
 * <p>This interceptor does not overwrite original tool outputs to avoid
 * breaking model reasoning in the React loop.</p>
 *
 * @author Assistant Agent Team
 * @since 1.0.0
 */
@Component
@Profile("migration")
public class V3ProtocolToolInterceptor extends ToolInterceptor {

	private static final Logger logger = LoggerFactory.getLogger(V3ProtocolToolInterceptor.class);

	public static final String STATE_KEY_V3_PROTOCOL_EVENTS = "v3_protocol_events";

	private final V3ProtocolAdapter v3ProtocolAdapter;

	public V3ProtocolToolInterceptor(V3ProtocolAdapter v3ProtocolAdapter) {
		this.v3ProtocolAdapter = v3ProtocolAdapter;
	}

	@Override
	public ToolCallResponse interceptToolCall(ToolCallRequest request, ToolCallHandler handler) {
		ToolCallResponse response = handler.call(request);
		if (request == null || response == null) {
			return response;
		}

		try {
			String toolName = request.getToolName();
			String toolOutput = response.getResult();
			List<AssistantEvent> events = v3ProtocolAdapter.adapt(toolName, toolOutput, (Map<String, Object>) null);
			appendEventsToState(request, events);
		}
		catch (Exception e) {
			logger.warn("V3ProtocolToolInterceptor#interceptToolCall - adapt failed, toolName={}, error={}",
					request.getToolName(), e.getMessage());
		}

		return response;
	}

	@Override
	public String getName() {
		return "V3ProtocolToolInterceptor";
	}

	@SuppressWarnings("unchecked")
	private void appendEventsToState(ToolCallRequest request, List<AssistantEvent> events) {
		if (events == null || events.isEmpty()) {
			return;
		}
		OverAllState state = extractState(request);
		if (state == null) {
			return;
		}

		List<Object> existing = state.value(STATE_KEY_V3_PROTOCOL_EVENTS, List.class).orElse(null);
		List<AssistantEvent> merged = new ArrayList<>();
		if (existing != null) {
			for (Object item : existing) {
				if (item instanceof AssistantEvent assistantEvent) {
					merged.add(assistantEvent);
				}
			}
		}
		merged.addAll(events);

		Map<String, Object> updates = new HashMap<>();
		updates.put(STATE_KEY_V3_PROTOCOL_EVENTS, merged);
		state.updateState(updates);
	}

	private OverAllState extractState(ToolCallRequest request) {
		Object candidate = invokeNoArg(request, "getState");
		if (candidate instanceof OverAllState overAllState) {
			return overAllState;
		}

		candidate = invokeNoArg(request, "getOverAllState");
		if (candidate instanceof OverAllState overAllState) {
			return overAllState;
		}

		candidate = invokeNoArg(request, "getAgentState");
		if (candidate instanceof OverAllState overAllState) {
			return overAllState;
		}

		candidate = invokeNoArg(request, "getContext");
		if (candidate instanceof Map<?, ?> context) {
			Object stateObject = context.get(ToolContextConstants.AGENT_STATE_CONTEXT_KEY);
			if (stateObject instanceof OverAllState overAllState) {
				return overAllState;
			}
		}
		return null;
	}

	private Object invokeNoArg(Object target, String methodName) {
		try {
			Method method = target.getClass().getMethod(methodName);
			method.setAccessible(true);
			return method.invoke(target);
		}
		catch (Exception ignored) {
			return null;
		}
	}
}
