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
package com.alibaba.assistant.agent.runtime.planner;

import com.alibaba.assistant.agent.controlplane.space.PlatformSpaceService;
import com.alibaba.assistant.agent.controlplane.toolregistry.ToolMeta;
import com.alibaba.assistant.agent.controlplane.toolregistry.ToolMetaService;
import com.alibaba.assistant.agent.execution.flow.DAGFlowExecutor;
import com.alibaba.assistant.agent.execution.flow.FlowContext;
import com.alibaba.assistant.agent.execution.flow.FlowDefinitionConverter;
import com.alibaba.assistant.agent.execution.model.StepConfig;
import com.alibaba.assistant.agent.execution.model.StepResult;
import com.alibaba.assistant.agent.execution.step.HttpStepExecutor;
import com.alibaba.assistant.agent.runtime.agent.AssistantStateKeys;
import com.alibaba.assistant.agent.runtime.compiler.RuntimeArtifact;
import com.alibaba.assistant.agent.runtime.execution.ArtifactRuntimeExecutor;
import com.alibaba.assistant.agent.runtime.registry.ArtifactPublicationLookupService;
import com.alibaba.assistant.agent.runtime.registry.PublicationScopeResolver;
import com.alibaba.assistant.agent.runtime.registry.PublishedToolDescriptor;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.agent.tools.ToolContextConstants;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.model.ToolContext;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ToolExecutorTest {

    @Test
    void shouldReturnErrorWhenToolMetaNotFound() {
        ToolMetaService toolMetaService = mock(ToolMetaService.class);
        FlowDefinitionConverter flowDefinitionConverter = mock(FlowDefinitionConverter.class);
        DAGFlowExecutor dagFlowExecutor = mock(DAGFlowExecutor.class);
        HttpStepExecutor httpStepExecutor = mock(HttpStepExecutor.class);
        ObjectMapper objectMapper = new ObjectMapper();

        when(toolMetaService.findLatestEnabledByToolCode("default", "current_user")).thenReturn(Optional.empty());

        ToolExecutor executor = new ToolExecutor(
                toolMetaService,
                flowDefinitionConverter,
                dagFlowExecutor,
                httpStepExecutor,
                objectMapper,
                Collections.emptyList());

        ToolExecutor.ExecutionResult result = executor.execute("default", "current_user", Map.of(), null);

        assertFalse(result.success());
        assertTrue(result.errorMessage().contains("not found"));
    }

    @Test
    void shouldExecuteSimpleModeAndExtractOutputs() {
        ToolMetaService toolMetaService = mock(ToolMetaService.class);
        FlowDefinitionConverter flowDefinitionConverter = mock(FlowDefinitionConverter.class);
        DAGFlowExecutor dagFlowExecutor = mock(DAGFlowExecutor.class);
        HttpStepExecutor httpStepExecutor = mock(HttpStepExecutor.class);
        ObjectMapper objectMapper = new ObjectMapper();

        ToolMeta currentUserTool = new ToolMeta();
        currentUserTool.setToolCode("current_user");
        currentUserTool.setSystemCode("oa");
        currentUserTool.setApiEndpoint("/api/current_user");
        currentUserTool.setHttpMethod("GET");
        currentUserTool.setContentType("application/json");
        when(toolMetaService.findLatestEnabledByToolCode("default", "current_user"))
                .thenReturn(Optional.of(currentUserTool));

        when(httpStepExecutor.execute(any(StepConfig.class), any(FlowContext.class)))
                .thenReturn(StepResult.success(Map.of("employeeId", "E001", "name", "Alice")));

        ToolExecutor executor = new ToolExecutor(
                toolMetaService,
                flowDefinitionConverter,
                dagFlowExecutor,
                httpStepExecutor,
                objectMapper,
                Collections.emptyList());

        OverAllState state = new OverAllState();
        state.updateState(Map.of(
                AssistantStateKeys.SYSTEM_CODE, "oa",
                AssistantStateKeys.ASSISTANT_UID, "u1",
                AssistantStateKeys.THREAD_ID, "thread-1"));
        ToolContext context = new ToolContext(Map.of(ToolContextConstants.AGENT_STATE_CONTEXT_KEY, state));

        ToolExecutor.ExecutionResult result = executor.execute("default", "current_user", Map.of(), context);

        assertTrue(result.success());
        assertEquals("E001", result.outputFields().get("employeeId"));
        assertEquals("Alice", result.outputFields().get("name"));
        verify(httpStepExecutor, times(1)).execute(any(StepConfig.class), any(FlowContext.class));
    }

    @Test
    void shouldUseCamelCaseIdentityFromArgumentsWhenStateMissing() {
        ToolMetaService toolMetaService = mock(ToolMetaService.class);
        FlowDefinitionConverter flowDefinitionConverter = mock(FlowDefinitionConverter.class);
        DAGFlowExecutor dagFlowExecutor = mock(DAGFlowExecutor.class);
        HttpStepExecutor httpStepExecutor = mock(HttpStepExecutor.class);
        ObjectMapper objectMapper = new ObjectMapper();

        ToolMeta currentUserTool = new ToolMeta();
        currentUserTool.setToolCode("current_user");
        currentUserTool.setApiEndpoint("/api/current_user");
        currentUserTool.setHttpMethod("GET");
        currentUserTool.setContentType("application/json");
        when(toolMetaService.findLatestEnabledByToolCode("default", "current_user"))
                .thenReturn(Optional.of(currentUserTool));

        when(httpStepExecutor.execute(any(StepConfig.class), any(FlowContext.class)))
                .thenReturn(StepResult.success(Map.of("employeeId", "E001")));

        ToolExecutor executor = new ToolExecutor(
                toolMetaService,
                flowDefinitionConverter,
                dagFlowExecutor,
                httpStepExecutor,
                objectMapper,
                Collections.emptyList());

        Map<String, Object> args = Map.of(
                "assistantUid", "u1",
                "systemCode", "oa");
        ToolExecutor.ExecutionResult result = executor.execute("default", "current_user", args, null);

        assertTrue(result.success());
        ArgumentCaptor<FlowContext> contextCaptor = ArgumentCaptor.forClass(FlowContext.class);
        verify(httpStepExecutor, times(1)).execute(any(StepConfig.class), contextCaptor.capture());
        assertEquals("oa", contextCaptor.getValue().getSystemCode());
        assertEquals("u1", contextCaptor.getValue().getAssistantUid());
    }

    @Test
    void shouldPreferPublishedArtifactBeforeLegacyToolMetaFallback() {
        ToolMetaService toolMetaService = mock(ToolMetaService.class);
        FlowDefinitionConverter flowDefinitionConverter = mock(FlowDefinitionConverter.class);
        DAGFlowExecutor dagFlowExecutor = mock(DAGFlowExecutor.class);
        HttpStepExecutor httpStepExecutor = mock(HttpStepExecutor.class);
        ArtifactPublicationLookupService lookupService = mock(ArtifactPublicationLookupService.class);
        ArtifactRuntimeExecutor artifactRuntimeExecutor = mock(ArtifactRuntimeExecutor.class);
        ObjectMapper objectMapper = new ObjectMapper();

        PublishedToolDescriptor descriptor = descriptor("oa.current.user", "oa");
        when(lookupService.findPublishedArtifact(eq("oa.current.user"), any()))
                .thenReturn(Optional.of(descriptor));
        when(toolMetaService.findLatestEnabledByToolCode("default", "oa.current.user"))
                .thenReturn(Optional.of(legacyToolMeta("oa.current.user")));
        when(artifactRuntimeExecutor.execute(eq(descriptor), anyMap(), any()))
                .thenReturn(Map.of(
                        "success", true,
                        "finalOutputs", Map.of("employeeId", "E001")));

        ToolExecutor executor = new ToolExecutor(
                toolMetaService,
                flowDefinitionConverter,
                dagFlowExecutor,
                httpStepExecutor,
                objectMapper,
                Collections.emptyList(),
                lookupService,
                artifactRuntimeExecutor,
                null);

        ToolExecutor.ExecutionResult result = executor.execute("default", "oa.current.user", Map.of(), null);

        assertTrue(result.success());
        assertEquals("E001", result.outputFields().get("employeeId"));
        verify(artifactRuntimeExecutor, times(1)).execute(eq(descriptor), anyMap(), any());
        verify(toolMetaService, never()).findLatestEnabledByToolCode(anyString(), eq("oa.current.user"));
        verify(httpStepExecutor, never()).execute(any(StepConfig.class), any(FlowContext.class));
    }

    @Test
    void shouldNotFallbackToLegacyToolMetaWhenScopedCallDefaultsToArtifactOnly() {
        ToolMetaService toolMetaService = mock(ToolMetaService.class);
        FlowDefinitionConverter flowDefinitionConverter = mock(FlowDefinitionConverter.class);
        DAGFlowExecutor dagFlowExecutor = mock(DAGFlowExecutor.class);
        HttpStepExecutor httpStepExecutor = mock(HttpStepExecutor.class);
        ArtifactPublicationLookupService lookupService = mock(ArtifactPublicationLookupService.class);
        ArtifactRuntimeExecutor artifactRuntimeExecutor = mock(ArtifactRuntimeExecutor.class);
        PublicationScopeResolver publicationScopeResolver = new PublicationScopeResolver(mock(PlatformSpaceService.class));
        ObjectMapper objectMapper = new ObjectMapper();

        when(lookupService.findPublishedArtifact(eq("gougu_oa.current_user"), any()))
                .thenReturn(Optional.empty());
        when(toolMetaService.findLatestEnabledByToolCode("default", "gougu_oa.current_user"))
                .thenReturn(Optional.of(legacyToolMeta("gougu_oa.current_user")));

        ToolExecutor executor = new ToolExecutor(
                toolMetaService,
                flowDefinitionConverter,
                dagFlowExecutor,
                httpStepExecutor,
                objectMapper,
                Collections.emptyList(),
                lookupService,
                artifactRuntimeExecutor,
                publicationScopeResolver);

        ToolExecutor.ExecutionResult result = executor.execute(
                "default",
                "gougu_oa.current_user",
                Map.of(),
                scopedToolContext(false));

        assertFalse(result.success());
        assertTrue(result.errorMessage().contains("not found"));
        verify(toolMetaService, never()).findLatestEnabledByToolCode(anyString(), eq("gougu_oa.current_user"));
        verify(artifactRuntimeExecutor, never()).execute(any(), anyMap(), any());
    }


    @Test
    void shouldProjectPublishedArtifactRiskDrivenConfirmationIntoMatchedToolMetaSnapshot() {
        ToolMetaService toolMetaService = mock(ToolMetaService.class);
        FlowDefinitionConverter flowDefinitionConverter = mock(FlowDefinitionConverter.class);
        DAGFlowExecutor dagFlowExecutor = mock(DAGFlowExecutor.class);
        HttpStepExecutor httpStepExecutor = mock(HttpStepExecutor.class);
        ArtifactPublicationLookupService lookupService = mock(ArtifactPublicationLookupService.class);
        ArtifactRuntimeExecutor artifactRuntimeExecutor = mock(ArtifactRuntimeExecutor.class);
        ObjectMapper objectMapper = new ObjectMapper();

        PublishedToolDescriptor descriptor = descriptor("oa.leave.apply", "oa", null, "HIGH", null);
        when(lookupService.findPublishedArtifact(eq("oa.leave.apply"), any()))
                .thenReturn(Optional.of(descriptor));
        when(artifactRuntimeExecutor.execute(eq(descriptor), anyMap(), any()))
                .thenAnswer(invocation -> {
                    ToolContext runtimeContext = invocation.getArgument(2);
                    OverAllState state = (OverAllState) runtimeContext.getContext()
                            .get(ToolContextConstants.AGENT_STATE_CONTEXT_KEY);
                    @SuppressWarnings("unchecked")
                    Map<String, Object> matchedToolMeta = state.value(AssistantStateKeys.MATCHED_TOOL_META, Map.class)
                            .orElse(null);
                    assertTrue(matchedToolMeta != null);
                    assertEquals("HIGH", matchedToolMeta.get("riskLevel"));
                    assertEquals(Boolean.TRUE, matchedToolMeta.get("requiresConfirm"));
                    return Map.of(
                            "success", true,
                            "finalOutputs", Map.of("requestId", "REQ-1"));
                });

        ToolExecutor executor = new ToolExecutor(
                toolMetaService,
                flowDefinitionConverter,
                dagFlowExecutor,
                httpStepExecutor,
                objectMapper,
                Collections.emptyList(),
                lookupService,
                artifactRuntimeExecutor,
                null);

        OverAllState state = new OverAllState();
        ToolContext context = new ToolContext(Map.of(ToolContextConstants.AGENT_STATE_CONTEXT_KEY, state));

        ToolExecutor.ExecutionResult result = executor.execute(
                "default",
                "oa.leave.apply",
                Map.of("reason", "personal"),
                context);

        assertTrue(result.success());
        assertEquals("REQ-1", result.outputFields().get("requestId"));
        verify(artifactRuntimeExecutor, times(1)).execute(eq(descriptor), anyMap(), any());
    }
    private ToolMeta legacyToolMeta(String toolCode) {
        ToolMeta toolMeta = new ToolMeta();
        toolMeta.setToolCode(toolCode);
        toolMeta.setSystemCode("gougu_oa");
        toolMeta.setApiEndpoint("/api/current_user");
        toolMeta.setHttpMethod("GET");
        toolMeta.setContentType("application/json");
        return toolMeta;
    }

    private ToolContext scopedToolContext(boolean allowLegacyFallback) {
        OverAllState state = new OverAllState();
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("tenant_id", "default");
        values.put(AssistantStateKeys.SPACE_ID, 9L);
        values.put(AssistantStateKeys.SPACE_ENVIRONMENT, "prod");
        values.put(AssistantStateKeys.AGENT_APP_CODE, "finance-agent");
        if (allowLegacyFallback) {
            values.put("allow_legacy_fallback", true);
        }
        state.updateState(values);
        return new ToolContext(Map.of(ToolContextConstants.AGENT_STATE_CONTEXT_KEY, state));
    }

    private PublishedToolDescriptor descriptor(String artifactCode, String systemCode) {
        return descriptor(artifactCode, systemCode, null, null, null);
    }

    private PublishedToolDescriptor descriptor(
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
                        null,
                        null,
                        confirmationPolicyJson,
                        null)
                : null;
        Map<String, RuntimeArtifact.ActionBinding> actions = riskLevel != null || approvalPolicyId != null
                ? Map.of("submit", actionBinding(artifactCode + ".submit", riskLevel, approvalPolicyId))
                : Map.of();
        RuntimeArtifact artifact = new RuntimeArtifact(
                1L,
                artifactCode,
                RuntimeArtifact.ArtifactType.WORKFLOW,
                "Current User",
                1,
                null,
                null,
                null,
                null,
                interaction,
                new com.alibaba.assistant.agent.execution.flow.FlowDefinition(),
                actions,
                Map.of());
        return PublishedToolDescriptor.forArtifact(
                "artifact-catalog",
                "workflow:" + artifactCode,
                "Current User",
                null,
                null,
                false,
                systemCode,
                artifact);
    }

    private RuntimeArtifact.ActionBinding actionBinding(String actionCode, String riskLevel, Long approvalPolicyId) {
        return new RuntimeArtifact.ActionBinding(
                1L,
                actionCode,
                1L,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                riskLevel,
                approvalPolicyId,
                null,
                null,
                1);
    }
}

