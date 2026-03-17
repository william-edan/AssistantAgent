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
import com.alibaba.assistant.agent.execution.flow.FlowDefinition;
import com.alibaba.assistant.agent.runtime.compiler.RuntimeArtifact;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ArtifactPublicationLookupServiceTest {

    @Test
    void shouldListOnlyUserVisibleArtifactPublicationsFromResolvedScope() {
        PublicationScopeResolver scopeResolver = mock(PublicationScopeResolver.class);
        ToolPublicationProvider provider = mock(ToolPublicationProvider.class);
        ToolPublicationProviderSelector selector = new ToolPublicationProviderSelector();
        ArtifactPublicationLookupService service = new ArtifactPublicationLookupService(
                List.of(provider), scopeResolver, selector, null);

        ToolPublicationProvider.PublicationScope scope =
                new ToolPublicationProvider.PublicationScope("default", 1L, "prod", "hr-assistant");
        PublishedToolDescriptor plannerDescriptor = artifactDescriptor(
                "tool-meta-catalog",
                "tool:oa.user.query",
                artifact("oa.user.query"),
                "QUERY",
                "PLANNER",
                "COMPOSABLE");
        PublishedToolDescriptor directDescriptor = directDescriptor(
                "synthetic-direct",
                "gougu_oa.leave_application",
                "gougu_oa_leave_application_execute");
        when(scopeResolver.resolve(Map.of("space_code", "default", "agent_app_code", "hr-assistant")))
                .thenReturn(scope);
        when(provider.listPublishedTools(scope)).thenReturn(List.of(
                artifactDescriptor("tool-meta-catalog", "tool:oa.leave.apply", artifact("oa.leave.apply")),
                plannerDescriptor,
                directDescriptor));

        List<PublishedToolDescriptor> descriptors = service.listPublishedArtifacts(Map.of(
                "space_code", "default",
                "agent_app_code", "hr-assistant"));

        assertEquals(1, descriptors.size());
        assertEquals("oa.leave.apply", descriptors.get(0).artifact().getArtifactCode());
    }

    @Test
    void shouldFindArtifactByArtifactCodeFromToolContextEvenWhenPlannerOnly() {
        PublicationScopeResolver scopeResolver = mock(PublicationScopeResolver.class);
        ToolPublicationProvider provider = mock(ToolPublicationProvider.class);
        ToolPublicationProviderSelector selector = new ToolPublicationProviderSelector();
        ArtifactPublicationLookupService service = new ArtifactPublicationLookupService(
                List.of(provider), scopeResolver, selector, null);

        ToolContext toolContext = new ToolContext(Map.of("space_code", "default", "agent_app_code", "hr-assistant"));
        ToolPublicationProvider.PublicationScope scope =
                new ToolPublicationProvider.PublicationScope("default", 1L, "prod", "hr-assistant");
        when(scopeResolver.resolve(toolContext)).thenReturn(scope);
        when(provider.listPublishedTools(scope)).thenReturn(List.of(
                artifactDescriptor(
                        "tool-meta-catalog",
                        "tool:oa.employee.query",
                        artifact("oa.employee.query"),
                        "QUERY",
                        "PLANNER",
                        "COMPOSABLE")));

        PublishedToolDescriptor descriptor = service.findPublishedArtifact("oa.employee.query", toolContext).orElseThrow();

        assertEquals("tool:oa.employee.query", descriptor.publicationKey());
        assertEquals("oa.employee.query", descriptor.artifact().getArtifactCode());
    }

    @Test
    void shouldFindDirectToolAliasFromSelectedProvidersWhenArtifactPublicationMissing() {
        PublicationScopeResolver scopeResolver = mock(PublicationScopeResolver.class);
        ToolPublicationProvider directProvider = mock(ToolPublicationProvider.class);
        when(directProvider.providerId()).thenReturn("synthetic-direct");
        ToolPublicationProviderSelector selector = new ToolPublicationProviderSelector();
        ArtifactPublicationLookupService service = new ArtifactPublicationLookupService(
                List.of(directProvider), scopeResolver, selector, null);

        ToolContext toolContext = new ToolContext(Map.of("space_code", "default", "agent_app_code", "hr-assistant"));
        ToolPublicationProvider.PublicationScope scope =
                new ToolPublicationProvider.PublicationScope("default", null, "prod", "hr-assistant");
        PublishedToolDescriptor directDescriptor = directDescriptor(
                "synthetic-direct",
                "gougu_oa.leave_application",
                "gougu_oa_leave_application_execute");
        when(scopeResolver.resolve(toolContext)).thenReturn(scope);
        when(directProvider.listPublishedTools(scope)).thenReturn(List.of(directDescriptor));

        PublishedToolDescriptor resolved = service.findPublishedArtifact("gougu_oa.leave_application", toolContext)
                .orElseThrow();

        assertTrue(resolved.isDirectToolPublication());
        assertSame(directDescriptor.directTool(), resolved.directTool());
        assertEquals("gougu_oa.leave_application", resolved.directTool().getCodeactMetadata().aliases().get(0));
    }

    @Test
    void shouldNotResolveDirectToolAliasFromBlockedProviderSources() {
        PublicationScopeResolver scopeResolver = mock(PublicationScopeResolver.class);
        ToolPublicationProvider artifactProvider = mock(ToolPublicationProvider.class);
        ToolPublicationProvider directProvider = mock(ToolPublicationProvider.class);
        when(artifactProvider.providerId()).thenReturn("tool-meta-catalog");
        when(directProvider.providerId()).thenReturn("synthetic-direct");
        ToolPublicationProviderSelector selector = new ToolPublicationProviderSelector();
        ArtifactPublicationLookupService service = new ArtifactPublicationLookupService(
                List.of(directProvider, artifactProvider), scopeResolver, selector, null);

        ToolContext toolContext = new ToolContext(Map.of("space_code", "default", "agent_app_code", "hr-assistant"));
        ToolPublicationProvider.PublicationScope scope = new ToolPublicationProvider.PublicationScope(
                "default",
                1L,
                "prod",
                "hr-assistant",
                ToolPublicationProvider.SourceSelectionMode.EXCLUSIVE,
                List.of("synthetic-direct"),
                List.of("synthetic-direct"));
        when(scopeResolver.resolve(toolContext)).thenReturn(scope);

        assertTrue(service.findPublishedArtifact("gougu_oa.leave_application", toolContext).isEmpty());
        verify(artifactProvider, never()).listPublishedTools(scope);
        verify(directProvider, never()).listPublishedTools(scope);
    }

    @Test
    void shouldIgnoreNonArtifactPublications() {
        PublicationScopeResolver scopeResolver = mock(PublicationScopeResolver.class);
        ToolPublicationProvider provider = mock(ToolPublicationProvider.class);
        ToolPublicationProviderSelector selector = new ToolPublicationProviderSelector();
        ArtifactPublicationLookupService service = new ArtifactPublicationLookupService(
                List.of(provider), scopeResolver, selector, null);

        ToolPublicationProvider.PublicationScope scope =
                new ToolPublicationProvider.PublicationScope("default", 1L, "prod", "hr-assistant");
        ToolContext toolContext = new ToolContext(Map.of());
        when(scopeResolver.resolve(Map.of())).thenReturn(scope);
        when(scopeResolver.resolve(toolContext)).thenReturn(scope);
        when(provider.listPublishedTools(scope)).thenReturn(List.of());

        assertTrue(service.listPublishedArtifacts(Map.of()).isEmpty());
        assertFalse(service.findPublishedArtifact("oa.leave.apply", toolContext).isPresent());
    }

    @Test
    void shouldOnlyQueryRequestedProviderSources() {
        PublicationScopeResolver scopeResolver = mock(PublicationScopeResolver.class);
        ToolPublicationProvider artifactProvider = mock(ToolPublicationProvider.class);
        ToolPublicationProvider directProvider = mock(ToolPublicationProvider.class);
        when(artifactProvider.providerId()).thenReturn("tool-meta-catalog");
        when(directProvider.providerId()).thenReturn("synthetic-direct");
        ToolPublicationProviderSelector selector = new ToolPublicationProviderSelector();
        ArtifactPublicationLookupService service = new ArtifactPublicationLookupService(
                List.of(directProvider, artifactProvider), scopeResolver, selector, null);

        ToolPublicationProvider.PublicationScope scope = new ToolPublicationProvider.PublicationScope(
                "default",
                1L,
                "prod",
                "hr-assistant",
                ToolPublicationProvider.SourceSelectionMode.EXCLUSIVE,
                List.of("tool-meta-catalog"),
                List.of());
        when(scopeResolver.resolve(Map.of("space_code", "default"))).thenReturn(scope);
        when(artifactProvider.listPublishedTools(scope)).thenReturn(List.of(
                artifactDescriptor("tool-meta-catalog", "tool:oa.leave.apply", artifact("oa.leave.apply"))));

        List<PublishedToolDescriptor> descriptors = service.listPublishedArtifacts(Map.of("space_code", "default"));

        assertEquals(1, descriptors.size());
        verify(artifactProvider).listPublishedTools(scope);
        verify(directProvider, never()).listPublishedTools(scope);
    }

    private PublishedToolDescriptor artifactDescriptor(String providerId, String publicationKey, RuntimeArtifact artifact) {
        return artifactDescriptor(providerId, publicationKey, artifact, "ACTION", "USER", "DIRECT");
    }

    private PublishedToolDescriptor artifactDescriptor(
            String providerId,
            String publicationKey,
            RuntimeArtifact artifact,
            String toolType,
            String visibility,
            String invocationPolicy) {
        return PublishedToolDescriptor.forArtifact(
                providerId,
                publicationKey,
                artifact.getDisplayName(),
                null,
                null,
                false,
                "gougu_oa",
                toolType,
                visibility,
                invocationPolicy,
                artifact);
    }

    private PublishedToolDescriptor directDescriptor(String providerId, String toolCode, String toolName) {
        return PublishedToolDescriptor.forDirectTool(
                providerId,
                providerId + ":" + toolName,
                "请假申请",
                mockCodeactTool(toolCode, toolName));
    }

    private CodeactTool mockCodeactTool(String toolCode, String toolName) {
        CodeactTool tool = mock(CodeactTool.class);
        ToolDefinition definition = DefaultToolDefinition.builder()
                .name(toolName)
                .description("请假申请")
                .inputSchema("{\"type\":\"object\",\"properties\":{}}")
                .build();
        CodeactToolMetadata metadata = DefaultCodeactToolMetadata.builder()
                .addSupportedLanguage(Language.PYTHON)
                .targetClassName("published_tools")
                .targetClassDescription("Published tools")
                .codeInvocationTemplate(toolName + "() -> Dict[str, Any]")
                .fewShots(List.of(new CodeExample("demo", "published_tools." + toolName + "()", "mock behavior")))
                .displayName("请假申请")
                .addAlias(toolCode)
                .returnDirect(false)
                .build();

        when(tool.getToolDefinition()).thenReturn(definition);
        when(tool.getCodeactMetadata()).thenReturn(metadata);
        return tool;
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
}

