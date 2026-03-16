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
import com.alibaba.assistant.agent.runtime.agent.AssistantStateKeys;
import com.alibaba.assistant.agent.runtime.compiler.RuntimeArtifact;
import com.alibaba.assistant.agent.runtime.config.AssistantRuntimeProperties;
import com.alibaba.assistant.agent.runtime.config.RuntimeConfigView;
import com.alibaba.assistant.agent.runtime.guard.BudgetTracker;
import com.alibaba.assistant.agent.runtime.registry.ArtifactPublicationLookupService;
import com.alibaba.assistant.agent.runtime.registry.PublishedToolDescriptor;
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
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PolicyGuardToolInterceptorTest {

    @Test
    void shouldBlockWhenBudgetExceeded() {
        PolicyGuardToolInterceptor interceptor = createInterceptor(true, 1, 12_000L);
        ToolCallHandler handler = mock(ToolCallHandler.class);

        OverAllState state = new OverAllState();
        state.updateState(Map.of(
                "_budget_start_ms", System.currentTimeMillis(),
                "_budget_tool_calls", 1));

        ToolCallResponse response = interceptor.interceptToolCall(request("leave_execute", "{}", state), handler);

        assertNotNull(response);
        verify(handler, never()).call(any());
    }

    @Test
    void shouldBlockWhenConfirmationMissingForRiskyStateTool() {
        PolicyGuardToolInterceptor interceptor = createInterceptor(true, 6, 12_000L);
        ToolCallHandler handler = mock(ToolCallHandler.class);

        OverAllState state = new OverAllState();
        ToolMeta meta = new ToolMeta();
        meta.setToolCode("risky_query");
        meta.setRiskLevel("MEDIUM");
        meta.setRequiresConfirm(true);
        state.updateState(Map.of(
                CodeactStateKeys.AVAILABLE_TOOL_NAMES, List.of("risky_query"),
                AssistantStateKeys.MATCHED_TOOL_META, meta));

        ToolCallResponse response = interceptor.interceptToolCall(
                request("risky_query", "{\"reason\":\"personal\"}", state),
                handler);

        assertNotNull(response);
        assertEquals("CONFIRMING", state.value(AssistantStateKeys.CONVERSATION_PHASE, String.class).orElse(null));
        assertTrue(response.getResult().contains("Confirmation required"));
        verify(handler, never()).call(any());
    }

    @Test
    void shouldPassAndRecordToolCallWhenAllowed() {
        PolicyGuardToolInterceptor interceptor = createInterceptor(true, 6, 12_000L);
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

        ToolCallResponse response = interceptor.interceptToolCall(
                request("leave_execute", "{\"confirmed\":true}", state),
                handler);

        assertEquals("{\"ok\":true}", response.getResult());
        assertEquals(1, state.value("_budget_tool_calls", Integer.class).orElse(0));
        verify(handler, times(1)).call(any());
    }

    @Test
    void shouldRejectLegacyExecuteAliasWhenOnlyArtifactExecuteIsAllowed() {
        PolicyGuardToolInterceptor interceptor = createInterceptor(true, 6, 12_000L);
        ToolCallHandler handler = mock(ToolCallHandler.class);

        OverAllState state = new OverAllState();
        state.updateState(Map.of(
                CodeactStateKeys.AVAILABLE_TOOL_NAMES, List.of("artifact_execute"),
                AssistantStateKeys.EXECUTION_CONFIRM_GRANTED, true,
                AssistantStateKeys.EXECUTION_CONFIRM_TOOL_NAME, "artifact_execute"));

        ToolCallResponse response = interceptor.interceptToolCall(
                request("gougu_oa.leave_application_execute", "{\"confirmed\":true}", state),
                handler);

        assertNotNull(response);
        assertTrue(response.getResult().contains("Tool not allowed by whitelist"));
        verify(handler, never()).call(any());
    }

    @Test
    void shouldPassArtifactExecuteThroughWhenHookHandlesConfirmation() {
        PolicyGuardToolInterceptor interceptor = createInterceptor(true, 6, 12_000L);
        ToolCallHandler handler = mock(ToolCallHandler.class);
        when(handler.call(any())).thenReturn(ToolCallResponse.of(
                "artifact_execute",
                "call-1",
                "{\"ok\":true}"));

        OverAllState state = new OverAllState();
        state.updateState(Map.of(
                CodeactStateKeys.AVAILABLE_TOOL_NAMES, List.of("artifact_execute")));

        ToolCallResponse response = interceptor.interceptToolCall(
                request("artifact_execute", "{\"toolCode\":\"gougu_oa.leave_application\",\"confirmed\":true}", state, "call-1"),
                handler);

        assertEquals("{\"ok\":true}", response.getResult());
        verify(handler, times(1)).call(any());
    }

    @Test
    void shouldBlockAllToolsWhenAllowlistExplicitlyEmpty() {

        PolicyGuardToolInterceptor interceptor = createInterceptor(true, 6, 12_000L);
        ToolCallHandler handler = mock(ToolCallHandler.class);

        OverAllState state = new OverAllState();
        state.updateState(Map.of(CodeactStateKeys.AVAILABLE_TOOL_NAMES, List.of()));

        ToolCallResponse response = interceptor.interceptToolCall(
                request("artifact_execute", "{\"toolCode\":\"gougu_oa.leave_application\",\"confirmed\":true}", state, "call-1"),
                handler);

        assertNotNull(response);
        assertTrue(response.getResult().contains("Tool not allowed by whitelist"));
        verify(handler, never()).call(any());
    }

    @Test
    void shouldBlockWhenPublishedArtifactGovernanceRequiresConfirmWithoutStateMeta() {
        InterceptorFixture fixture = createArtifactAwareInterceptor(true, 6, 12_000L);
        ToolCallHandler handler = mock(ToolCallHandler.class);
        when(fixture.lookupService().findPublishedArtifact(eq("oa.leave.apply"), any()))
                .thenReturn(Optional.of(publishedArtifact("oa.leave.apply", "gougu_oa", null, "HIGH", null)));

        OverAllState state = scopedState();
        state.updateState(Map.of(CodeactStateKeys.AVAILABLE_TOOL_NAMES, List.of("oa.leave.apply")));

        ToolCallResponse response = fixture.interceptor().interceptToolCall(
                request("oa.leave.apply", "{\"reason\":\"personal\"}", state),
                handler);

        assertNotNull(response);
        assertTrue(response.getResult().contains("Confirmation required"));
        verify(handler, never()).call(any());
    }

    @Test
    void shouldAllowWhenPublishedArtifactMissingAndNoStateGovernanceExists() {
        InterceptorFixture fixture = createArtifactAwareInterceptor(true, 6, 12_000L);
        ToolCallHandler handler = mock(ToolCallHandler.class);
        when(handler.call(any())).thenReturn(ToolCallResponse.of("oa.leave.apply", "call-1", "{\"ok\":true}"));
        when(fixture.lookupService().findPublishedArtifact(eq("oa.leave.apply"), any()))
                .thenReturn(Optional.empty());

        OverAllState state = scopedState();
        state.updateState(Map.of(CodeactStateKeys.AVAILABLE_TOOL_NAMES, List.of("oa.leave.apply")));

        ToolCallResponse response = fixture.interceptor().interceptToolCall(
                request("oa.leave.apply", "{\"reason\":\"personal\"}", state),
                handler);

        assertEquals("{\"ok\":true}", response.getResult());
        verify(handler, times(1)).call(any());
    }

    private PolicyGuardToolInterceptor createInterceptor(boolean enabled, int maxToolCalls, long maxLatencyMs) {
        RuntimeConfigView adapter = createAdapter(enabled, maxToolCalls, maxLatencyMs);
        return new PolicyGuardToolInterceptor(new ObjectMapper(), new BudgetTracker(adapter), adapter);
    }

    private InterceptorFixture createArtifactAwareInterceptor(boolean enabled, int maxToolCalls, long maxLatencyMs) {
        RuntimeConfigView adapter = createAdapter(enabled, maxToolCalls, maxLatencyMs);
        ArtifactPublicationLookupService lookupService = mock(ArtifactPublicationLookupService.class);
        PolicyGuardToolInterceptor interceptor = new PolicyGuardToolInterceptor(
                new ObjectMapper(),
                new BudgetTracker(adapter),
                adapter,
                lookupService);
        return new InterceptorFixture(interceptor, lookupService);
    }

    private RuntimeConfigView createAdapter(boolean enabled, int maxToolCalls, long maxLatencyMs) {
        MockEnvironment environment = new MockEnvironment();
        AssistantRuntimeProperties properties = new AssistantRuntimeProperties();
        properties.getPolicyGuard().setEnabled(enabled);
        properties.getBudget().setMaxToolCalls(maxToolCalls);
        properties.getBudget().setMaxLatencyMs(maxLatencyMs);
        return new RuntimeConfigView(properties);
    }

    private OverAllState scopedState() {
        OverAllState state = new OverAllState();
        state.updateState(Map.of(
                "tenant_id", "default",
                AssistantStateKeys.SPACE_ID, 9L,
                AssistantStateKeys.SPACE_ENVIRONMENT, "prod",
                AssistantStateKeys.AGENT_APP_CODE, "finance-agent"));
        return state;
    }

    private PublishedToolDescriptor publishedArtifact(
            String artifactCode,
            String systemCode,
            String confirmationPolicyJson,
            String riskLevel,
            Long approvalPolicyId) {
        RuntimeArtifact.Interaction interaction = confirmationPolicyJson != null
                ? new RuntimeArtifact.Interaction(
                        1L,
                        artifactCode + ".interaction",
                        null,
                        null,
                        confirmationPolicyJson)
                : null;
        Map<String, RuntimeArtifact.ActionBinding> actions = riskLevel != null || approvalPolicyId != null
                ? Map.of("submit", new RuntimeArtifact.ActionBinding(
                        1L,
                        artifactCode + ".submit",
                        1L,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        riskLevel,
                        approvalPolicyId,
                        null,
                        1))
                : Map.of();
        RuntimeArtifact artifact = new RuntimeArtifact(
                1L,
                artifactCode,
                RuntimeArtifact.ArtifactType.WORKFLOW,
                artifactCode,
                1,
                null,
                null,
                null,
                null,
                interaction,
                null,
                actions,
                Map.of());
        return PublishedToolDescriptor.forArtifact(
                "tool-meta-catalog",
                "workflow:" + artifactCode,
                artifactCode,
                null,
                null,
                false,
                systemCode,
                artifact);
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

    private record InterceptorFixture(
            PolicyGuardToolInterceptor interceptor,
            ArtifactPublicationLookupService lookupService) {

    }
}



