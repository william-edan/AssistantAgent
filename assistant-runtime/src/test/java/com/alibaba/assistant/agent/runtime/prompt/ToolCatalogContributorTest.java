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
package com.alibaba.assistant.agent.runtime.prompt;

import com.alibaba.assistant.agent.controlplane.toolregistry.ToolMeta;
import com.alibaba.assistant.agent.controlplane.toolregistry.ToolMetaService;
import com.alibaba.assistant.agent.execution.flow.FlowDefinition;
import com.alibaba.assistant.agent.prompt.PromptContribution;
import com.alibaba.assistant.agent.prompt.PromptContributorContext;
import com.alibaba.assistant.agent.runtime.compiler.RuntimeArtifact;
import com.alibaba.assistant.agent.runtime.config.RuntimeConfigCompatibilityAdapter;
import com.alibaba.assistant.agent.runtime.registry.ArtifactPublicationLookupService;
import com.alibaba.assistant.agent.runtime.registry.PublicationScopeResolver;
import com.alibaba.assistant.agent.runtime.registry.PublishedToolDescriptor;
import com.alibaba.assistant.agent.runtime.registry.ToolPublicationProvider;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ToolCatalogContributorTest {

    @Test
    void shouldSkipWhenDynamicPromptDisabled() {
        ArtifactPublicationLookupService artifactLookupService = mock(ArtifactPublicationLookupService.class);
        ToolMetaService toolMetaService = mock(ToolMetaService.class);
        RuntimeConfigCompatibilityAdapter adapter = mock(RuntimeConfigCompatibilityAdapter.class);
        PublicationScopeResolver publicationScopeResolver = mock(PublicationScopeResolver.class);
        when(adapter.promptDynamicEnabled()).thenReturn(false);

        ToolCatalogContributor contributor = new ToolCatalogContributor(
                artifactLookupService,
                toolMetaService,
                adapter,
                publicationScopeResolver);
        boolean shouldContribute = contributor.shouldContribute(context(Map.of()));

        assertFalse(shouldContribute);
    }

    @Test
    void shouldRenderArtifactCatalogBeforeLegacyFallback() {
        ArtifactPublicationLookupService artifactLookupService = mock(ArtifactPublicationLookupService.class);
        ToolMetaService toolMetaService = mock(ToolMetaService.class);
        RuntimeConfigCompatibilityAdapter adapter = mock(RuntimeConfigCompatibilityAdapter.class);
        PublicationScopeResolver publicationScopeResolver = mock(PublicationScopeResolver.class);
        when(adapter.promptDynamicEnabled()).thenReturn(true);
        when(adapter.promptMaxToolsInPrompt()).thenReturn(1);
        when(artifactLookupService.listPublishedArtifacts(anyMap())).thenReturn(List.of(
                descriptor("workflow:oa.leave.apply", artifact("oa.leave.apply", "请假申请")),
                descriptor("workflow:oa.current.user", artifact("oa.current.user", "当前用户"))));

        ToolCatalogContributor contributor = new ToolCatalogContributor(
                artifactLookupService,
                toolMetaService,
                adapter,
                publicationScopeResolver);
        PromptContribution contribution = contributor.contribute(context(Map.of()));

        assertNotNull(contribution);
        assertEquals(1, contribution.messagesToAppend().size());
        Message message = contribution.messagesToAppend().get(0);
        assertInstanceOf(UserMessage.class, message);
        assertTrue(message.getText().contains("oa.leave.apply"));
        assertFalse(message.getText().contains("oa.current.user"));
        assertTrue(message.getText().contains("artifact_execute"));
        assertTrue(message.getText().contains("1/2"));
        verifyNoInteractions(toolMetaService);
    }

    @Test
    void shouldFallbackToLegacyCatalogWhenNoArtifactPublicationExistsForUnscopedCall() {
        ArtifactPublicationLookupService artifactLookupService = mock(ArtifactPublicationLookupService.class);
        ToolMetaService toolMetaService = mock(ToolMetaService.class);
        RuntimeConfigCompatibilityAdapter adapter = mock(RuntimeConfigCompatibilityAdapter.class);
        PublicationScopeResolver publicationScopeResolver = mock(PublicationScopeResolver.class);
        when(adapter.promptDynamicEnabled()).thenReturn(true);
        when(adapter.promptMaxToolsInPrompt()).thenReturn(5);
        when(artifactLookupService.listPublishedArtifacts(anyMap())).thenReturn(List.of());
        when(publicationScopeResolver.resolve(anyMap())).thenReturn(scope(
                "tenant-a",
                null,
                "prod",
                null,
                ToolPublicationProvider.SourceSelectionMode.MERGE,
                List.of(),
                List.of()));
        when(toolMetaService.listEnabledByTenantAndSystem(eq("tenant-a"), eq("gougu_oa")))
                .thenReturn(List.of(tool("gougu_oa.leave_apply", "请假申请", null)));

        ToolCatalogContributor contributor = new ToolCatalogContributor(
                artifactLookupService,
                toolMetaService,
                adapter,
                publicationScopeResolver);
        PromptContribution contribution = contributor.contribute(context(Map.of(
                "tenant_id", "tenant-a",
                "system_code", "gougu_oa")));

        assertEquals(1, contribution.messagesToAppend().size());
        verify(toolMetaService, times(1)).listEnabledByTenantAndSystem("tenant-a", "gougu_oa");
    }

    @Test
    void shouldNotFallbackToLegacyCatalogWhenScopedCallDefaultsToArtifactOnly() {
        ArtifactPublicationLookupService artifactLookupService = mock(ArtifactPublicationLookupService.class);
        ToolMetaService toolMetaService = mock(ToolMetaService.class);
        RuntimeConfigCompatibilityAdapter adapter = mock(RuntimeConfigCompatibilityAdapter.class);
        PublicationScopeResolver publicationScopeResolver = mock(PublicationScopeResolver.class);
        when(adapter.promptDynamicEnabled()).thenReturn(true);
        when(adapter.promptMaxToolsInPrompt()).thenReturn(5);
        when(artifactLookupService.listPublishedArtifacts(anyMap())).thenReturn(List.of());
        when(publicationScopeResolver.resolve(anyMap())).thenReturn(scope(
                "default",
                9L,
                "prod",
                "finance-agent",
                ToolPublicationProvider.SourceSelectionMode.EXCLUSIVE,
                List.of("artifact-catalog"),
                List.of("legacy-bridge")));

        ToolCatalogContributor contributor = new ToolCatalogContributor(
                artifactLookupService,
                toolMetaService,
                adapter,
                publicationScopeResolver);
        PromptContribution contribution = contributor.contribute(context(Map.of(
                "tenant_id", "default",
                "space_id", 9L,
                "environment", "prod",
                "agent_app_code", "finance-agent",
                "system_code", "gougu_oa")));

        assertEquals(0, contribution.messagesToAppend().size());
        verify(toolMetaService, never()).listEnabledByTenantAndSystem("default", "gougu_oa");
    }

    @Test
    void shouldFallbackToLegacyCatalogWhenScopedCallAllowsLegacyFallback() {
        ArtifactPublicationLookupService artifactLookupService = mock(ArtifactPublicationLookupService.class);
        ToolMetaService toolMetaService = mock(ToolMetaService.class);
        RuntimeConfigCompatibilityAdapter adapter = mock(RuntimeConfigCompatibilityAdapter.class);
        PublicationScopeResolver publicationScopeResolver = mock(PublicationScopeResolver.class);
        when(adapter.promptDynamicEnabled()).thenReturn(true);
        when(adapter.promptMaxToolsInPrompt()).thenReturn(5);
        when(artifactLookupService.listPublishedArtifacts(anyMap())).thenReturn(List.of());
        when(publicationScopeResolver.resolve(anyMap())).thenReturn(scope(
                "default",
                9L,
                "prod",
                "finance-agent",
                ToolPublicationProvider.SourceSelectionMode.MERGE,
                List.of("artifact-catalog"),
                List.of()));
        when(toolMetaService.listEnabledByTenantAndSystem(eq("default"), eq("gougu_oa")))
                .thenReturn(List.of(tool("gougu_oa.leave_apply", "请假申请", null)));

        ToolCatalogContributor contributor = new ToolCatalogContributor(
                artifactLookupService,
                toolMetaService,
                adapter,
                publicationScopeResolver);
        PromptContribution contribution = contributor.contribute(context(Map.of(
                "tenant_id", "default",
                "space_id", 9L,
                "environment", "prod",
                "agent_app_code", "finance-agent",
                "allow_legacy_fallback", true,
                "system_code", "gougu_oa")));

        assertEquals(1, contribution.messagesToAppend().size());
        verify(toolMetaService, times(1)).listEnabledByTenantAndSystem("default", "gougu_oa");
    }

    private ToolPublicationProvider.PublicationScope scope(
            String tenantId,
            Long spaceId,
            String environment,
            String agentAppCode,
            ToolPublicationProvider.SourceSelectionMode mode,
            List<String> requestedSourceIds,
            List<String> blockedSourceIds) {
        return new ToolPublicationProvider.PublicationScope(
                tenantId,
                spaceId,
                environment,
                agentAppCode,
                mode,
                requestedSourceIds,
                blockedSourceIds);
    }

    private ToolMeta tool(String toolCode, String toolName, String desc) {
        ToolMeta toolMeta = new ToolMeta();
        toolMeta.setToolCode(toolCode);
        toolMeta.setToolName(toolName);
        toolMeta.setDescription(desc);
        return toolMeta;
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

    private RuntimeArtifact artifact(String artifactCode, String displayName) {
        return new RuntimeArtifact(
                1L,
                artifactCode,
                RuntimeArtifact.ArtifactType.WORKFLOW,
                displayName,
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

    private PromptContributorContext context(Map<String, Object> attrs) {
        return new PromptContributorContext() {
            @Override
            public List<Message> getMessages() {
                return Collections.emptyList();
            }

            @Override
            public Optional<SystemMessage> getSystemMessage() {
                return Optional.empty();
            }

            @Override
            public Map<String, Object> getAttributes() {
                return attrs;
            }

            @Override
            public Optional<String> getPhase() {
                return Optional.of("REACT");
            }
        };
    }

}
