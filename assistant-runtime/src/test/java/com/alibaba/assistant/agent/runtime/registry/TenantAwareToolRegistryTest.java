/*
 * Copyright 2024-2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
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
package com.alibaba.assistant.agent.runtime.registry;

import com.alibaba.assistant.agent.common.enums.Language;
import com.alibaba.assistant.agent.common.tools.CodeExample;
import com.alibaba.assistant.agent.common.tools.CodeactTool;
import com.alibaba.assistant.agent.common.tools.CodeactToolMetadata;
import com.alibaba.assistant.agent.common.tools.DefaultCodeactToolMetadata;
import com.alibaba.assistant.agent.common.tools.definition.CodeactToolDefinition;
import com.alibaba.assistant.agent.controlplane.space.PlatformSpaceService;
import com.alibaba.assistant.agent.execution.flow.FlowDefinition;
import com.alibaba.assistant.agent.runtime.tool.codeact.ArtifactToolFactory;
import com.alibaba.assistant.agent.runtime.compiler.RuntimeArtifact;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.agent.tools.ToolContextConstants;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TenantAwareToolRegistryTest {

    @Test
    void shouldCacheSnapshotAndInvalidateOnToolPublishedEvent() {
        ToolPublicationProvider artifactProvider = mock(ToolPublicationProvider.class);
        ToolPublicationProvider legacyProvider = mock(ToolPublicationProvider.class);
        ToolPublicationProviderSelector selector = new ToolPublicationProviderSelector();
        ToolPublicationMaterializer materializer = new ToolPublicationMaterializer(new ArtifactToolFactory(new ObjectMapper()));
        RuntimeArtifact artifact = new RuntimeArtifact(
                1L,
                "oa.leave.apply",
                RuntimeArtifact.ArtifactType.WORKFLOW,
                "请假申请",
                1,
                null,
                null,
                null,
                null,
                null,
                new FlowDefinition(),
                Map.of(),
                Map.of());
        CodeactTool legacyTool = mockCodeactTool("leave_application_execute");
        PublishedToolDescriptor artifactDescriptor = new PublishedToolDescriptor(
                "artifact-catalog",
                "workflow:oa.leave.apply",
                "请假申请",
                "oa_tools",
                "OA workflow tools",
                false,
                null,
                artifact);
        PublishedToolDescriptor legacyDescriptor = PublishedToolDescriptor.forDirectTool(
                "legacy-bridge",
                "legacy:leave_application_execute",
                "请假审批",
                legacyTool);
        ToolPublicationProvider.PublicationScope scope = new ToolPublicationProvider.PublicationScope("default", null, null, null);
        when(artifactProvider.providerId()).thenReturn("artifact-catalog");
        when(legacyProvider.providerId()).thenReturn("legacy-bridge");
        when(artifactProvider.listPublishedTools(scope)).thenReturn(List.of(artifactDescriptor));
        when(legacyProvider.listPublishedTools(scope)).thenReturn(List.of(legacyDescriptor));

        TenantAwareToolRegistry registry = new TenantAwareToolRegistry(
                List.of(artifactProvider, legacyProvider), materializer, null, selector);
        assertTrue(registry.getTool("oa_leave_apply_execute").isPresent());
        assertTrue(registry.getTool("leave_application_execute").isPresent());

        registry.createSessionRegistry("default");
        registry.createSessionRegistry("default");
        verify(artifactProvider, times(1)).listPublishedTools(scope);
        verify(legacyProvider, times(1)).listPublishedTools(scope);

        registry.onToolPublished(new TenantAwareToolRegistry.ToolPublishedEvent("default"));
        registry.createSessionRegistry("default");
        verify(artifactProvider, times(2)).listPublishedTools(scope);
        verify(legacyProvider, times(2)).listPublishedTools(scope);
    }

    @Test
    void shouldCreateScopedRegistryFromToolContext() {
        ToolPublicationProvider artifactProvider = mock(ToolPublicationProvider.class);
        ToolPublicationProviderSelector selector = new ToolPublicationProviderSelector();
        ToolPublicationMaterializer materializer = new ToolPublicationMaterializer(new ArtifactToolFactory(new ObjectMapper()));
        PublicationScopeResolver publicationScopeResolver = mock(PublicationScopeResolver.class);
        RuntimeArtifact artifact = new RuntimeArtifact(
                9L,
                "oa.leave.apply",
                RuntimeArtifact.ArtifactType.WORKFLOW,
                "请假申请",
                1,
                null,
                null,
                null,
                null,
                null,
                new FlowDefinition(),
                Map.of(),
                Map.of());
        ToolPublicationProvider.PublicationScope scope = new ToolPublicationProvider.PublicationScope(
                "default", 9L, "prod", "hr-assistant");
        when(artifactProvider.providerId()).thenReturn("artifact-catalog");
        when(publicationScopeResolver.resolve(org.mockito.ArgumentMatchers.any(ToolContext.class))).thenReturn(scope);
        when(artifactProvider.listPublishedTools(scope)).thenReturn(List.of(new PublishedToolDescriptor(
                "artifact-catalog",
                "workflow:oa.leave.apply",
                "请假申请",
                "oa_tools",
                "OA workflow tools",
                false,
                null,
                artifact)));

        TenantAwareToolRegistry registry = new TenantAwareToolRegistry(
                List.of(artifactProvider), materializer, publicationScopeResolver, selector);
        OverAllState state = new OverAllState();
        state.updateState(Map.of("agent_app_code", "hr-assistant", "space_id", 9L));
        ToolContext toolContext = new ToolContext(Map.of(ToolContextConstants.AGENT_STATE_CONTEXT_KEY, state));

        assertTrue(registry.scope(toolContext).getTool("oa_leave_apply_execute").isPresent());
        verify(artifactProvider, times(1)).listPublishedTools(scope);
    }

    @Test
    void shouldOnlyUseRequestedProviderSourcesForScopedRegistry() {
        ToolPublicationProvider artifactProvider = mock(ToolPublicationProvider.class);
        ToolPublicationProvider legacyProvider = mock(ToolPublicationProvider.class);
        when(artifactProvider.providerId()).thenReturn("artifact-catalog");
        when(legacyProvider.providerId()).thenReturn("legacy-bridge");
        ToolPublicationProviderSelector selector = new ToolPublicationProviderSelector();
        ToolPublicationMaterializer materializer = new ToolPublicationMaterializer(new ArtifactToolFactory(new ObjectMapper()));
        PublicationScopeResolver publicationScopeResolver = mock(PublicationScopeResolver.class);
        RuntimeArtifact artifact = new RuntimeArtifact(
                9L,
                "oa.leave.apply",
                RuntimeArtifact.ArtifactType.WORKFLOW,
                "请假申请",
                1,
                null,
                null,
                null,
                null,
                null,
                new FlowDefinition(),
                Map.of(),
                Map.of());
        ToolPublicationProvider.PublicationScope scope = new ToolPublicationProvider.PublicationScope(
                "default",
                9L,
                "prod",
                "hr-assistant",
                ToolPublicationProvider.SourceSelectionMode.EXCLUSIVE,
                List.of("artifact-catalog"),
                List.of());
        when(publicationScopeResolver.resolve(org.mockito.ArgumentMatchers.any(ToolContext.class))).thenReturn(scope);
        when(artifactProvider.listPublishedTools(scope)).thenReturn(List.of(new PublishedToolDescriptor(
                "artifact-catalog",
                "workflow:oa.leave.apply",
                "请假申请",
                "oa_tools",
                "OA workflow tools",
                false,
                null,
                artifact)));

        TenantAwareToolRegistry registry = new TenantAwareToolRegistry(
                List.of(legacyProvider, artifactProvider), materializer, publicationScopeResolver, selector);
        OverAllState state = new OverAllState();
        state.updateState(Map.of("agent_app_code", "hr-assistant", "space_id", 9L));
        ToolContext toolContext = new ToolContext(Map.of(ToolContextConstants.AGENT_STATE_CONTEXT_KEY, state));

        assertTrue(registry.scope(toolContext).getTool("oa_leave_apply_execute").isPresent());
        verify(artifactProvider).listPublishedTools(scope);
        verify(legacyProvider, never()).listPublishedTools(scope);
    }

    @Test
    void shouldApplyAgentAppDefaultSourcePolicyForScopedRegistry() {
        ToolPublicationProvider artifactProvider = mock(ToolPublicationProvider.class);
        ToolPublicationProvider legacyProvider = mock(ToolPublicationProvider.class);
        when(artifactProvider.providerId()).thenReturn("artifact-catalog");
        when(legacyProvider.providerId()).thenReturn("legacy-bridge");
        ToolPublicationProviderSelector selector = new ToolPublicationProviderSelector();
        ToolPublicationMaterializer materializer = new ToolPublicationMaterializer(new ArtifactToolFactory(new ObjectMapper()));
        PlatformSpaceService platformSpaceService = mock(PlatformSpaceService.class);
        AgentAppPublicationPolicyResolver policyResolver = mock(AgentAppPublicationPolicyResolver.class);
        PublicationScopeResolver publicationScopeResolver = new PublicationScopeResolver(platformSpaceService, policyResolver);
        RuntimeArtifact artifact = new RuntimeArtifact(
                9L,
                "oa.leave.apply",
                RuntimeArtifact.ArtifactType.WORKFLOW,
                "请假申请",
                1,
                null,
                null,
                null,
                null,
                null,
                new FlowDefinition(),
                Map.of(),
                Map.of());
        ToolPublicationProvider.PublicationScope scope = new ToolPublicationProvider.PublicationScope(
                "default",
                9L,
                "prod",
                "finance-agent",
                ToolPublicationProvider.SourceSelectionMode.EXCLUSIVE,
                List.of("artifact-catalog"),
                List.of("legacy-bridge"));
        when(policyResolver.resolve(9L, "finance-agent")).thenReturn(Optional.of(
                new AgentAppPublicationPolicyResolver.PublicationSourcePolicy(
                        ToolPublicationProvider.SourceSelectionMode.EXCLUSIVE,
                        List.of("artifact-catalog"),
                        List.of("legacy-bridge"))));
        when(artifactProvider.listPublishedTools(scope)).thenReturn(List.of(new PublishedToolDescriptor(
                "artifact-catalog",
                "workflow:oa.leave.apply",
                "请假申请",
                "oa_tools",
                "OA workflow tools",
                false,
                null,
                artifact)));

        TenantAwareToolRegistry registry = new TenantAwareToolRegistry(
                List.of(legacyProvider, artifactProvider), materializer, publicationScopeResolver, selector);
        OverAllState state = new OverAllState();
        state.updateState(Map.of("agent_app_code", "finance-agent", "space_id", 9L));
        ToolContext toolContext = new ToolContext(Map.of(ToolContextConstants.AGENT_STATE_CONTEXT_KEY, state));

        assertTrue(registry.scope(toolContext).getTool("oa_leave_apply_execute").isPresent());
        verify(policyResolver).resolve(9L, "finance-agent");
        verify(artifactProvider).listPublishedTools(scope);
        verify(legacyProvider, never()).listPublishedTools(scope);
    }
    @Test
    void shouldDefaultToArtifactOnlyForScopedRegistryWithoutExplicitPolicy() {
        ToolPublicationProvider artifactProvider = mock(ToolPublicationProvider.class);
        ToolPublicationProvider legacyProvider = mock(ToolPublicationProvider.class);
        when(artifactProvider.providerId()).thenReturn("artifact-catalog");
        when(legacyProvider.providerId()).thenReturn("legacy-bridge");
        ToolPublicationProviderSelector selector = new ToolPublicationProviderSelector();
        ToolPublicationMaterializer materializer = new ToolPublicationMaterializer(new ArtifactToolFactory(new ObjectMapper()));
        PlatformSpaceService platformSpaceService = mock(PlatformSpaceService.class);
        PublicationScopeResolver publicationScopeResolver = new PublicationScopeResolver(platformSpaceService);
        RuntimeArtifact artifact = new RuntimeArtifact(
                9L,
                "oa.leave.apply",
                RuntimeArtifact.ArtifactType.WORKFLOW,
                "请假申请",
                1,
                null,
                null,
                null,
                null,
                null,
                new FlowDefinition(),
                Map.of(),
                Map.of());
        ToolPublicationProvider.PublicationScope scope = new ToolPublicationProvider.PublicationScope(
                "default",
                9L,
                "prod",
                "finance-agent",
                ToolPublicationProvider.SourceSelectionMode.EXCLUSIVE,
                List.of("artifact-catalog"),
                List.of("legacy-bridge"));
        when(artifactProvider.listPublishedTools(scope)).thenReturn(List.of(new PublishedToolDescriptor(
                "artifact-catalog",
                "workflow:oa.leave.apply",
                "请假申请",
                "oa_tools",
                "OA workflow tools",
                false,
                null,
                artifact)));

        TenantAwareToolRegistry registry = new TenantAwareToolRegistry(
                List.of(legacyProvider, artifactProvider), materializer, publicationScopeResolver, selector);
        OverAllState state = new OverAllState();
        state.updateState(Map.of("agent_app_code", "finance-agent", "space_id", 9L));
        ToolContext toolContext = new ToolContext(Map.of(ToolContextConstants.AGENT_STATE_CONTEXT_KEY, state));

        assertTrue(registry.scope(toolContext).getTool("oa_leave_apply_execute").isPresent());
        verify(artifactProvider).listPublishedTools(scope);
        verify(legacyProvider, never()).listPublishedTools(scope);
    }

    private static CodeactTool mockCodeactTool(String name) {
        CodeactTool tool = mock(CodeactTool.class);
        ToolDefinition definition = DefaultToolDefinition.builder()
                .name(name)
                .description("mock tool")
                .inputSchema("{\"type\":\"object\",\"properties\":{}}")
                .build();
        CodeactToolMetadata metadata = DefaultCodeactToolMetadata.builder()
                .addSupportedLanguage(Language.PYTHON)
                .targetClassName("mock_tools")
                .targetClassDescription("mock tools")
                .codeInvocationTemplate(name + "() -> Dict[str, Any]")
                .fewShots(List.of(new CodeExample("demo", "mock_tools." + name + "()", "mock behavior")))
                .returnDirect(false)
                .build();

        when(tool.getToolDefinition()).thenReturn(definition);
        when(tool.getCodeactMetadata()).thenReturn(metadata);
        when(tool.getCodeactDefinition()).thenReturn((CodeactToolDefinition) null);
        return tool;
    }
}

