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

import com.alibaba.assistant.agent.runtime.agent.AssistantStateKeys;
import com.alibaba.assistant.agent.runtime.task.AgentTaskProjector;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallHandler;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallRequest;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallResponse;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolInterceptor;
import com.alibaba.cloud.ai.graph.agent.tools.ToolContextConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工具调用拦截器。
 *
 * <p>该拦截器会把对前端公开的协议数据投影回 Agent 状态，
 * 这样持久化检查点在恢复时才能正确重建未完成会话。
 */
@Component
@Profile("migration")
public class V3ProtocolToolInterceptor extends ToolInterceptor {

	private static final Logger logger = LoggerFactory.getLogger(V3ProtocolToolInterceptor.class);

	public static final String STATE_KEY_V3_PROTOCOL_EVENTS = "v3_protocol_events";

	private final V3ProtocolAdapter v3ProtocolAdapter;

	private final AgentTaskProjector agentTaskProjector;

	public V3ProtocolToolInterceptor(
			V3ProtocolAdapter v3ProtocolAdapter,
			@Nullable AgentTaskProjector agentTaskProjector) {
		this.v3ProtocolAdapter = v3ProtocolAdapter;
		this.agentTaskProjector = agentTaskProjector;
	}

	@Override
	public ToolCallResponse interceptToolCall(ToolCallRequest request, ToolCallHandler handler) {
		ToolCallResponse response = handler.call(request);
		if (request == null || response == null) {
			return response;
		}

		try {
			OverAllState state = extractState(request);
			Map<String, Object> stateView = snapshotState(state);
			String threadId = resolveThreadId(request, stateView);
			List<FrontendEvent> events = v3ProtocolAdapter.adapt(
					threadId,
					request.getToolName(),
					response.getResult(),
					stateView);
			Map<String, Object> projectedThreadState = v3ProtocolAdapter.projectThreadState(
					request.getToolName(),
					response.getResult(),
					stateView);
			projectTaskState(threadId, stateView, events);
			appendProjectionToState(state, events, projectedThreadState);
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
	private void appendProjectionToState(
			OverAllState state,
			List<FrontendEvent> events,
			Map<String, Object> projectedThreadState) {
		if (state == null) {
			return;
		}

		Map<String, Object> updates = new HashMap<>();
		List<Object> existing = state.value(STATE_KEY_V3_PROTOCOL_EVENTS, List.class).orElse(null);
		List<FrontendEvent> merged = new ArrayList<>();
		if (existing != null) {
			for (Object item : existing) {
				if (item instanceof FrontendEvent frontendEvent) {
					merged.add(frontendEvent);
				}
			}
		}
		if (events != null && !events.isEmpty()) {
			merged.addAll(events);
			updates.put(STATE_KEY_V3_PROTOCOL_EVENTS, merged);
		}
		if (projectedThreadState != null && !projectedThreadState.isEmpty()) {
			updates.put(AssistantStateKeys.FRONTEND_THREAD_STATE, projectedThreadState);
		}
		if (!updates.isEmpty()) {
			state.updateState(updates);
		}
	}

	private Map<String, Object> snapshotState(OverAllState state) {
		Map<String, Object> snapshot = new LinkedHashMap<>();
		if (state == null) {
			return snapshot;
		}
		copyValue(state, snapshot, AssistantStateKeys.ASSISTANT_UID);
		copyValue(state, snapshot, AssistantStateKeys.THREAD_ID);
		copyValue(state, snapshot, AssistantStateKeys.SYSTEM_CODE);
		copyValue(state, snapshot, AssistantStateKeys.AGENT_APP_CODE);
		copyValue(state, snapshot, AssistantStateKeys.MATCHED_TOOL_META);
		copyValue(state, snapshot, AssistantStateKeys.COLLECTED_SLOTS);
		copyValue(state, snapshot, AssistantStateKeys.ENRICHED_SLOTS);
		copyValue(state, snapshot, AssistantStateKeys.CONVERSATION_PHASE);
		copyValue(state, snapshot, AssistantStateKeys.EXECUTION_RESULT);
		copyValue(state, snapshot, AssistantStateKeys.FRONTEND_THREAD_STATE);
		return snapshot;
	}

	private void projectTaskState(String threadId, Map<String, Object> stateView, List<FrontendEvent> events) {
		if (agentTaskProjector == null || events == null || events.isEmpty()) {
			return;
		}
		for (FrontendEvent event : events) {
			if (event == null || event.eventType() != FrontendEventType.TASK_STATE || event.payload() == null) {
				continue;
			}
			agentTaskProjector.recordTaskState(threadId, stateView, event.payload());
		}
	}

	private void copyValue(OverAllState state, Map<String, Object> target, String key) {
		state.value(key, Object.class).ifPresent(value -> target.put(key, value));
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

	private String resolveThreadId(ToolCallRequest request, Map<String, Object> stateView) {
		String threadId = asText(stateView.get(AssistantStateKeys.THREAD_ID));
		if (StringUtils.hasText(threadId)) {
			return threadId;
		}
		threadId = asText(invokeNoArg(request, "getThreadId"));
		if (StringUtils.hasText(threadId)) {
			return threadId;
		}
		threadId = asText(invokeNoArg(request, "threadId"));
		if (StringUtils.hasText(threadId)) {
			return threadId;
		}
		return null;
	}

	private String asText(Object value) {
		if (value == null) {
			return null;
		}
		String text = String.valueOf(value).trim();
		return StringUtils.hasText(text) ? text : null;
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
