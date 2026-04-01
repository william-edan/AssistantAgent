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

import com.alibaba.assistant.agent.api.controller.dto.ChatThreadStateData;
import com.alibaba.assistant.agent.api.security.AuthenticatedUserContext;
import com.alibaba.assistant.agent.api.service.ChatThreadStateService;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatControllerStructuredStateInputTest {

    private static final String FORM_PROMPT =
            "Please provide the leave time range, reason, and whether approvers should be notified.";

    private final AgentLoader agentLoader = mock(AgentLoader.class);

    private final Agent agent = mock(Agent.class);

    private final ChatThreadStateService chatThreadStateService = mock(ChatThreadStateService.class);

    private final ChatController controller = new ChatController(
            agentLoader,
            "grayscale_agent",
            "",
            "",
            "prod",
            null,
            null,
            null,
            chatThreadStateService,
            null,
            null);

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
        request.newMessage = new UserMessageDTO("supplement weekly report");
        request.stateDelta = new LinkedHashMap<>();
        request.stateDelta.put("works", "fixed report pipeline");
        request.stateDelta.put("plans", "finish integration tests next week");
        request.stateDelta.put("send", 0);
        request.stateDelta.put(AssistantStateKeys.ROLE_PACKAGE_CODE, "digital-admin");
        request.stateDelta.put(AssistantStateKeys.ROLE_PACKAGE_VERSION, "v1");

        controller.runSse(request, null, null, null).blockLast();

        ArgumentCaptor<Map<String, Object>> inputCaptor = ArgumentCaptor.forClass(Map.class);
        verify(agent).stream(inputCaptor.capture(), any(RunnableConfig.class));
        Map<String, Object> agentInput = inputCaptor.getValue();
        assertEquals("supplement weekly report", agentInput.get("input"));
        assertEquals("fixed report pipeline", agentInput.get("works"));
        assertEquals("finish integration tests next week", agentInput.get("plans"));
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
    void shouldExposeCurrentTurnUserInputInsideAgentInputDuringRunSse() throws Exception {
        authenticate();
        when(agentLoader.loadAgent("grayscale_agent")).thenReturn(agent);
        when(agent.stream(anyMap(), any(RunnableConfig.class))).thenReturn(Flux.empty());
        when(agent.stream(any(org.springframework.ai.chat.messages.UserMessage.class), any(RunnableConfig.class)))
                .thenReturn(Flux.empty());

        AgentRunRequest request = new AgentRunRequest();
        request.threadId = "thread-current-turn-input-run";
        request.userId = "ignored-user";
        request.newMessage = new UserMessageDTO("tomorrow");
        request.stateDelta = new LinkedHashMap<>();
        request.stateDelta.put(AssistantStateKeys.ROLE_PACKAGE_CODE, "digital-admin");
        request.stateDelta.put(AssistantStateKeys.ROLE_PACKAGE_VERSION, "v1");

        controller.runSse(request, null, null, null).blockLast();

        ArgumentCaptor<Map<String, Object>> inputCaptor = ArgumentCaptor.forClass(Map.class);
        verify(agent).stream(inputCaptor.capture(), any(RunnableConfig.class));
        Map<String, Object> agentInput = inputCaptor.getValue();
        assertEquals("tomorrow", agentInput.get("current_turn_user_input"));
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
        request.stateDelta.put("works", "resume payload");
        request.stateDelta.put("send", 1);
        request.stateDelta.put(AssistantStateKeys.ROLE_PACKAGE_CODE, "digital-admin");
        request.stateDelta.put(AssistantStateKeys.ROLE_PACKAGE_VERSION, "v1");

        controller.resumeSse(request, null, null, null).blockLast();

        ArgumentCaptor<Map<String, Object>> inputCaptor = ArgumentCaptor.forClass(Map.class);
        verify(agent).stream(inputCaptor.capture(), any(RunnableConfig.class));
        Map<String, Object> agentInput = inputCaptor.getValue();
        assertEquals("resume payload", agentInput.get("works"));
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
        when(scenarioRouter.resolveScenario(anyMap(), eq("start leave application")))
                .thenReturn(Optional.of("leave-approval"));

        AgentRunRequest request = new AgentRunRequest();
        request.threadId = "thread-role-scenario";
        request.userId = "ignored-user";
        request.newMessage = new UserMessageDTO("start leave application");
        request.stateDelta = new LinkedHashMap<>();
        request.stateDelta.put(AssistantStateKeys.ROLE_PACKAGE_CODE, "digital-admin");
        request.stateDelta.put(AssistantStateKeys.ROLE_PACKAGE_VERSION, "v1");

        routedController.runSse(request, null, null, null).blockLast();

        ArgumentCaptor<Map<String, Object>> inputCaptor = ArgumentCaptor.forClass(Map.class);
        verify(agent).stream(inputCaptor.capture(), any(RunnableConfig.class));
        assertEquals("leave-approval", inputCaptor.getValue().get(AssistantStateKeys.ROLE_SCENARIO_CODE));
    }

    @Test
    void shouldReplayPendingFormDuringRunSseWhenIncomingTextIsEmpty() throws Exception {
        authenticate();
        when(chatThreadStateService.getThreadState("thread-form-echo", "1001"))
                .thenReturn(pendingFormThreadState("thread-form-echo"));

        AgentRunRequest request = new AgentRunRequest();
        request.threadId = "thread-form-echo";
        request.userId = "ignored-user";
        request.newMessage = new UserMessageDTO("");
        request.stateDelta = new LinkedHashMap<>();

        List<String> payloads = controller.runSse(request, null, null, null)
                .map(event -> event.data() != null ? event.data() : "")
                .collectList()
                .block();

        assertEquals(2, payloads.size());
        assertTrue(payloads.stream().anyMatch(data -> data.contains("\"eventType\":\"FORM_STATE\"")));
        assertTrue(payloads.stream().anyMatch(data -> data.contains("\"source\":\"run_sse\"")));
        verify(agentLoader, never()).loadAgent(anyString());
        verify(agent, never()).stream(anyMap(), any(RunnableConfig.class));
    }

    @Test
    void shouldContinueAgentRunWhenPendingFormEchoAlsoContainsExplicitSlotInputs() throws Exception {
        authenticate();
        when(chatThreadStateService.getThreadState("thread-form-submit", "1001"))
                .thenReturn(pendingFormThreadState("thread-form-submit"));
        when(agentLoader.loadAgent("grayscale_agent")).thenReturn(agent);
        when(agent.stream(anyMap(), any(RunnableConfig.class))).thenReturn(Flux.empty());

        AgentRunRequest request = new AgentRunRequest();
        request.threadId = "thread-form-submit";
        request.userId = "ignored-user";
        request.newMessage = new UserMessageDTO(FORM_PROMPT);
        request.stateDelta = new LinkedHashMap<>();
        request.stateDelta.put("types", 2);
        request.stateDelta.put(AssistantStateKeys.ROLE_PACKAGE_CODE, "digital-admin");

        controller.runSse(request, null, null, null).blockLast();

        verify(agentLoader).loadAgent("grayscale_agent");
        verify(agent).stream(anyMap(), any(RunnableConfig.class));
    }

    @Test
    void shouldContinueAgentRunWhenPendingFormEchoContainsCurrentTurnSlotInputs() throws Exception {
        authenticate();
        when(chatThreadStateService.getThreadState("thread-form-submit-nested", "1001"))
                .thenReturn(pendingFormThreadState("thread-form-submit-nested"));
        when(agentLoader.loadAgent("grayscale_agent")).thenReturn(agent);
        when(agent.stream(anyMap(), any(RunnableConfig.class))).thenReturn(Flux.empty());

        AgentRunRequest request = new AgentRunRequest();
        request.threadId = "thread-form-submit-nested";
        request.userId = "ignored-user";
        request.newMessage = new UserMessageDTO(FORM_PROMPT);
        request.stateDelta = new LinkedHashMap<>();
        request.stateDelta.put(
                AssistantStateKeys.CURRENT_TURN_SLOT_INPUTS,
                Map.of("types", 2, "start_date", "2026-03-24", "end_date", "2026-03-24", "reason", "family"));
        request.stateDelta.put(AssistantStateKeys.ROLE_PACKAGE_CODE, "digital-admin");

        controller.runSse(request, null, null, null).blockLast();

        verify(agentLoader).loadAgent("grayscale_agent");
        verify(agent).stream(anyMap(), any(RunnableConfig.class));
    }

    @Test
    void shouldNotReplayTerminalReadOnlyFormDuringRunSse() throws Exception {
        authenticate();
        when(chatThreadStateService.getThreadState("thread-profile-done", "1001"))
                .thenReturn(completedFormThreadState("thread-profile-done"));
        when(agentLoader.loadAgent("grayscale_agent")).thenReturn(agent);
        when(agent.stream(anyMap(), any(RunnableConfig.class))).thenReturn(Flux.empty());

        AgentRunRequest request = new AgentRunRequest();
        request.threadId = "thread-profile-done";
        request.userId = "ignored-user";
        request.newMessage = new UserMessageDTO("show me zhangsan profile again");
        request.stateDelta = new LinkedHashMap<>();

        controller.runSse(request, null, null, null).blockLast();

        verify(agentLoader).loadAgent("grayscale_agent");
        verify(agent).stream(anyMap(), any(RunnableConfig.class));
    }

    private static ChatThreadStateData pendingFormThreadState(String threadId) {
        return new ChatThreadStateData(
                threadId,
                null,
                "WAITING_INPUT",
                "COLLECTING",
                true,
                true,
                "gougu_oa.leave_application",
                null,
                null,
                null,
                "FORM_CARD",
                0,
                0,
                null,
                FORM_PROMPT,
                Map.of(
                        "mode", "COLLECT",
                        "status", "WAITING_INPUT",
                        "phase", "COLLECTING",
                        "message", FORM_PROMPT,
                        "toolCode", "gougu_oa.leave_application",
                        "values", Map.of("check_uids", "4"),
                        "fields", List.of(Map.of("name", "types", "title", "leave type")),
                        "missingFields", List.of(Map.of("name", "types")),
                        "summary", Map.of(),
                        "canSubmit", false),
                Map.of(),
                List.of(),
                List.of(),
                Map.of());
    }

    private static ChatThreadStateData completedFormThreadState(String threadId) {
        Map<String, Object> pendingForm = new LinkedHashMap<>();
        pendingForm.put("mode", "DISPLAY");
        pendingForm.put("status", "COMPLETED");
        pendingForm.put("phase", "DONE");
        pendingForm.put("readOnly", true);
        pendingForm.put("message", "employeeName: zhangsan");
        pendingForm.put("toolCode", "profile_query");
        pendingForm.put("values", Map.of("employeeName", "zhangsan"));
        pendingForm.put("fields", List.of(Map.of("name", "employeeName", "title", "employeeName")));
        pendingForm.put("missingFields", List.of());
        pendingForm.put("summary", Map.of());
        pendingForm.put("canSubmit", false);
        return new ChatThreadStateData(
                threadId,
                null,
                "COMPLETED",
                "DONE",
                false,
                false,
                "profile_query",
                null,
                null,
                null,
                "FORM_CARD",
                0,
                0,
                null,
                "employeeName: zhangsan",
                pendingForm,
                Map.of(),
                List.of(),
                List.of(),
                Map.of());
    }

    private static void authenticate() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new AuthenticatedUserContext("1001", 1L, "gougu_oa", "assistant-ui", "token-x"),
                "token-x",
                Collections.emptyList()));
    }
}
