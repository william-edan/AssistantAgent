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
package com.alibaba.assistant.agent.runtime.registry;

import com.alibaba.assistant.agent.common.enums.Language;
import com.alibaba.assistant.agent.common.tools.CodeExample;
import com.alibaba.assistant.agent.common.tools.CodeactTool;
import com.alibaba.assistant.agent.common.tools.CodeactToolMetadata;
import com.alibaba.assistant.agent.common.tools.DefaultCodeactToolMetadata;
import com.alibaba.assistant.agent.common.tools.definition.CodeactToolDefinition;
import com.alibaba.assistant.agent.controlplane.rolepackage.ResolvedRolePackageManagementView;
import com.alibaba.assistant.agent.controlplane.space.PlatformSpaceService;
import com.alibaba.assistant.agent.runtime.agent.AssistantStateKeys;
import com.alibaba.assistant.agent.runtime.role.RoleContextResolver;
import com.alibaba.assistant.agent.runtime.role.RoleToolScopeFilter;
import com.alibaba.assistant.agent.runtime.role.ScenarioRouter;
import com.alibaba.assistant.agent.execution.flow.FlowDefinition;
import com.alibaba.assistant.agent.runtime.compiler.RuntimeArtifact;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.agent.tools.ToolContextConstants;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
        ToolPublicationProvider directProvider = mock(ToolPublicationProvider.class);
        ToolPublicationProviderSelector selector = new ToolPublicationProviderSelector();
        ToolPublicationMaterializer materializer = new ToolPublicationMaterializer();
        RuntimeArtifact artifact = artifact("oa.leave.apply");
        CodeactTool directTool = mockCodeactTool("leave_application_execute");
        PublishedToolDescriptor artifactDescriptor = artifactDescriptor(artifact);
        PublishedToolDescriptor directDescriptor = PublishedToolDescriptor.forDirectTool(
                "synthetic-direct",
                "synthetic-direct:leave_application_execute",
                "请假审批",
                directTool);
        ToolPublicationProvider.PublicationScope scope = new ToolPublicationProvider.PublicationScope("default", null, null, null);
        when(artifactProvider.providerId()).thenReturn("tool-meta-catalog");
        when(directProvider.providerId()).thenReturn("synthetic-direct");
        when(artifactProvider.listPublishedTools(scope)).thenReturn(List.of(artifactDescriptor));
        when(directProvider.listPublishedTools(scope)).thenReturn(List.of(directDescriptor));

        TenantAwareToolRegistry registry = new TenantAwareToolRegistry(
                List.of(artifactProvider, directProvider), materializer, null, selector);
        assertFalse(registry.getTool("oa_leave_apply_execute").isPresent());
        assertTrue(registry.getTool("leave_application_execute").isPresent());

        registry.createSessionRegistry("default");
        registry.createSessionRegistry("default");
        verify(artifactProvider, times(1)).listPublishedTools(scope);
        verify(directProvider, times(1)).listPublishedTools(scope);

        registry.onToolPublished(new TenantAwareToolRegistry.ToolPublishedEvent("default"));
        registry.createSessionRegistry("default");
        verify(artifactProvider, times(2)).listPublishedTools(scope);
        verify(directProvider, times(2)).listPublishedTools(scope);
    }

    @Test
    void shouldExposeOnlyUserVisibleDirectToolsAsReactAccessibleTools() {
        ToolPublicationProvider artifactProvider = mock(ToolPublicationProvider.class);
        ToolPublicationProvider directProvider = mock(ToolPublicationProvider.class);
        ToolPublicationProvider mcpProvider = mock(ToolPublicationProvider.class);
        ToolPublicationProviderSelector selector = new ToolPublicationProviderSelector();
        ToolPublicationMaterializer materializer = new ToolPublicationMaterializer();
        RuntimeArtifact artifact = artifact("oa.leave.apply");
        CodeactTool directTool = mockCodeactTool("leave_application_execute");
        CodeactTool mcpTool = mockCodeactTool("kb_search");
        CodeactTool internalTool = mockCodeactTool("employee_resolver");
        PublishedToolDescriptor directDescriptor = PublishedToolDescriptor.forDirectTool(
                "synthetic-direct",
                "synthetic-direct:leave_application_execute",
                "请假审批",
                directTool);
        PublishedToolDescriptor mcpDescriptor = PublishedToolDescriptor.forDirectTool(
                "mcp",
                "mcp:kb_search",
                "知识库搜索",
                mcpTool);
        PublishedToolDescriptor internalDescriptor = PublishedToolDescriptor.forDirectTool(
                "internal",
                "internal:employee_resolver",
                "员工解析",
                "QUERY",
                "INTERNAL",
                "DEPENDENCY_ONLY",
                internalTool);
        ToolPublicationProvider.PublicationScope scope = new ToolPublicationProvider.PublicationScope("default", null, null, null);
        when(artifactProvider.providerId()).thenReturn("tool-meta-catalog");
        when(directProvider.providerId()).thenReturn("synthetic-direct");
        when(mcpProvider.providerId()).thenReturn("mcp");
        when(artifactProvider.listPublishedTools(scope)).thenReturn(List.of(artifactDescriptor(artifact)));
        when(directProvider.listPublishedTools(scope)).thenReturn(List.of(directDescriptor, internalDescriptor));
        when(mcpProvider.listPublishedTools(scope)).thenReturn(List.of(mcpDescriptor));

        TenantAwareToolRegistry registry = new TenantAwareToolRegistry(
                List.of(artifactProvider, directProvider, mcpProvider), materializer, null, selector);
        List<String> reactToolNames = registry.getReactAccessibleTools().stream()
                .map(tool -> tool.getToolDefinition().name())
                .sorted()
                .toList();

        assertTrue(registry.getTool("leave_application_execute").isPresent());
        assertFalse(reactToolNames.contains("employee_resolver"));
        assertEquals(List.of("kb_search", "leave_application_execute"), reactToolNames);
    }

    @Test
    void shouldCreateScopedRegistryFromToolContext() {
        ToolPublicationProvider artifactProvider = mock(ToolPublicationProvider.class);
        ToolPublicationProviderSelector selector = new ToolPublicationProviderSelector();
        ToolPublicationMaterializer materializer = new ToolPublicationMaterializer();
        PublicationScopeResolver publicationScopeResolver = mock(PublicationScopeResolver.class);
        RuntimeArtifact artifact = artifact("oa.leave.apply");
        ToolPublicationProvider.PublicationScope scope = new ToolPublicationProvider.PublicationScope(
                "default", 9L, "prod", "hr-assistant");
        when(artifactProvider.providerId()).thenReturn("tool-meta-catalog");
        when(publicationScopeResolver.resolve(org.mockito.ArgumentMatchers.any(ToolContext.class))).thenReturn(scope);
        when(artifactProvider.listPublishedTools(scope)).thenReturn(List.of(artifactDescriptor(artifact)));

        TenantAwareToolRegistry registry = new TenantAwareToolRegistry(
                List.of(artifactProvider), materializer, publicationScopeResolver, selector);
        OverAllState state = new OverAllState();
        state.updateState(Map.of("agent_app_code", "hr-assistant", "space_id", 9L));
        ToolContext toolContext = new ToolContext(Map.of(ToolContextConstants.AGENT_STATE_CONTEXT_KEY, state));

        assertTrue(registry.scope(toolContext).getAllTools().isEmpty());
        verify(artifactProvider, times(1)).listPublishedTools(scope);
    }

    @Test
    void shouldOnlyUseRequestedProviderSourcesForScopedRegistry() {
        ToolPublicationProvider artifactProvider = mock(ToolPublicationProvider.class);
        ToolPublicationProvider directProvider = mock(ToolPublicationProvider.class);
        when(artifactProvider.providerId()).thenReturn("tool-meta-catalog");
        when(directProvider.providerId()).thenReturn("synthetic-direct");
        ToolPublicationProviderSelector selector = new ToolPublicationProviderSelector();
        ToolPublicationMaterializer materializer = new ToolPublicationMaterializer();
        PublicationScopeResolver publicationScopeResolver = mock(PublicationScopeResolver.class);
        RuntimeArtifact artifact = artifact("oa.leave.apply");
        ToolPublicationProvider.PublicationScope scope = new ToolPublicationProvider.PublicationScope(
                "default",
                9L,
                "prod",
                "hr-assistant",
                ToolPublicationProvider.SourceSelectionMode.EXCLUSIVE,
                List.of("tool-meta-catalog"),
                List.of());
        when(publicationScopeResolver.resolve(org.mockito.ArgumentMatchers.any(ToolContext.class))).thenReturn(scope);
        when(artifactProvider.listPublishedTools(scope)).thenReturn(List.of(artifactDescriptor(artifact)));

        TenantAwareToolRegistry registry = new TenantAwareToolRegistry(
                List.of(directProvider, artifactProvider), materializer, publicationScopeResolver, selector);
        OverAllState state = new OverAllState();
        state.updateState(Map.of("agent_app_code", "hr-assistant", "space_id", 9L));
        ToolContext toolContext = new ToolContext(Map.of(ToolContextConstants.AGENT_STATE_CONTEXT_KEY, state));

        assertTrue(registry.scope(toolContext).getAllTools().isEmpty());
        verify(artifactProvider).listPublishedTools(scope);
        verify(directProvider, never()).listPublishedTools(scope);
    }

    @Test
    void shouldApplyAgentAppDefaultSourcePolicyForScopedRegistry() {
        ToolPublicationProvider artifactProvider = mock(ToolPublicationProvider.class);
        ToolPublicationProvider directProvider = mock(ToolPublicationProvider.class);
        when(artifactProvider.providerId()).thenReturn("tool-meta-catalog");
        when(directProvider.providerId()).thenReturn("synthetic-direct");
        ToolPublicationProviderSelector selector = new ToolPublicationProviderSelector();
        ToolPublicationMaterializer materializer = new ToolPublicationMaterializer();
        PlatformSpaceService platformSpaceService = mock(PlatformSpaceService.class);
        AgentAppPublicationPolicyResolver policyResolver = mock(AgentAppPublicationPolicyResolver.class);
        PublicationScopeResolver publicationScopeResolver = new PublicationScopeResolver(platformSpaceService, policyResolver);
        RuntimeArtifact artifact = artifact("oa.leave.apply");
        ToolPublicationProvider.PublicationScope scope = new ToolPublicationProvider.PublicationScope(
                "default",
                9L,
                "prod",
                "finance-agent",
                ToolPublicationProvider.SourceSelectionMode.EXCLUSIVE,
                List.of("tool-meta-catalog"),
                List.of("synthetic-direct"));
        when(policyResolver.resolve(9L, "finance-agent")).thenReturn(Optional.of(
                new AgentAppPublicationPolicyResolver.PublicationSourcePolicy(
                        ToolPublicationProvider.SourceSelectionMode.EXCLUSIVE,
                        List.of("tool-meta-catalog"),
                        List.of("synthetic-direct"))));
        when(artifactProvider.listPublishedTools(scope)).thenReturn(List.of(artifactDescriptor(artifact)));

        TenantAwareToolRegistry registry = new TenantAwareToolRegistry(
                List.of(directProvider, artifactProvider), materializer, publicationScopeResolver, selector);
        OverAllState state = new OverAllState();
        state.updateState(Map.of("agent_app_code", "finance-agent", "space_id", 9L));
        ToolContext toolContext = new ToolContext(Map.of(ToolContextConstants.AGENT_STATE_CONTEXT_KEY, state));

        assertTrue(registry.scope(toolContext).getAllTools().isEmpty());
        verify(policyResolver).resolve(9L, "finance-agent");
        verify(artifactProvider).listPublishedTools(scope);
        verify(directProvider, never()).listPublishedTools(scope);
    }

    @Test
    void shouldDefaultToToolMetaCatalogForScopedRegistryWithoutExplicitPolicy() {
        ToolPublicationProvider artifactProvider = mock(ToolPublicationProvider.class);
        ToolPublicationProvider directProvider = mock(ToolPublicationProvider.class);
        when(artifactProvider.providerId()).thenReturn("tool-meta-catalog");
        when(directProvider.providerId()).thenReturn("synthetic-direct");
        ToolPublicationProviderSelector selector = new ToolPublicationProviderSelector();
        ToolPublicationMaterializer materializer = new ToolPublicationMaterializer();
        PlatformSpaceService platformSpaceService = mock(PlatformSpaceService.class);
        PublicationScopeResolver publicationScopeResolver = new PublicationScopeResolver(platformSpaceService);
        RuntimeArtifact artifact = artifact("oa.leave.apply");
        ToolPublicationProvider.PublicationScope scope = new ToolPublicationProvider.PublicationScope(
                "default",
                9L,
                "prod",
                "finance-agent",
                ToolPublicationProvider.SourceSelectionMode.EXCLUSIVE,
                List.of("tool-meta-catalog"),
                List.of());
        when(artifactProvider.listPublishedTools(scope)).thenReturn(List.of(artifactDescriptor(artifact)));

        TenantAwareToolRegistry registry = new TenantAwareToolRegistry(
                List.of(directProvider, artifactProvider), materializer, publicationScopeResolver, selector);
        OverAllState state = new OverAllState();
        state.updateState(Map.of("agent_app_code", "finance-agent", "space_id", 9L));
        ToolContext toolContext = new ToolContext(Map.of(ToolContextConstants.AGENT_STATE_CONTEXT_KEY, state));

        assertTrue(registry.scope(toolContext).getAllTools().isEmpty());
        assertFalse(registry.scope(toolContext).getTool("leave_application_execute").isPresent());
        verify(artifactProvider, times(1)).listPublishedTools(scope);
        verify(directProvider, never()).listPublishedTools(scope);
    }

    @Test
    void shouldResolveScopedPublicationUsingRoleBoundAgentApp() {
        ToolPublicationProvider directProvider = mock(ToolPublicationProvider.class);
        when(directProvider.providerId()).thenReturn("synthetic-direct");
        ToolPublicationProviderSelector selector = new ToolPublicationProviderSelector();
        ToolPublicationMaterializer materializer = new ToolPublicationMaterializer();
        PublicationScopeResolver publicationScopeResolver = mock(PublicationScopeResolver.class);
        RoleContextResolver roleContextResolver = mock(RoleContextResolver.class);
        ScenarioRouter scenarioRouter = mock(ScenarioRouter.class);
        RoleToolScopeFilter roleToolScopeFilter = new RoleToolScopeFilter(roleContextResolver, scenarioRouter);
        ToolPublicationProvider.PublicationScope scope = new ToolPublicationProvider.PublicationScope(
                "default", 9L, "prod", "finance-agent");
        when(publicationScopeResolver.resolve(org.mockito.ArgumentMatchers.any(ToolContext.class))).thenReturn(scope);
        CodeactTool allowedTool = mockCodeactTool("leave_application_execute");
        CodeactTool blockedTool = mockCodeactTool("expense_submit_execute");
        List<PublishedToolDescriptor> publishedDescriptors = List.of(
                PublishedToolDescriptor.forDirectTool(
                        "synthetic-direct",
                        "synthetic-direct:leave_application_execute",
                        "请假审批",
                        allowedTool),
                PublishedToolDescriptor.forDirectTool(
                        "synthetic-direct",
                        "synthetic-direct:expense_submit_execute",
                        "费用提报",
                        blockedTool));
        when(directProvider.listPublishedTools(scope)).thenReturn(publishedDescriptors);
        when(roleContextResolver.resolve(org.mockito.ArgumentMatchers.anyMap())).thenReturn(Optional.of(
                new RoleContextResolver.RoleContext(
                        9L,
                        "finance-agent",
                        "digital-admin",
                        "v1",
                        "数字行政助理",
                        "负责审批、排期和通知。",
                        null,
                        List.of(),
                        List.of(new ResolvedRolePackageManagementView.RoleToolScopeView(
                                null,
                                "leave_application_execute",
                                "REQUIRED")))));

        TenantAwareToolRegistry registry = new TenantAwareToolRegistry(
                List.of(directProvider), materializer, publicationScopeResolver, selector, roleToolScopeFilter);
        OverAllState state = new OverAllState();
        state.updateState(Map.of(
                AssistantStateKeys.AGENT_APP_CODE, "finance-agent",
                AssistantStateKeys.SPACE_ID, 9L,
                AssistantStateKeys.ROLE_PACKAGE_CODE, "digital-admin",
                AssistantStateKeys.ROLE_PACKAGE_VERSION, "v1"));
        ToolContext toolContext = new ToolContext(Map.of(ToolContextConstants.AGENT_STATE_CONTEXT_KEY, state));

        List<String> toolNames = registry.scope(toolContext).getAllTools().stream()
                .map(tool -> tool.getToolDefinition().name())
                .toList();

        assertEquals(List.of("leave_application_execute"), toolNames);
    }
    private PublishedToolDescriptor artifactDescriptor(RuntimeArtifact artifact) {
        return PublishedToolDescriptor.forArtifact(
                "tool-meta-catalog",
                "tool:" + artifact.getArtifactCode(),
                artifact.getDisplayName(),
                "oa_tools",
                "OA workflow tools",
                false,
                null,
                "ACTION",
                "USER",
                "DIRECT",
                artifact);
    }

    private RuntimeArtifact artifact(String artifactCode) {
        return new RuntimeArtifact(
                1L,
                artifactCode,
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




