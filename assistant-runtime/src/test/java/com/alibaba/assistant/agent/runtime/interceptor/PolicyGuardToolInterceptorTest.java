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
import com.alibaba.assistant.agent.runtime.config.AssistantRuntimeProperties;
import com.alibaba.assistant.agent.runtime.config.RuntimeConfigCompatibilityAdapter;
import com.alibaba.assistant.agent.runtime.guard.BudgetTracker;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallExecutionContext;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallHandler;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallRequest;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.env.MockEnvironment;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class PolicyGuardToolInterceptorTest {

	@Test
	void shouldBlockWhenBudgetExceeded() {
		PolicyGuardToolInterceptor interceptor = createInterceptor(true, 1, 12000L);
		ToolCallHandler handler = mock(ToolCallHandler.class);

		OverAllState state = new OverAllState();
		state.updateState(Map.of(
				"_budget_start_ms", System.currentTimeMillis(),
				"_budget_tool_calls", 1));

		ToolCallRequest request = request("leave_execute", "{}", state);
		ToolCallResponse response = interceptor.interceptToolCall(request, handler);

		assertNotNull(response);
		verify(handler, never()).call(any());
	}

	@Test
	void shouldBlockWhenConfirmationMissingForRiskyNonExecuteTool() {
		PolicyGuardToolInterceptor interceptor = createInterceptor(true, 6, 12000L);
		ToolCallHandler handler = mock(ToolCallHandler.class);

		OverAllState state = new OverAllState();
		ToolMeta meta = new ToolMeta();
		meta.setToolCode("risky_query");
		meta.setRiskLevel("MEDIUM");
		meta.setRequiresConfirm(true);
		state.updateState(Map.of(
				CodeactStateKeys.AVAILABLE_TOOL_NAMES, List.of("risky_query"),
				AssistantStateKeys.MATCHED_TOOL_META, meta));

		ToolCallRequest request = request("risky_query", "{\"reason\":\"personal\"}", state);
		ToolCallResponse response = interceptor.interceptToolCall(request, handler);

		assertNotNull(response);
		assertEquals("CONFIRMING", state.value(AssistantStateKeys.CONVERSATION_PHASE, String.class).orElse(null));
		verify(handler, never()).call(any());
	}

	@Test
	void shouldPassAndRecordToolCallWhenAllowed() {
		PolicyGuardToolInterceptor interceptor = createInterceptor(true, 6, 12000L);
		ToolCallHandler handler = mock(ToolCallHandler.class);
		when(handler.call(any())).thenReturn(ToolCallResponse.of("leave_execute", "call-1", "{\"ok\":true}"));

		OverAllState state = new OverAllState();
		ToolMeta meta = new ToolMeta();
		meta.setToolCode("leave_execute");
		meta.setRiskLevel("LOW");
		meta.setRequiresConfirm(false);
		state.updateState(Map.of(
				CodeactStateKeys.AVAILABLE_TOOL_NAMES, List.of("leave_execute"),
				AssistantStateKeys.MATCHED_TOOL_META, meta,
				AssistantStateKeys.EXECUTION_CONFIRM_GRANTED, true,
				AssistantStateKeys.EXECUTION_CONFIRM_TOOL_NAME, "leave_execute"));

		ToolCallRequest request = request("leave_execute", "{\"confirmed\":true}", state);
		ToolCallResponse response = interceptor.interceptToolCall(request, handler);

		assertEquals("{\"ok\":true}", response.getResult());
		assertEquals(1, state.value("_budget_tool_calls", Integer.class).orElse(0));
		verify(handler, times(1)).call(any());
	}

	@Test
	void shouldNormalizeDottedExecuteToolNameToAllowlistNameBeforeHandlerCall() {
		PolicyGuardToolInterceptor interceptor = createInterceptor(true, 6, 12000L);
		ToolCallHandler handler = mock(ToolCallHandler.class);
		when(handler.call(any())).thenReturn(ToolCallResponse.of(
				"gougu_oa_leave_application_execute",
				"call-1",
				"{\"ok\":true}"));

		OverAllState state = new OverAllState();
		state.updateState(Map.of(
				CodeactStateKeys.AVAILABLE_TOOL_NAMES, List.of("gougu_oa_leave_application_execute"),
				AssistantStateKeys.EXECUTION_CONFIRM_GRANTED, true,
				AssistantStateKeys.EXECUTION_CONFIRM_TOOL_NAME, "gougu_oa_leave_application_execute"));

		ToolCallRequest request = request(
				"gougu_oa.leave_application_execute",
				"{\"confirmed\":true}",
				state);

		ToolCallResponse response = interceptor.interceptToolCall(request, handler);

		assertEquals("{\"ok\":true}", response.getResult());
		ArgumentCaptor<ToolCallRequest> captor = ArgumentCaptor.forClass(ToolCallRequest.class);
		verify(handler, times(1)).call(captor.capture());
		assertEquals("gougu_oa_leave_application_execute", captor.getValue().getToolName());
	}

	@Test
	void shouldPassExecuteToolThroughWhenHookHandlesConfirmation() {
		PolicyGuardToolInterceptor interceptor = createInterceptor(true, 6, 12000L);
		ToolCallHandler handler = mock(ToolCallHandler.class);
		when(handler.call(any())).thenReturn(ToolCallResponse.of(
				"gougu_oa_leave_application_execute",
				"call-1",
				"{\"ok\":true}"));

		OverAllState state = new OverAllState();
		state.updateState(Map.of(
				CodeactStateKeys.AVAILABLE_TOOL_NAMES, List.of("gougu_oa_leave_application_execute")));

		ToolCallRequest request = request(
				"gougu_oa_leave_application_execute",
				"{\"reason\":\"personal\"}",
				state,
				"call-1");
		ToolCallResponse response = interceptor.interceptToolCall(request, handler);

		assertEquals("{\"ok\":true}", response.getResult());
		verify(handler, times(1)).call(any());
	}

	@Test
	void shouldBlockNonExecuteToolWhenGovernanceRequiresConfirmAndNotConfirmed() {
		PolicyGuardToolInterceptor interceptor = createInterceptor(true, 6, 12000L);
		ToolCallHandler handler = mock(ToolCallHandler.class);

		OverAllState state = new OverAllState();
		ToolMeta meta = new ToolMeta();
		meta.setToolCode("dangerous_query");
		meta.setRiskLevel("MEDIUM");
		meta.setRequiresConfirm(true);
		state.updateState(Map.of(
				CodeactStateKeys.AVAILABLE_TOOL_NAMES, List.of("dangerous_query"),
				AssistantStateKeys.MATCHED_TOOL_META, meta));

		ToolCallRequest request = request(
				"dangerous_query",
				"{\"sql\":\"DELETE FROM users\"}",
				state);
		ToolCallResponse response = interceptor.interceptToolCall(request, handler);

		assertNotNull(response);
		assertTrue(response.getResult().contains("Confirmation required"));
		verify(handler, never()).call(any());
	}

	@Test
	void shouldBlockAllToolsWhenAllowlistExplicitlyEmpty() {
		PolicyGuardToolInterceptor interceptor = createInterceptor(true, 6, 12000L);
		ToolCallHandler handler = mock(ToolCallHandler.class);

		OverAllState state = new OverAllState();
		state.updateState(Map.of(CodeactStateKeys.AVAILABLE_TOOL_NAMES, List.of()));

		ToolCallRequest request = request("gougu_oa_leave_application_execute", "{\"confirmed\":true}", state, "call-1");
		ToolCallResponse response = interceptor.interceptToolCall(request, handler);

		assertNotNull(response);
		assertTrue(response.getResult().contains("Tool not allowed by whitelist"));
		verify(handler, never()).call(any());
	}

	private PolicyGuardToolInterceptor createInterceptor(boolean enabled, int maxToolCalls, long maxLatencyMs) {
		MockEnvironment environment = new MockEnvironment();
		AssistantRuntimeProperties properties = new AssistantRuntimeProperties();
		properties.getPolicyGuard().setEnabled(enabled);
		properties.getBudget().setMaxToolCalls(maxToolCalls);
		properties.getBudget().setMaxLatencyMs(maxLatencyMs);
		RuntimeConfigCompatibilityAdapter adapter = new RuntimeConfigCompatibilityAdapter(properties, environment);
		BudgetTracker budgetTracker = new BudgetTracker(adapter);
		ToolMetaService toolMetaService = mock(ToolMetaService.class);
		return new PolicyGuardToolInterceptor(new ObjectMapper(), toolMetaService, budgetTracker, adapter);
	}

	private ToolCallRequest request(String toolName, String args, OverAllState state) {
		return request(toolName, args, state, "call-1");
	}

	private ToolCallRequest request(String toolName, String args, OverAllState state, String toolCallId) {
		return ToolCallRequest.builder()
				.toolName(toolName)
				.toolCallId(toolCallId)
				.arguments(args)
				.context(Map.of())
				.executionContext(new ToolCallExecutionContext(
						RunnableConfig.builder().threadId("thread-1").build(),
						state))
				.build();
	}

}
