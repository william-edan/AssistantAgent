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

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallExecutionContext;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallHandler;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallRequest;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallResponse;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class InternalPromptContributionToolInterceptorTest {

	@Test
	void shouldShortCircuitInternalPromptContributionToolCall() {
		InternalPromptContributionToolInterceptor interceptor = new InternalPromptContributionToolInterceptor();
		ToolCallHandler handler = mock(ToolCallHandler.class);

		ToolCallRequest request = request("__prompt_contribution__", "{}");
		ToolCallResponse response = interceptor.interceptToolCall(request, handler);

		assertFalse(response.isError());
		assertTrue(response.getResult().contains("\"ignored\":true"));
		verify(handler, never()).call(any());
	}

	@Test
	void shouldDelegateForRegularToolCall() {
		InternalPromptContributionToolInterceptor interceptor = new InternalPromptContributionToolInterceptor();
		ToolCallHandler handler = mock(ToolCallHandler.class);
		when(handler.call(any())).thenReturn(ToolCallResponse.of("slot_collect", "call-1", "{\"ok\":true}"));

		ToolCallRequest request = request("slot_collect", "{\"toolCode\":\"leave_apply\"}");
		ToolCallResponse response = interceptor.interceptToolCall(request, handler);

		assertFalse(response.isError());
		assertTrue(response.getResult().contains("\"ok\":true"));
		verify(handler, times(1)).call(any());
	}

	private ToolCallRequest request(String toolName, String args) {
		OverAllState state = new OverAllState();
		return ToolCallRequest.builder()
				.toolName(toolName)
				.toolCallId("call-1")
				.arguments(args)
				.context(Map.of())
				.executionContext(new ToolCallExecutionContext(
						RunnableConfig.builder().threadId("thread-1").build(),
						state))
				.build();
	}

}
