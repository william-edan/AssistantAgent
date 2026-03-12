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
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallExecutionContext;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallHandler;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallRequest;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class HumanInTheLoopToolInterceptorTest {

	@Test
	void shouldBlockHighRiskToolWhenNotConfirmed() {
		HumanInTheLoopToolInterceptor interceptor = new HumanInTheLoopToolInterceptor(new ObjectMapper());
		ToolCallHandler handler = mock(ToolCallHandler.class);

		OverAllState state = new OverAllState();
		ToolMeta toolMeta = new ToolMeta();
		toolMeta.setRiskLevel("HIGH");
		toolMeta.setRequiresConfirm(true);
		state.updateState(Map.of(AssistantStateKeys.MATCHED_TOOL_META, toolMeta));

		ToolCallRequest request = ToolCallRequest.builder()
				.toolName("leave_execute")
				.toolCallId("call-1")
				.arguments("{\"reason\":\"personal\"}")
				.context(Map.of())
				.executionContext(new ToolCallExecutionContext(
						RunnableConfig.builder().threadId("thread-1").build(),
						state))
				.build();

		ToolCallResponse response = interceptor.interceptToolCall(request, handler);

		assertTrue(response.isError());
		assertEquals("CONFIRMING", state.value(AssistantStateKeys.CONVERSATION_PHASE, String.class).orElse(null));
		verify(handler, never()).call(any());
	}

	@Test
	void shouldAllowHighRiskToolWhenConfirmed() {
		HumanInTheLoopToolInterceptor interceptor = new HumanInTheLoopToolInterceptor(new ObjectMapper());
		ToolCallHandler handler = mock(ToolCallHandler.class);
		when(handler.call(any())).thenReturn(ToolCallResponse.of("leave_execute", "call-1", "{\"ok\":true}"));

		OverAllState state = new OverAllState();
		ToolMeta toolMeta = new ToolMeta();
		toolMeta.setRiskLevel("HIGH");
		toolMeta.setRequiresConfirm(true);
		state.updateState(Map.of(AssistantStateKeys.MATCHED_TOOL_META, toolMeta));

		ToolCallRequest request = ToolCallRequest.builder()
				.toolName("leave_execute")
				.toolCallId("call-1")
				.arguments("{\"confirmed\":true}")
				.context(Map.of())
				.executionContext(new ToolCallExecutionContext(
						RunnableConfig.builder().threadId("thread-1").build(),
						state))
				.build();

		ToolCallResponse response = interceptor.interceptToolCall(request, handler);

		assertTrue(!response.isError());
		verify(handler, times(1)).call(any());
	}


	@Test
	void shouldSkipHumanConfirmationForInternalDependencyCall() {
		HumanInTheLoopToolInterceptor interceptor = new HumanInTheLoopToolInterceptor(new ObjectMapper());
		ToolCallHandler handler = mock(ToolCallHandler.class);
		when(handler.call(any())).thenReturn(ToolCallResponse.of("current_user", "call-1", "{\"ok\":true}"));

		OverAllState state = new OverAllState();
		ToolMeta toolMeta = new ToolMeta();
		toolMeta.setRiskLevel("HIGH");
		toolMeta.setRequiresConfirm(true);
		state.updateState(Map.of(AssistantStateKeys.MATCHED_TOOL_META, toolMeta));

		ToolCallRequest request = ToolCallRequest.builder()
				.toolName("current_user")
				.toolCallId("call-1")
				.arguments("{\"employeeId\":\"E001\"}")
				.context(Map.of(ToolExecutor.INTERNAL_DEPENDENCY_CALL_KEY, true))
				.executionContext(new ToolCallExecutionContext(
						RunnableConfig.builder().threadId("thread-1").build(),
						state))
				.build();

		ToolCallResponse response = interceptor.interceptToolCall(request, handler);

		assertTrue(!response.isError());
		verify(handler, times(1)).call(any());
	}
}


