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

import com.alibaba.assistant.agent.controlplane.audit.AuditEvent;
import com.alibaba.assistant.agent.controlplane.audit.AuditEventService;
import com.alibaba.assistant.agent.runtime.agent.AssistantStateKeys;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallExecutionContext;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallHandler;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallRequest;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallResponse;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AuditToolInterceptorTest {

	@Test
	void shouldPersistAuditEventOnSuccess() {
		AuditEventService auditEventService = mock(AuditEventService.class);
		AuditToolInterceptor interceptor = new AuditToolInterceptor(auditEventService);
		ToolCallHandler handler = mock(ToolCallHandler.class);
		when(handler.call(any())).thenReturn(ToolCallResponse.of("leave_execute", "call-1", "{\"ok\":true}"));
		when(auditEventService.save(any(AuditEvent.class))).thenReturn(true);

		OverAllState state = new OverAllState();
		state.updateState(Map.of(
				AssistantStateKeys.ASSISTANT_UID, "assistant-1001",
				AssistantStateKeys.SYSTEM_CODE, "gougu_oa",
				AssistantStateKeys.CONVERSATION_PHASE, "CODEACT"));

		ToolCallRequest request = ToolCallRequest.builder()
				.toolName("leave_execute")
				.toolCallId("call-1")
				.arguments("{\"reason\":\"personal\"}")
				.context(Map.of())
				.executionContext(new ToolCallExecutionContext(
						RunnableConfig.builder().threadId("thread-1").checkPointId("cp-1").build(),
						state))
				.build();

		interceptor.interceptToolCall(request, handler);

		ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
		verify(auditEventService, times(1)).save(captor.capture());
		AuditEvent event = captor.getValue();
		assertEquals("leave_execute", event.getToolName());
		assertEquals("SUCCESS", event.getStatus());
		assertEquals("assistant-1001", event.getAssistantUid());
		assertEquals("gougu_oa", event.getSystemCode());
		assertNotNull(event.getTraceId());
		assertEquals(event.getTraceId(), state.value(AssistantStateKeys.AUDIT_TRACE_ID, String.class).orElse(null));
	}

	@Test
	void shouldPersistAuditEventOnFailureAndRethrow() {
		AuditEventService auditEventService = mock(AuditEventService.class);
		AuditToolInterceptor interceptor = new AuditToolInterceptor(auditEventService);
		ToolCallHandler handler = mock(ToolCallHandler.class);
		when(handler.call(any())).thenThrow(new IllegalStateException("boom"));
		when(auditEventService.save(any(AuditEvent.class))).thenReturn(true);

		OverAllState state = new OverAllState();
		state.updateState(Map.of(
				AssistantStateKeys.ASSISTANT_UID, "assistant-1001",
				AssistantStateKeys.SYSTEM_CODE, "gougu_oa",
				AssistantStateKeys.CONVERSATION_PHASE, "CODEACT"));

		ToolCallRequest request = ToolCallRequest.builder()
				.toolName("leave_execute")
				.toolCallId("call-1")
				.arguments("{}")
				.context(Map.of())
				.executionContext(new ToolCallExecutionContext(
						RunnableConfig.builder().threadId("thread-1").build(),
						state))
				.build();

		IllegalStateException error = assertThrows(IllegalStateException.class,
				() -> interceptor.interceptToolCall(request, handler));
		assertEquals("boom", error.getMessage());

		ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
		verify(auditEventService, times(1)).save(captor.capture());
		assertEquals("ERROR", captor.getValue().getStatus());
		assertTrue(captor.getValue().getErrorMessage().contains("boom"));
	}

}
