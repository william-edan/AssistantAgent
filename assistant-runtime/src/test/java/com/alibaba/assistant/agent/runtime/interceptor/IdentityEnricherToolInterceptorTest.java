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

import com.alibaba.assistant.agent.controlplane.identity.TokenBroker;
import com.alibaba.assistant.agent.controlplane.identity.TokenLease;
import com.alibaba.assistant.agent.runtime.agent.AssistantStateKeys;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallExecutionContext;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallHandler;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallRequest;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class IdentityEnricherToolInterceptorTest {

	@Test
	void shouldInjectIdentityContextIntoRequestAndState() {
		TokenBroker tokenBroker = mock(TokenBroker.class);
		IdentityEnricherToolInterceptor interceptor = new IdentityEnricherToolInterceptor(tokenBroker, new ObjectMapper());
		ToolCallHandler handler = mock(ToolCallHandler.class);
		when(handler.call(any())).thenReturn(ToolCallResponse.of("leave_execute", "call-1", "{\"ok\":true}"));

		TokenLease lease = new TokenLease(
				"lease-1",
				"token-abc",
				"gougu_oa",
				"assistant-1001",
				LocalDateTime.now().plusMinutes(5));
		when(tokenBroker.acquire("assistant-1001", "gougu_oa")).thenReturn(Optional.of(lease));

		OverAllState state = new OverAllState();
		state.updateState(Map.of(
				AssistantStateKeys.ASSISTANT_UID, "assistant-1001",
				AssistantStateKeys.SYSTEM_CODE, "gougu_oa"));

		ToolCallRequest request = ToolCallRequest.builder()
				.toolName("leave_execute")
				.toolCallId("call-1")
				.arguments("{}")
				.context(Map.of())
				.executionContext(new ToolCallExecutionContext(
						RunnableConfig.builder().threadId("thread-1").build(),
						state))
				.build();

		interceptor.interceptToolCall(request, handler);

		verify(tokenBroker, times(1)).acquire("assistant-1001", "gougu_oa");
		ArgumentCaptor<ToolCallRequest> captor = ArgumentCaptor.forClass(ToolCallRequest.class);
		verify(handler, times(1)).call(captor.capture());

		@SuppressWarnings("unchecked")
		Map<String, Object> identityContext = state.value(AssistantStateKeys.IDENTITY_CONTEXT, Map.class).orElse(null);
		assertTrue(identityContext != null && !identityContext.isEmpty());
		assertEquals("token-abc", identityContext.get("access_token"));
		assertTrue(captor.getValue().getContext().containsKey(AssistantStateKeys.IDENTITY_CONTEXT));
	}

}
