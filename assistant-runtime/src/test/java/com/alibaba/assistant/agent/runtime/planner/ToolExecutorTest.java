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

import com.alibaba.assistant.agent.common.enums.Language;
import com.alibaba.assistant.agent.common.tools.CodeactTool;
import com.alibaba.assistant.agent.common.tools.CodeactToolMetadata;
import com.alibaba.assistant.agent.common.tools.DefaultCodeactToolMetadata;
import com.alibaba.assistant.agent.runtime.agent.AssistantStateKeys;
import com.alibaba.assistant.agent.runtime.compiler.RuntimeArtifact;
import com.alibaba.assistant.agent.runtime.execution.ArtifactRuntimeExecutor;
import com.alibaba.assistant.agent.runtime.registry.ArtifactPublicationLookupService;
import com.alibaba.assistant.agent.runtime.registry.PublishedToolDescriptor;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolInterceptor;
import com.alibaba.cloud.ai.graph.agent.tools.ToolContextConstants;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ToolExecutorTest {

    @Test
    void shouldReturnErrorWhenPublishedArtifactNotFound() {
        ArtifactPublicationLookupService lookupService = mock(ArtifactPublicationLookupService.class);
        when(lookupService.findPublishedArtifact(eq("oa.current.user"), isNull()))
                .thenReturn(Optional.empty());

        ToolExecutor executor = new ToolExecutor(
                new ObjectMapper(),
                Collections.<ToolInterceptor>emptyList(),
                lookupService,
                mock(ArtifactRuntimeExecutor.class));

        ToolExecutor.ExecutionResult result = executor.execute("default", "oa.current.user", Map.of(), null);

        assertFalse(result.success());
        assertTrue(result.errorMessage().contains("not found"));
    }

    @Test
    void shouldExecutePublishedArtifactAndProjectRiskSnapshotIntoState() {
        ArtifactPublicationLookupService lookupService = mock(ArtifactPublicationLookupService.class);
        ArtifactRuntimeExecutor artifactRuntimeExecutor = mock(ArtifactRuntimeExecutor.class);

        RuntimeArtifact artifact = artifact("oa.leave.apply", "HIGH");
        PublishedToolDescriptor descriptor = PublishedToolDescriptor.forArtifact(
                "tool-meta-catalog",
                "oa.leave.apply@1",
                "Leave apply",
                null,
                null,
                false,
                "oa",
                artifact);

        when(lookupService.findPublishedArtifact(eq("oa.leave.apply"), any()))
                .thenReturn(Optional.of(descriptor));

        AtomicReference<Map<String, Object>> capturedSnapshot = new AtomicReference<>();
        when(artifactRuntimeExecutor.execute(eq(descriptor), anyMap(), any())).thenAnswer(invocation -> {
            ToolContext context = invocation.getArgument(2);
            OverAllState state = (OverAllState) context.getContext().get(ToolContextConstants.AGENT_STATE_CONTEXT_KEY);
            @SuppressWarnings("unchecked")
            Map<String, Object> snapshot = state.value(AssistantStateKeys.MATCHED_TOOL_META, Map.class).orElse(null);
            capturedSnapshot.set(snapshot);
            return Map.of(
                    "success", true,
                    "finalOutputs", Map.of("leaveId", "L-001"));
        });

        ToolExecutor executor = new ToolExecutor(
                new ObjectMapper(),
                Collections.<ToolInterceptor>emptyList(),
                lookupService,
                artifactRuntimeExecutor);

        OverAllState state = new OverAllState();
        state.updateState(Map.of(
                AssistantStateKeys.MATCHED_TOOL_META, Map.of("toolCode", "previous.tool"),
                AssistantStateKeys.THREAD_ID, "thread-1"));
        ToolContext toolContext = new ToolContext(Map.of(ToolContextConstants.AGENT_STATE_CONTEXT_KEY, state));

        ToolExecutor.ExecutionResult result = executor.execute("default", "oa.leave.apply", Map.of(), toolContext);

        assertTrue(result.success());
        assertEquals("L-001", result.outputFields().get("leaveId"));
        assertNotNull(capturedSnapshot.get());
        assertEquals("oa.leave.apply", capturedSnapshot.get().get("toolCode"));
        assertEquals("HIGH", capturedSnapshot.get().get("riskLevel"));
        assertEquals(Boolean.TRUE, capturedSnapshot.get().get("requiresConfirm"));
        assertEquals(Map.of("toolCode", "previous.tool"), state.value(AssistantStateKeys.MATCHED_TOOL_META, Map.class).orElse(null));
    }

    @Test
    void shouldExecutePublishedDirectToolAndExtractDataPayload() {
        ArtifactPublicationLookupService lookupService = mock(ArtifactPublicationLookupService.class);
        ToolDefinition definition = DefaultToolDefinition.builder()
                .name("oa.current.user")
                .description("Current user")
                .inputSchema("{\"type\":\"object\",\"properties\":{}}")
                .build();
        CodeactToolMetadata metadata = DefaultCodeactToolMetadata.builder()
                .addSupportedLanguage(Language.PYTHON)
                .displayName("Current user")
                .addAlias("oa.current.user")
                .build();
        AtomicReference<String> capturedInput = new AtomicReference<>();
        StubCodeactTool directTool = new StubCodeactTool(definition, metadata,
                "{\"success\":true,\"data\":{\"employeeId\":\"E001\",\"name\":\"Alice\"}}",
                capturedInput);
        PublishedToolDescriptor descriptor = new PublishedToolDescriptor(
                "tool-meta-catalog",
                "oa.current.user@1",
                "Current user",
                null,
                null,
                false,
                "oa",
                null,
                directTool);

        when(lookupService.findPublishedArtifact(eq("oa.current.user"), isNull()))
                .thenReturn(Optional.of(descriptor));

        ToolExecutor executor = new ToolExecutor(
                new ObjectMapper(),
                Collections.<ToolInterceptor>emptyList(),
                lookupService,
                null);

        ToolExecutor.ExecutionResult result = executor.execute(
                "default",
                "oa.current.user",
                Map.of("assistantUid", "u1", "systemCode", "oa"),
                null);

        assertTrue(result.success());
        assertEquals("E001", result.outputFields().get("employeeId"));
        assertEquals("Alice", result.outputFields().get("name"));
        assertNotNull(capturedInput.get());
        assertTrue(capturedInput.get().contains("\"toolCode\":\"oa.current.user\""));
        assertTrue(capturedInput.get().contains("\"params\""));
    }

    @Test
    void shouldReturnErrorWhenArtifactExecutorUnavailableForArtifactPublication() {
        ArtifactPublicationLookupService lookupService = mock(ArtifactPublicationLookupService.class);
        PublishedToolDescriptor descriptor = PublishedToolDescriptor.forArtifact(
                "tool-meta-catalog",
                "oa.leave.apply@1",
                "Leave apply",
                null,
                null,
                false,
                "oa",
                artifact("oa.leave.apply", "LOW"));
        when(lookupService.findPublishedArtifact(eq("oa.leave.apply"), isNull()))
                .thenReturn(Optional.of(descriptor));

        ToolExecutor executor = new ToolExecutor(
                new ObjectMapper(),
                Collections.<ToolInterceptor>emptyList(),
                lookupService,
                null);

        ToolExecutor.ExecutionResult result = executor.execute("default", "oa.leave.apply", Map.of(), null);

        assertFalse(result.success());
        assertTrue(result.errorMessage().contains("Artifact dependency executor is unavailable"));
    }

    private static RuntimeArtifact artifact(String toolCode, String riskLevel) {
        return new RuntimeArtifact(
                1L,
                toolCode,
                RuntimeArtifact.ArtifactType.ACTION,
                "Artifact " + toolCode,
                1,
                null,
                null,
                null,
                null,
                new RuntimeArtifact.Interaction(1L, toolCode, null, null, null),
                null,
                Map.of("submit", new RuntimeArtifact.ActionBinding(
                        1L,
                        toolCode + ".submit",
                        1L,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        riskLevel,
                        null,
                        null,
                        1)),
                Map.of());
    }

    private static final class StubCodeactTool implements CodeactTool {

        private final ToolDefinition toolDefinition;
        private final CodeactToolMetadata metadata;
        private final String response;
        private final AtomicReference<String> capturedInput;

        private StubCodeactTool(
                ToolDefinition toolDefinition,
                CodeactToolMetadata metadata,
                String response,
                AtomicReference<String> capturedInput) {
            this.toolDefinition = toolDefinition;
            this.metadata = metadata;
            this.response = response;
            this.capturedInput = capturedInput;
        }

        @Override
        public String call(String toolInput) {
            capturedInput.set(toolInput);
            return response;
        }

        @Override
        public String call(String toolInput, ToolContext toolContext) {
            capturedInput.set(toolInput);
            return response;
        }

        @Override
        public ToolDefinition getToolDefinition() {
            return toolDefinition;
        }

        @Override
        public CodeactToolMetadata getCodeactMetadata() {
            return metadata;
        }
    }
}
