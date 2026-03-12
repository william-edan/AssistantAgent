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
package com.alibaba.assistant.agent.api.controller;

import com.alibaba.assistant.agent.api.security.AuthenticatedUserContext;
import com.alibaba.assistant.agent.runtime.agent.AssistantStateKeys;
import com.alibaba.cloud.ai.agent.studio.dto.AgentRunRequest;
import com.alibaba.cloud.ai.agent.studio.dto.messages.UserMessageDTO;
import com.alibaba.cloud.ai.agent.studio.loader.AgentLoader;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.Agent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatControllerAuthenticationTest {

	private final AgentLoader agentLoader = mock(AgentLoader.class);

	private final Agent agent = mock(Agent.class);

	private final ChatController controller = new ChatController(agentLoader, "grayscale_agent", "");

	@AfterEach
	void tearDown() {
		SecurityContextHolder.clearContext();
	}

	@Test
	void shouldRejectRunSseWhenAuthenticationMissing() {
		AgentRunRequest request = buildValidRunRequest("spoof-user");

		ResponseStatusException error = assertThrows(ResponseStatusException.class,
				() -> controller.runSse(request, null, "spoof-assistant", "spoof-system"));

		assertEquals(HttpStatus.UNAUTHORIZED, error.getStatusCode());
	}

	@Test
	@SuppressWarnings("unchecked")
	void shouldInjectConfiguredDefaultSpaceScopeIntoState() throws Exception {
		ChatController scopedController = new ChatController(agentLoader, "grayscale_agent", "", "finance-space", "test");
		SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
				new AuthenticatedUserContext("1001", 1L, "gougu_oa", "assistant-ui", "token-x"),
				"token-x",
				Collections.emptyList()));

		when(agentLoader.loadAgent("grayscale_agent")).thenReturn(agent);
		when(agent.stream(any(org.springframework.ai.chat.messages.UserMessage.class), any(RunnableConfig.class)))
				.thenReturn(Flux.empty());

		AgentRunRequest request = buildValidRunRequest("spoof-user");

		scopedController.runSse(request, null, "spoof-assistant", "spoof-system").blockLast();

		assertEquals("finance-space", request.stateDelta.get(AssistantStateKeys.SPACE_CODE));
		assertEquals("test", request.stateDelta.get(AssistantStateKeys.SPACE_ENVIRONMENT));

		ArgumentCaptor<RunnableConfig> runnableConfigCaptor = ArgumentCaptor.forClass(RunnableConfig.class);
		verify(agent).stream(any(org.springframework.ai.chat.messages.UserMessage.class), runnableConfigCaptor.capture());
		Map<String, Object> stateUpdate = (Map<String, Object>) runnableConfigCaptor.getValue()
				.metadata(RunnableConfig.STATE_UPDATE_METADATA_KEY)
				.orElse(Map.of());
		assertEquals("finance-space", stateUpdate.get(AssistantStateKeys.SPACE_CODE));
		assertEquals("test", stateUpdate.get(AssistantStateKeys.SPACE_ENVIRONMENT));
	}

	@Test
	@SuppressWarnings("unchecked")
	void shouldKeepExplicitSpaceScopeWhenDefaultScopeConfigured() throws Exception {
		ChatController scopedController = new ChatController(agentLoader, "grayscale_agent", "", "finance-space", "test");
		SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
				new AuthenticatedUserContext("1001", 1L, "gougu_oa", "assistant-ui", "token-x"),
				"token-x",
				Collections.emptyList()));

		when(agentLoader.loadAgent("grayscale_agent")).thenReturn(agent);
		when(agent.stream(any(org.springframework.ai.chat.messages.UserMessage.class), any(RunnableConfig.class)))
				.thenReturn(Flux.empty());

		AgentRunRequest request = buildValidRunRequest("spoof-user");
		request.stateDelta = new LinkedHashMap<>();
		request.stateDelta.put(AssistantStateKeys.SPACE_CODE, "explicit-space");
		request.stateDelta.put(AssistantStateKeys.SPACE_ENVIRONMENT, "prod");

		scopedController.runSse(request, null, "spoof-assistant", "spoof-system").blockLast();

		assertEquals("explicit-space", request.stateDelta.get(AssistantStateKeys.SPACE_CODE));
		assertEquals("prod", request.stateDelta.get(AssistantStateKeys.SPACE_ENVIRONMENT));

		ArgumentCaptor<RunnableConfig> runnableConfigCaptor = ArgumentCaptor.forClass(RunnableConfig.class);
		verify(agent).stream(any(org.springframework.ai.chat.messages.UserMessage.class), runnableConfigCaptor.capture());
		Map<String, Object> stateUpdate = (Map<String, Object>) runnableConfigCaptor.getValue()
				.metadata(RunnableConfig.STATE_UPDATE_METADATA_KEY)
				.orElse(Map.of());
		assertEquals("explicit-space", stateUpdate.get(AssistantStateKeys.SPACE_CODE));
		assertEquals("prod", stateUpdate.get(AssistantStateKeys.SPACE_ENVIRONMENT));
	}
	@Test
	@SuppressWarnings("unchecked")
	void shouldUseAuthenticatedIdentityAndInjectAgentAppScopeIntoState() throws Exception {
		SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
				new AuthenticatedUserContext("1001", 1L, "gougu_oa", "assistant-ui", "token-x"),
				"token-x",
				Collections.emptyList()));

		when(agentLoader.loadAgent("grayscale_agent")).thenReturn(agent);
		when(agent.stream(any(org.springframework.ai.chat.messages.UserMessage.class), any(RunnableConfig.class)))
				.thenReturn(Flux.empty());

		AgentRunRequest request = buildValidRunRequest("spoof-user");
		request.stateDelta = new LinkedHashMap<>();
		request.stateDelta.put(AssistantStateKeys.ASSISTANT_UID, "spoof-assistant");
		request.stateDelta.put(AssistantStateKeys.SYSTEM_CODE, "spoof-system");
		request.stateDelta.put(AssistantStateKeys.SPACE_CODE, "enterprise_default");

		controller.runSse(request, null, "spoof-assistant", "spoof-system").blockLast();

		assertEquals("1001", request.userId);
		assertEquals("1001", request.stateDelta.get(AssistantStateKeys.ASSISTANT_UID));
		assertEquals("gougu_oa", request.stateDelta.get(AssistantStateKeys.SYSTEM_CODE));
		assertEquals("grayscale_agent", request.stateDelta.get(AssistantStateKeys.AGENT_APP_CODE));
		assertEquals("enterprise_default", request.stateDelta.get(AssistantStateKeys.SPACE_CODE));

		ArgumentCaptor<RunnableConfig> runnableConfigCaptor = ArgumentCaptor.forClass(RunnableConfig.class);
		verify(agent).stream(any(org.springframework.ai.chat.messages.UserMessage.class), runnableConfigCaptor.capture());
		RunnableConfig runnableConfig = runnableConfigCaptor.getValue();
		assertEquals("1001", runnableConfig.metadata("user_id").orElse(null));

		Map<String, Object> stateUpdate = (Map<String, Object>) runnableConfig
				.metadata(RunnableConfig.STATE_UPDATE_METADATA_KEY)
				.orElse(Map.of());
		assertTrue(stateUpdate.containsKey(AssistantStateKeys.ASSISTANT_UID));
		assertTrue(stateUpdate.containsKey(AssistantStateKeys.SYSTEM_CODE));
		assertTrue(stateUpdate.containsKey(AssistantStateKeys.AGENT_APP_CODE));
		assertEquals("1001", stateUpdate.get(AssistantStateKeys.ASSISTANT_UID));
		assertEquals("gougu_oa", stateUpdate.get(AssistantStateKeys.SYSTEM_CODE));
		assertEquals("grayscale_agent", stateUpdate.get(AssistantStateKeys.AGENT_APP_CODE));
		assertEquals("enterprise_default", stateUpdate.get(AssistantStateKeys.SPACE_CODE));
	}

	private static AgentRunRequest buildValidRunRequest(String userId) {
		AgentRunRequest request = new AgentRunRequest();
		request.threadId = "thread-auth-1";
		request.userId = userId;
		request.newMessage = new UserMessageDTO("hello");
		return request;
	}

}
