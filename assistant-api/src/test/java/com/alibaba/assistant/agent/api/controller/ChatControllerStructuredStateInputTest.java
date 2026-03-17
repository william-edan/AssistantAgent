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
import com.alibaba.assistant.agent.runtime.role.ScenarioRouter;
import com.alibaba.cloud.ai.agent.studio.dto.AgentResumeRequest;
import com.alibaba.cloud.ai.agent.studio.dto.AgentRunRequest;
import com.alibaba.cloud.ai.agent.studio.dto.messages.UserMessageDTO;
import com.alibaba.cloud.ai.agent.studio.loader.AgentLoader;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.Agent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import reactor.core.publisher.Flux;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatControllerStructuredStateInputTest {

    private final AgentLoader agentLoader = mock(AgentLoader.class);

    private final Agent agent = mock(Agent.class);

    private final ChatController controller = new ChatController(agentLoader, "grayscale_agent", "");

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void shouldSendStructuredStateInsideAgentInputDuringRunSse() throws Exception {
        authenticate();
        when(agentLoader.loadAgent("grayscale_agent")).thenReturn(agent);
        when(agent.stream(anyMap(), any(RunnableConfig.class))).thenReturn(Flux.empty());
        when(agent.stream(any(org.springframework.ai.chat.messages.UserMessage.class), any(RunnableConfig.class)))
                .thenReturn(Flux.empty());

        AgentRunRequest request = new AgentRunRequest();
        request.threadId = "thread-structured-run";
        request.userId = "ignored-user";
        request.newMessage = new UserMessageDTO("补充汇报内容");
        request.stateDelta = new LinkedHashMap<>();
        request.stateDelta.put("works", "本周完成多轮汇报主链修复");
        request.stateDelta.put("plans", "下周补全真实联调");
        request.stateDelta.put("send", 0);
        request.stateDelta.put(AssistantStateKeys.ROLE_PACKAGE_CODE, "digital-admin");
        request.stateDelta.put(AssistantStateKeys.ROLE_PACKAGE_VERSION, "v1");

        controller.runSse(request, null, null, null).blockLast();

        ArgumentCaptor<Map<String, Object>> inputCaptor = ArgumentCaptor.forClass(Map.class);
        verify(agent).stream(inputCaptor.capture(), any(RunnableConfig.class));
        Map<String, Object> agentInput = inputCaptor.getValue();
        assertEquals("补充汇报内容", agentInput.get("input"));
        assertEquals("本周完成多轮汇报主链修复", agentInput.get("works"));
        assertEquals("下周补全真实联调", agentInput.get("plans"));
        assertEquals(0, agentInput.get("send"));
        assertEquals("1001", agentInput.get(AssistantStateKeys.ASSISTANT_UID));
        assertEquals("gougu_oa", agentInput.get(AssistantStateKeys.SYSTEM_CODE));
        assertEquals("grayscale_agent", agentInput.get(AssistantStateKeys.AGENT_APP_CODE));
        assertEquals("digital-admin", agentInput.get(AssistantStateKeys.ROLE_PACKAGE_CODE));
        assertEquals("v1", agentInput.get(AssistantStateKeys.ROLE_PACKAGE_VERSION));
        assertTrue(agentInput.containsKey(AssistantStateKeys.CURRENT_TURN_SLOT_INPUTS));
        assertInstanceOf(List.class, agentInput.get("messages"));
    }

    @Test
    void shouldSendStructuredStateInsideAgentInputDuringResumeSse() throws Exception {
        authenticate();
        when(agentLoader.loadAgent("grayscale_agent")).thenReturn(agent);
        when(agent.stream(anyMap(), any(RunnableConfig.class))).thenReturn(Flux.empty());
        when(agent.stream(anyString(), any(RunnableConfig.class))).thenReturn(Flux.empty());

        AgentResumeRequest request = new AgentResumeRequest();
        request.threadId = "thread-structured-resume";
        request.userId = "ignored-user";
        request.stateDelta = new LinkedHashMap<>();
        request.stateDelta.put("works", "恢复后的汇报内容");
        request.stateDelta.put("send", 1);
        request.stateDelta.put(AssistantStateKeys.ROLE_PACKAGE_CODE, "digital-admin");
        request.stateDelta.put(AssistantStateKeys.ROLE_PACKAGE_VERSION, "v1");

        controller.resumeSse(request, null, null, null).blockLast();

        ArgumentCaptor<Map<String, Object>> inputCaptor = ArgumentCaptor.forClass(Map.class);
        verify(agent).stream(inputCaptor.capture(), any(RunnableConfig.class));
        Map<String, Object> agentInput = inputCaptor.getValue();
        assertEquals("恢复后的汇报内容", agentInput.get("works"));
        assertEquals(1, agentInput.get("send"));
        assertEquals("1001", agentInput.get(AssistantStateKeys.ASSISTANT_UID));
        assertEquals("gougu_oa", agentInput.get(AssistantStateKeys.SYSTEM_CODE));
        assertEquals("grayscale_agent", agentInput.get(AssistantStateKeys.AGENT_APP_CODE));
        assertEquals("digital-admin", agentInput.get(AssistantStateKeys.ROLE_PACKAGE_CODE));
        assertEquals("v1", agentInput.get(AssistantStateKeys.ROLE_PACKAGE_VERSION));
        assertTrue(agentInput.containsKey(AssistantStateKeys.CURRENT_TURN_SLOT_INPUTS));
    }

    @Test
    void shouldResolveRoleScenarioIntoAgentInputDuringRunSse() throws Exception {
        authenticate();
        ScenarioRouter scenarioRouter = mock(ScenarioRouter.class);
        ChatController routedController = new ChatController(
                agentLoader,
                "grayscale_agent",
                "",
                "",
                "prod",
                null,
                null,
                null,
                null,
                null,
                null,
                scenarioRouter);
        when(agentLoader.loadAgent("grayscale_agent")).thenReturn(agent);
        when(agent.stream(anyMap(), any(RunnableConfig.class))).thenReturn(Flux.empty());
        when(agent.stream(any(org.springframework.ai.chat.messages.UserMessage.class), any(RunnableConfig.class)))
                .thenReturn(Flux.empty());
        when(scenarioRouter.resolveScenario(anyMap(), eq("我要发起请假申请"))).thenReturn(Optional.of("leave-approval"));

        AgentRunRequest request = new AgentRunRequest();
        request.threadId = "thread-role-scenario";
        request.userId = "ignored-user";
        request.newMessage = new UserMessageDTO("我要发起请假申请");
        request.stateDelta = new LinkedHashMap<>();
        request.stateDelta.put(AssistantStateKeys.ROLE_PACKAGE_CODE, "digital-admin");
        request.stateDelta.put(AssistantStateKeys.ROLE_PACKAGE_VERSION, "v1");

        routedController.runSse(request, null, null, null).blockLast();

        ArgumentCaptor<Map<String, Object>> inputCaptor = ArgumentCaptor.forClass(Map.class);
        verify(agent).stream(inputCaptor.capture(), any(RunnableConfig.class));
        assertEquals("leave-approval", inputCaptor.getValue().get(AssistantStateKeys.ROLE_SCENARIO_CODE));
    }

    private static void authenticate() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new AuthenticatedUserContext("1001", 1L, "gougu_oa", "assistant-ui", "token-x"),
                "token-x",
                Collections.emptyList()));
    }

}

