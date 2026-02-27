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
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelRequest;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelResponse;
import com.alibaba.cloud.ai.graph.agent.tools.ToolContextConstants;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class PolicyCheckModelInterceptorTest {

	@Test
	void shouldBlockUnauthorizedToolCallsFromModelResponse() {
		PolicyCheckModelInterceptor interceptor = new PolicyCheckModelInterceptor();
		ModelCallHandler handler = mock(ModelCallHandler.class);
		when(handler.call(any())).thenReturn(ModelResponse.of(assistantMessageWithToolCall("danger_tool")));

		OverAllState state = new OverAllState();
		state.updateState(Map.of(CodeactStateKeys.AVAILABLE_TOOL_NAMES, List.of("slot_collect")));

		ModelRequest request = buildRequest(state);

		assertThrows(IllegalStateException.class, () -> interceptor.interceptModel(request, handler));
	}

	@Test
	void shouldFilterToolDescriptionsByAllowlistBeforeModelCall() {
		PolicyCheckModelInterceptor interceptor = new PolicyCheckModelInterceptor();
		ModelCallHandler handler = mock(ModelCallHandler.class);
		when(handler.call(any())).thenReturn(ModelResponse.of(assistantMessageWithToolCall("slot_collect")));

		OverAllState state = new OverAllState();
		state.updateState(Map.of(CodeactStateKeys.AVAILABLE_TOOL_NAMES, List.of("slot_collect")));

		ModelRequest request = buildRequest(state);
		interceptor.interceptModel(request, handler);

		ArgumentCaptor<ModelRequest> captor = ArgumentCaptor.forClass(ModelRequest.class);
		verify(handler, times(1)).call(captor.capture());
		assertEquals(1, captor.getValue().getToolDescriptions().size());
		assertEquals("slot collect", captor.getValue().getToolDescriptions().get("slot_collect"));
	}

	private ModelRequest buildRequest(OverAllState state) {
		List<Message> messages = List.of(new UserMessage("help me"));
		return ModelRequest.builder()
				.messages(messages)
				.context(Map.of(ToolContextConstants.AGENT_STATE_CONTEXT_KEY, state))
				.toolDescriptions(Map.of(
						"slot_collect", "slot collect",
						"danger_tool", "danger tool"))
				.build();
	}

	private AssistantMessage assistantMessageWithToolCall(String toolName) {
		return AssistantMessage.builder()
				.content("")
				.toolCalls(List.of(new AssistantMessage.ToolCall("call-1", "function", toolName, "{}")))
				.build();
	}

}
