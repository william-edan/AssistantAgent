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

import com.alibaba.assistant.agent.execution.flow.FlowDefinition;
import com.alibaba.assistant.agent.runtime.compiler.RuntimeArtifact;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ArtifactPublicationLookupServiceTest {

    @Test
    void shouldListArtifactPublicationsFromResolvedScope() {
        PublicationScopeResolver scopeResolver = mock(PublicationScopeResolver.class);
        ToolPublicationProvider provider = mock(ToolPublicationProvider.class);
        ToolPublicationProviderSelector selector = new ToolPublicationProviderSelector();
        ArtifactPublicationLookupService service = new ArtifactPublicationLookupService(
                List.of(provider), scopeResolver, selector);

        ToolPublicationProvider.PublicationScope scope =
                new ToolPublicationProvider.PublicationScope("default", 1L, "prod", "hr-assistant");
        when(scopeResolver.resolve(Map.of("space_code", "default", "agent_app_code", "hr-assistant")))
                .thenReturn(scope);
        when(provider.listPublishedTools(scope)).thenReturn(List.of(
                descriptor("workflow:oa.leave.apply", artifact("oa.leave.apply")),
                PublishedToolDescriptor.forDirectTool("legacy", "legacy:leave_apply", "请假申请", null)));

        List<PublishedToolDescriptor> descriptors = service.listPublishedArtifacts(Map.of(
                "space_code", "default",
                "agent_app_code", "hr-assistant"));

        assertEquals(1, descriptors.size());
        assertEquals("oa.leave.apply", descriptors.get(0).artifact().getArtifactCode());
    }

    @Test
    void shouldFindArtifactByArtifactCodeFromToolContext() {
        PublicationScopeResolver scopeResolver = mock(PublicationScopeResolver.class);
        ToolPublicationProvider provider = mock(ToolPublicationProvider.class);
        ToolPublicationProviderSelector selector = new ToolPublicationProviderSelector();
        ArtifactPublicationLookupService service = new ArtifactPublicationLookupService(
                List.of(provider), scopeResolver, selector);

        ToolContext toolContext = new ToolContext(Map.of("space_code", "default", "agent_app_code", "hr-assistant"));
        ToolPublicationProvider.PublicationScope scope =
                new ToolPublicationProvider.PublicationScope("default", 1L, "prod", "hr-assistant");
        when(scopeResolver.resolve(toolContext)).thenReturn(scope);
        when(provider.listPublishedTools(scope)).thenReturn(List.of(descriptor("workflow:oa.leave.apply", artifact("oa.leave.apply"))));

        PublishedToolDescriptor descriptor = service.findPublishedArtifact("oa.leave.apply", toolContext).orElseThrow();

        assertEquals("workflow:oa.leave.apply", descriptor.publicationKey());
        assertEquals("oa.leave.apply", descriptor.artifact().getArtifactCode());
    }

    @Test
    void shouldIgnoreNonArtifactPublications() {
        PublicationScopeResolver scopeResolver = mock(PublicationScopeResolver.class);
        ToolPublicationProvider provider = mock(ToolPublicationProvider.class);
        ToolPublicationProviderSelector selector = new ToolPublicationProviderSelector();
        ArtifactPublicationLookupService service = new ArtifactPublicationLookupService(
                List.of(provider), scopeResolver, selector);

        ToolPublicationProvider.PublicationScope scope =
                new ToolPublicationProvider.PublicationScope("default", 1L, "prod", "hr-assistant");
        when(scopeResolver.resolve(Map.of())).thenReturn(scope);
        when(provider.listPublishedTools(scope)).thenReturn(List.of());

        assertTrue(service.listPublishedArtifacts(Map.of()).isEmpty());
        assertFalse(service.findPublishedArtifact("oa.leave.apply", new ToolContext(Map.of())).isPresent());
    }

    @Test
    void shouldOnlyQueryRequestedProviderSources() {
        PublicationScopeResolver scopeResolver = mock(PublicationScopeResolver.class);
        ToolPublicationProvider artifactProvider = mock(ToolPublicationProvider.class);
        ToolPublicationProvider legacyProvider = mock(ToolPublicationProvider.class);
        when(artifactProvider.providerId()).thenReturn("artifact-catalog");
        when(legacyProvider.providerId()).thenReturn("legacy-bridge");
        ToolPublicationProviderSelector selector = new ToolPublicationProviderSelector();
        ArtifactPublicationLookupService service = new ArtifactPublicationLookupService(
                List.of(legacyProvider, artifactProvider), scopeResolver, selector);

        ToolPublicationProvider.PublicationScope scope = new ToolPublicationProvider.PublicationScope(
                "default",
                1L,
                "prod",
                "hr-assistant",
                ToolPublicationProvider.SourceSelectionMode.EXCLUSIVE,
                List.of("artifact-catalog"),
                List.of());
        when(scopeResolver.resolve(Map.of("space_code", "default"))).thenReturn(scope);
        when(artifactProvider.listPublishedTools(scope)).thenReturn(List.of(descriptor("workflow:oa.leave.apply", artifact("oa.leave.apply"))));

        List<PublishedToolDescriptor> descriptors = service.listPublishedArtifacts(Map.of("space_code", "default"));

        assertEquals(1, descriptors.size());
        verify(artifactProvider).listPublishedTools(scope);
        verify(legacyProvider, never()).listPublishedTools(scope);
    }

    private PublishedToolDescriptor descriptor(String publicationKey, RuntimeArtifact artifact) {
        return PublishedToolDescriptor.forArtifact(
                "artifact-catalog",
                publicationKey,
                artifact.getDisplayName(),
                null,
                null,
                false,
                "gougu_oa",
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
}
