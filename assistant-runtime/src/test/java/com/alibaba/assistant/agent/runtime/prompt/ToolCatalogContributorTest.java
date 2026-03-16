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

import com.alibaba.assistant.agent.execution.flow.FlowDefinition;
import com.alibaba.assistant.agent.prompt.PromptContribution;
import com.alibaba.assistant.agent.prompt.PromptContributorContext;
import com.alibaba.assistant.agent.runtime.compiler.RuntimeArtifact;
import com.alibaba.assistant.agent.runtime.config.RuntimeConfigView;
import com.alibaba.assistant.agent.runtime.registry.ArtifactPublicationLookupService;
import com.alibaba.assistant.agent.runtime.registry.PublishedToolDescriptor;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ToolCatalogContributorTest {

    @Test
    void shouldSkipWhenDynamicPromptDisabled() {
        ArtifactPublicationLookupService artifactLookupService = mock(ArtifactPublicationLookupService.class);
        RuntimeConfigView adapter = mock(RuntimeConfigView.class);
        when(adapter.promptDynamicEnabled()).thenReturn(false);

        ToolCatalogContributor contributor = new ToolCatalogContributor(artifactLookupService, adapter);

        assertFalse(contributor.shouldContribute(context(Map.of())));
    }

    @Test
    void shouldRenderOnlyUserVisiblePublishedArtifactCatalog() {
        ArtifactPublicationLookupService artifactLookupService = mock(ArtifactPublicationLookupService.class);
        RuntimeConfigView adapter = mock(RuntimeConfigView.class);
        when(adapter.promptDynamicEnabled()).thenReturn(true);
        when(adapter.promptMaxToolsInPrompt()).thenReturn(1);
        when(artifactLookupService.listPublishedArtifacts(anyMap())).thenReturn(List.of(
                descriptor("workflow:oa.leave.apply", artifact("oa.leave.apply", "请假申请"), "ACTION", "USER", "DIRECT"),
                descriptor("workflow:oa.current.user", artifact("oa.current.user", "当前用户"), "QUERY", "PLANNER", "COMPOSABLE")));

        ToolCatalogContributor contributor = new ToolCatalogContributor(artifactLookupService, adapter);
        PromptContribution contribution = contributor.contribute(context(Map.of()));

        assertNotNull(contribution);
        assertEquals(1, contribution.messagesToAppend().size());
        Message message = contribution.messagesToAppend().get(0);
        assertInstanceOf(UserMessage.class, message);
        assertTrue(message.getText().contains("oa.leave.apply"));
        assertFalse(message.getText().contains("oa.current.user"));
        assertTrue(message.getText().contains("artifact_execute"));
    }

    @Test
    void shouldReturnEmptyContributionWhenNothingUserVisible() {
        ArtifactPublicationLookupService artifactLookupService = mock(ArtifactPublicationLookupService.class);
        RuntimeConfigView adapter = mock(RuntimeConfigView.class);
        when(adapter.promptDynamicEnabled()).thenReturn(true);
        when(adapter.promptMaxToolsInPrompt()).thenReturn(5);
        when(artifactLookupService.listPublishedArtifacts(anyMap())).thenReturn(List.of(
                descriptor("workflow:oa.user.query", artifact("oa.user.query", "用户查询"), "QUERY", "PLANNER", "COMPOSABLE"),
                descriptor("workflow:oa.manager.resolver", artifact("oa.manager.resolver", "直属领导解析"), "QUERY", "INTERNAL", "DEPENDENCY_ONLY")));

        ToolCatalogContributor contributor = new ToolCatalogContributor(artifactLookupService, adapter);
        PromptContribution contribution = contributor.contribute(context(Map.of()));

        assertTrue(contribution.messagesToAppend().isEmpty());
    }

    private PublishedToolDescriptor descriptor(
            String publicationKey,
            RuntimeArtifact artifact,
            String toolType,
            String visibility,
            String invocationPolicy) {
        return PublishedToolDescriptor.forArtifact(
                "tool-meta-catalog",
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
                new RuntimeArtifact.Interaction(1L, artifactCode, null, null, null),
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


