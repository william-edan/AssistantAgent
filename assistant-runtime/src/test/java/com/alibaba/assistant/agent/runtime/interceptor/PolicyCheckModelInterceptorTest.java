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
import com.alibaba.assistant.agent.runtime.agent.AssistantStateKeys;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelCallHandler;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelRequest;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelResponse;
import com.alibaba.cloud.ai.graph.agent.tools.ToolContextConstants;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class PolicyCheckModelInterceptorTest {

	@Test
	void shouldBlockToolCallsWhenCollectingWithoutNewUserInput() {
		PolicyCheckModelInterceptor interceptor = new PolicyCheckModelInterceptor();
		ModelCallHandler handler = mock(ModelCallHandler.class);
		when(handler.call(any())).thenReturn(ModelResponse.of(assistantMessageWithToolCall("slot_collect")));

		OverAllState state = new OverAllState();
		state.updateState(Map.of(
				AssistantStateKeys.CONVERSATION_PHASE, "COLLECTING",
				AssistantStateKeys.LAST_COLLECT_USER_INPUT, "发起工作汇报",
				"input", "发起工作汇报",
				"jump_to", "tool",
				CodeactStateKeys.AVAILABLE_TOOL_NAMES, List.of("slot_collect", "slot_confirm")));

		ModelRequest request = buildRequest(state, Map.of("slot_collect", "slot collect"));
		ModelResponse response = interceptor.interceptModel(request, handler);

		ArgumentCaptor<ModelRequest> captor = ArgumentCaptor.forClass(ModelRequest.class);
		verify(handler, times(1)).call(captor.capture());
		assertTrue(captor.getValue().getToolDescriptions().isEmpty());
		assertTrue(captor.getValue().getTools().isEmpty());

		AssistantMessage message = (AssistantMessage) response.getMessage();
		assertFalse(message.hasToolCalls());
		assertTrue(message.getText().contains("请先补充缺失的必填内容"));
		assertTrue(state.value("jump_to", Object.class).isEmpty());
	}

	@Test
	void shouldKeepToolCallsWhenCollectingWithNewUserInput() {
		PolicyCheckModelInterceptor interceptor = new PolicyCheckModelInterceptor();
		ModelCallHandler handler = mock(ModelCallHandler.class);
		when(handler.call(any())).thenReturn(ModelResponse.of(assistantMessageWithToolCall("slot_collect")));

		OverAllState state = new OverAllState();
		state.updateState(Map.of(
				AssistantStateKeys.CONVERSATION_PHASE, "COLLECTING",
				AssistantStateKeys.LAST_COLLECT_USER_INPUT, "发起工作汇报",
				"input", "本周完成了项目A接口开发",
				CodeactStateKeys.AVAILABLE_TOOL_NAMES, List.of("slot_collect", "slot_confirm")));

		ModelRequest request = buildRequest(state, Map.of("slot_collect", "slot collect"));
		ModelResponse response = interceptor.interceptModel(request, handler);

		ArgumentCaptor<ModelRequest> captor = ArgumentCaptor.forClass(ModelRequest.class);
		verify(handler, times(1)).call(captor.capture());
		assertTrue(captor.getValue().getToolDescriptions().containsKey("slot_collect"));

		AssistantMessage message = (AssistantMessage) response.getMessage();
		assertTrue(message.hasToolCalls());
	}

	@Test
	void shouldBlockToolCallsWhenConfirmingWithoutNewUserInput() {
		PolicyCheckModelInterceptor interceptor = new PolicyCheckModelInterceptor();
		ModelCallHandler handler = mock(ModelCallHandler.class);
		when(handler.call(any())).thenReturn(ModelResponse.of(assistantMessageWithToolCall("slot_collect")));

		OverAllState state = new OverAllState();
		state.updateState(Map.of(
				AssistantStateKeys.CONVERSATION_PHASE, "CONFIRMING",
				AssistantStateKeys.LAST_COLLECT_USER_INPUT, "我需要发送日报 当天完成了动作的调试",
				"input", "我需要发送日报 当天完成了动作的调试",
				CodeactStateKeys.AVAILABLE_TOOL_NAMES, List.of("slot_collect", "slot_confirm")));

		ModelRequest request = buildRequest(state, Map.of(
				"slot_collect", "slot collect",
				"slot_confirm", "slot confirm"));
		ModelResponse response = interceptor.interceptModel(request, handler);

		ArgumentCaptor<ModelRequest> captor = ArgumentCaptor.forClass(ModelRequest.class);
		verify(handler, times(1)).call(captor.capture());
		assertTrue(captor.getValue().getToolDescriptions().isEmpty());
		assertTrue(captor.getValue().getTools().isEmpty());

		AssistantMessage message = (AssistantMessage) response.getMessage();
		assertFalse(message.hasToolCalls());
		assertTrue(message.getText().contains("确认提交"));
	}

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

	@Test
	void shouldTreatExplicitEmptyAllowlistAsDenyAll() {
		PolicyCheckModelInterceptor interceptor = new PolicyCheckModelInterceptor();
		ModelCallHandler handler = mock(ModelCallHandler.class);
		when(handler.call(any())).thenReturn(ModelResponse.of(assistantMessageWithToolCall("danger_tool")));

		OverAllState state = new OverAllState();
		state.updateState(Map.of(CodeactStateKeys.AVAILABLE_TOOL_NAMES, List.of()));

		ModelRequest request = buildRequest(state);
		assertThrows(IllegalStateException.class, () -> interceptor.interceptModel(request, handler));

		ArgumentCaptor<ModelRequest> captor = ArgumentCaptor.forClass(ModelRequest.class);
		verify(handler, times(1)).call(captor.capture());
		assertTrue(captor.getValue().getToolDescriptions().isEmpty());
	}

	@Test
	void shouldNotFilterArtifactExecuteFromModelContextWhenConfirming() {
		PolicyCheckModelInterceptor interceptor = new PolicyCheckModelInterceptor();
		ModelCallHandler handler = mock(ModelCallHandler.class);
		when(handler.call(any())).thenReturn(ModelResponse.of(assistantMessageWithToolCall("artifact_execute")));

		OverAllState state = new OverAllState();
		state.updateState(Map.of(
				AssistantStateKeys.CONVERSATION_PHASE, "CONFIRMING",
				CodeactStateKeys.AVAILABLE_TOOL_NAMES, List.of("slot_confirm", "artifact_execute")));

		ModelRequest request = buildRequest(state, Map.of(
				"slot_confirm", "slot confirm",
				"artifact_execute", "execute artifact"));
		interceptor.interceptModel(request, handler);

		ArgumentCaptor<ModelRequest> captor = ArgumentCaptor.forClass(ModelRequest.class);
		verify(handler, times(1)).call(captor.capture());
		assertEquals(2, captor.getValue().getToolDescriptions().size());
		assertTrue(captor.getValue().getToolDescriptions().containsKey("slot_confirm"));
		assertTrue(captor.getValue().getToolDescriptions().containsKey("artifact_execute"));
	}

	@Test
	void shouldSanitizeToolResponseMessageWhenResponsesIsNull() {
		PolicyCheckModelInterceptor interceptor = new PolicyCheckModelInterceptor();
		ModelCallHandler handler = mock(ModelCallHandler.class);
		when(handler.call(any())).thenReturn(ModelResponse.of(assistantMessageWithToolCall("slot_collect")));

		OverAllState state = new OverAllState();
		state.updateState(Map.of(CodeactStateKeys.AVAILABLE_TOOL_NAMES, List.of("slot_collect")));

		ToolResponseMessage invalidToolResponse = ToolResponseMessage.builder()
				.responses(null)
				.build();
		ModelRequest request = ModelRequest.builder()
				.messages(List.of(new UserMessage("confirm"), invalidToolResponse))
				.context(Map.of(ToolContextConstants.AGENT_STATE_CONTEXT_KEY, state))
				.toolDescriptions(Map.of("slot_collect", "slot collect"))
				.build();

		interceptor.interceptModel(request, handler);

		ArgumentCaptor<ModelRequest> captor = ArgumentCaptor.forClass(ModelRequest.class);
		verify(handler, times(1)).call(captor.capture());

		assertEquals(1, captor.getValue().getMessages().size());
		assertTrue(captor.getValue().getMessages().get(0) instanceof UserMessage);
	}

	@Test
	void shouldSanitizeToolResponseMessageWhenAllowlistNotConfigured() {
		PolicyCheckModelInterceptor interceptor = new PolicyCheckModelInterceptor();
		ModelCallHandler handler = mock(ModelCallHandler.class);
		when(handler.call(any())).thenReturn(ModelResponse.of(assistantMessageWithToolCall("slot_collect")));

		OverAllState state = new OverAllState();

		ToolResponseMessage invalidToolResponse = ToolResponseMessage.builder()
				.responses(null)
				.build();
		ModelRequest request = ModelRequest.builder()
				.messages(List.of(new UserMessage("confirm"), invalidToolResponse))
				.context(Map.of(ToolContextConstants.AGENT_STATE_CONTEXT_KEY, state))
				.toolDescriptions(Map.of("slot_collect", "slot collect"))
				.build();

		interceptor.interceptModel(request, handler);

		ArgumentCaptor<ModelRequest> captor = ArgumentCaptor.forClass(ModelRequest.class);
		verify(handler, times(1)).call(captor.capture());

		assertEquals(1, captor.getValue().getMessages().size());
		assertTrue(captor.getValue().getMessages().get(0) instanceof UserMessage);
	}

	@Test
	void shouldDropDanglingAssistantToolCallsWithoutMatchingToolResponses() {
		PolicyCheckModelInterceptor interceptor = new PolicyCheckModelInterceptor();
		ModelCallHandler handler = mock(ModelCallHandler.class);
		when(handler.call(any())).thenReturn(ModelResponse.of(assistantMessageWithToolCall("slot_collect")));

		OverAllState state = new OverAllState();
		state.updateState(Map.of(CodeactStateKeys.AVAILABLE_TOOL_NAMES, List.of("slot_collect")));

		AssistantMessage danglingToolCall = AssistantMessage.builder()
				.content("processing")
				.toolCalls(List.of(new AssistantMessage.ToolCall("call-missing", "function", "slot_collect", "{}")))
				.build();
		ModelRequest request = ModelRequest.builder()
				.messages(List.of(new UserMessage("confirm"), danglingToolCall))
				.context(Map.of(ToolContextConstants.AGENT_STATE_CONTEXT_KEY, state))
				.toolDescriptions(Map.of("slot_collect", "slot collect"))
				.build();

		interceptor.interceptModel(request, handler);

		ArgumentCaptor<ModelRequest> captor = ArgumentCaptor.forClass(ModelRequest.class);
		verify(handler, times(1)).call(captor.capture());

		assertEquals(2, captor.getValue().getMessages().size());
		assertTrue(captor.getValue().getMessages().get(1) instanceof AssistantMessage);
		AssistantMessage sanitizedAssistant = (AssistantMessage) captor.getValue().getMessages().get(1);
		assertTrue(!sanitizedAssistant.hasToolCalls());
	}

	@Test
	void shouldDropNonAdjacentToolResponsesForAssistantToolCalls() {
		PolicyCheckModelInterceptor interceptor = new PolicyCheckModelInterceptor();
		ModelCallHandler handler = mock(ModelCallHandler.class);
		when(handler.call(any())).thenReturn(ModelResponse.of(assistantMessageWithToolCall("slot_collect")));

		OverAllState state = new OverAllState();
		state.updateState(Map.of(CodeactStateKeys.AVAILABLE_TOOL_NAMES, List.of("slot_collect")));

		AssistantMessage assistantWithToolCall = AssistantMessage.builder()
				.content("processing")
				.toolCalls(List.of(new AssistantMessage.ToolCall("call-1", "function", "slot_collect", "{}")))
				.build();
		ToolResponseMessage lateToolResponse = ToolResponseMessage.builder()
				.responses(List.of(new ToolResponseMessage.ToolResponse("call-1", "slot_collect", "{\"ok\":true}")))
				.build();
		ModelRequest request = ModelRequest.builder()
				.messages(List.of(
						new UserMessage("confirm"),
						assistantWithToolCall,
						new UserMessage("inserted-between"),
						lateToolResponse))
				.context(Map.of(ToolContextConstants.AGENT_STATE_CONTEXT_KEY, state))
				.toolDescriptions(Map.of("slot_collect", "slot collect"))
				.build();

		interceptor.interceptModel(request, handler);

		ArgumentCaptor<ModelRequest> captor = ArgumentCaptor.forClass(ModelRequest.class);
		verify(handler, times(1)).call(captor.capture());

		List<Message> sanitizedMessages = captor.getValue().getMessages();
		assertEquals(3, sanitizedMessages.size());
		assertTrue(sanitizedMessages.get(1) instanceof AssistantMessage);
		AssistantMessage sanitizedAssistant = (AssistantMessage) sanitizedMessages.get(1);
		assertTrue(!sanitizedAssistant.hasToolCalls());
		assertTrue(sanitizedMessages.stream().noneMatch(ToolResponseMessage.class::isInstance));
	}

	private ModelRequest buildRequest(OverAllState state) {
		return buildRequest(state, Map.of(
				"slot_collect", "slot collect",
				"danger_tool", "danger tool"));
	}

	private ModelRequest buildRequest(OverAllState state, Map<String, String> toolDescriptions) {
		List<Message> messages = List.of(new UserMessage("help me"));
		return ModelRequest.builder()
				.messages(messages)
				.context(Map.of(ToolContextConstants.AGENT_STATE_CONTEXT_KEY, state))
				.toolDescriptions(toolDescriptions)
				.build();
	}

	private AssistantMessage assistantMessageWithToolCall(String toolName) {
		return AssistantMessage.builder()
				.content("")
				.toolCalls(List.of(new AssistantMessage.ToolCall("call-1", "function", toolName, "{}")))
				.build();
	}

}
