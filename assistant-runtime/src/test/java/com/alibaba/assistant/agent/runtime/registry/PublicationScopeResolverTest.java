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

import com.alibaba.assistant.agent.controlplane.space.PlatformSpace;
import com.alibaba.assistant.agent.controlplane.space.PlatformSpaceService;
import com.alibaba.assistant.agent.runtime.agent.AssistantStateKeys;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.agent.tools.ToolContextConstants;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PublicationScopeResolverTest {

    @Test
    void shouldResolveScopeFromStateUsingSpaceCodeLookup() {
        PlatformSpaceService platformSpaceService = mock(PlatformSpaceService.class);
        PublicationScopeResolver resolver = new PublicationScopeResolver(platformSpaceService);
        PlatformSpace platformSpace = new PlatformSpace();
        platformSpace.setId(88L);
        when(platformSpaceService.findActiveByCode("hr-space", "test")).thenReturn(Optional.of(platformSpace));

        OverAllState state = new OverAllState();
        state.updateState(Map.of(
                AssistantStateKeys.SPACE_CODE, "hr-space",
                AssistantStateKeys.SPACE_ENVIRONMENT, "test",
                AssistantStateKeys.AGENT_APP_CODE, "hr-assistant",
                "tenant_id", "tenant-a"));

        ToolContext toolContext = new ToolContext(Map.of(ToolContextConstants.AGENT_STATE_CONTEXT_KEY, state));
        ToolPublicationProvider.PublicationScope scope = resolver.resolve(toolContext);

        assertEquals("tenant-a", scope.tenantId());
        assertEquals(88L, scope.spaceId());
        assertEquals("test", scope.environment());
        assertEquals("hr-assistant", scope.agentAppCode());
    }

    @Test
    void shouldPreferExplicitSpaceIdWithoutPlatformLookup() {
        PlatformSpaceService platformSpaceService = mock(PlatformSpaceService.class);
        PublicationScopeResolver resolver = new PublicationScopeResolver(platformSpaceService);

        OverAllState state = new OverAllState();
        state.updateState(Map.of(
                AssistantStateKeys.SPACE_ID, 9L,
                AssistantStateKeys.SPACE_ENVIRONMENT, "prod",
                AssistantStateKeys.AGENT_APP_CODE, "expense-agent",
                "tenant_id", "default"));

        ToolContext toolContext = new ToolContext(Map.of(ToolContextConstants.AGENT_STATE_CONTEXT_KEY, state));
        ToolPublicationProvider.PublicationScope scope = resolver.resolve(toolContext);

        assertEquals(9L, scope.spaceId());
        assertEquals("prod", scope.environment());
        assertEquals("expense-agent", scope.agentAppCode());
    }

    @Test
    void shouldResolveScopeFromAttributesMap() {
        PlatformSpaceService platformSpaceService = mock(PlatformSpaceService.class);
        PublicationScopeResolver resolver = new PublicationScopeResolver(platformSpaceService);
        PlatformSpace platformSpace = new PlatformSpace();
        platformSpace.setId(12L);
        when(platformSpaceService.findActiveByCode("finance-space", "prod")).thenReturn(Optional.of(platformSpace));

        ToolPublicationProvider.PublicationScope scope = resolver.resolve(Map.of(
                "tenant_id", "default",
                "space_code", "finance-space",
                "environment", "prod",
                "agent_app_code", "finance-agent"));

        assertEquals("default", scope.tenantId());
        assertEquals(12L, scope.spaceId());
        assertEquals("prod", scope.environment());
        assertEquals("finance-agent", scope.agentAppCode());
    }

    @Test
    void shouldResolvePublicationSourceSelectionFromAttributesMap() {
        PlatformSpaceService platformSpaceService = mock(PlatformSpaceService.class);
        PublicationScopeResolver resolver = new PublicationScopeResolver(platformSpaceService);

        ToolPublicationProvider.PublicationScope scope = resolver.resolve(Map.of(
                "tenant_id", "default",
                "space_id", 9L,
                "environment", "prod",
                "agent_app_code", "finance-agent",
                "tool_source_mode", "exclusive",
                "tool_source_ids", List.of("artifact-catalog", "mcp-gateway"),
                "disabled_tool_source_ids", "legacy-bridge"));

        assertEquals("default", scope.tenantId());
        assertEquals(9L, scope.spaceId());
        assertEquals("prod", scope.environment());
        assertEquals("finance-agent", scope.agentAppCode());
        assertEquals(ToolPublicationProvider.SourceSelectionMode.EXCLUSIVE, scope.sourceSelectionMode());
        assertEquals(List.of("artifact-catalog", "mcp-gateway"), scope.requestedSourceIds());
        assertEquals(List.of("legacy-bridge"), scope.blockedSourceIds());
    }

    @Test
    void shouldFallbackToAgentAppDefaultPublicationSourcePolicy() {
        PlatformSpaceService platformSpaceService = mock(PlatformSpaceService.class);
        AgentAppPublicationPolicyResolver policyResolver = mock(AgentAppPublicationPolicyResolver.class);
        PublicationScopeResolver resolver = new PublicationScopeResolver(platformSpaceService, policyResolver);
        AgentAppPublicationPolicyResolver.PublicationSourcePolicy policy =
                new AgentAppPublicationPolicyResolver.PublicationSourcePolicy(
                        ToolPublicationProvider.SourceSelectionMode.EXCLUSIVE,
                        List.of("artifact-catalog", "mcp-gateway"),
                        List.of("legacy-bridge"));
        when(policyResolver.resolve(9L, "finance-agent")).thenReturn(Optional.of(policy));

        ToolPublicationProvider.PublicationScope scope = resolver.resolve(Map.of(
                "tenant_id", "default",
                "space_id", 9L,
                "environment", "prod",
                "agent_app_code", "finance-agent"));

        assertEquals(ToolPublicationProvider.SourceSelectionMode.EXCLUSIVE, scope.sourceSelectionMode());
        assertEquals(List.of("artifact-catalog", "mcp-gateway"), scope.requestedSourceIds());
        assertEquals(List.of("legacy-bridge"), scope.blockedSourceIds());
    }

    @Test
    void shouldPreferExplicitSourceSelectionOverAgentAppDefaultPolicy() {
        PlatformSpaceService platformSpaceService = mock(PlatformSpaceService.class);
        AgentAppPublicationPolicyResolver policyResolver = mock(AgentAppPublicationPolicyResolver.class);
        PublicationScopeResolver resolver = new PublicationScopeResolver(platformSpaceService, policyResolver);
        AgentAppPublicationPolicyResolver.PublicationSourcePolicy policy =
                new AgentAppPublicationPolicyResolver.PublicationSourcePolicy(
                        ToolPublicationProvider.SourceSelectionMode.EXCLUSIVE,
                        List.of("artifact-catalog"),
                        List.of("legacy-bridge"));
        when(policyResolver.resolve(9L, "finance-agent")).thenReturn(Optional.of(policy));

        ToolPublicationProvider.PublicationScope scope = resolver.resolve(Map.of(
                "tenant_id", "default",
                "space_id", 9L,
                "environment", "prod",
                "agent_app_code", "finance-agent",
                "tool_source_mode", "merge",
                "tool_source_ids", List.of("mcp-gateway"),
                "disabled_tool_source_ids", List.of("artifact-catalog")));

        assertEquals(ToolPublicationProvider.SourceSelectionMode.MERGE, scope.sourceSelectionMode());
        assertEquals(List.of("mcp-gateway"), scope.requestedSourceIds());
        assertEquals(List.of("artifact-catalog"), scope.blockedSourceIds());
    }
    @Test
    void shouldDefaultToArtifactOnlyForScopedAgentAppCalls() {
        PlatformSpaceService platformSpaceService = mock(PlatformSpaceService.class);
        PublicationScopeResolver resolver = new PublicationScopeResolver(platformSpaceService);

        ToolPublicationProvider.PublicationScope scope = resolver.resolve(Map.of(
                "tenant_id", "default",
                "space_id", 9L,
                "environment", "prod",
                "agent_app_code", "finance-agent"));

        assertEquals(ToolPublicationProvider.SourceSelectionMode.EXCLUSIVE, scope.sourceSelectionMode());
        assertEquals(List.of("artifact-catalog"), scope.requestedSourceIds());
        assertEquals(List.of("legacy-bridge"), scope.blockedSourceIds());
    }

    @Test
    void shouldAllowLegacyFallbackWhenExplicitlyRequested() {
        PlatformSpaceService platformSpaceService = mock(PlatformSpaceService.class);
        PublicationScopeResolver resolver = new PublicationScopeResolver(platformSpaceService);

        ToolPublicationProvider.PublicationScope scope = resolver.resolve(Map.of(
                "tenant_id", "default",
                "space_id", 9L,
                "environment", "prod",
                "agent_app_code", "finance-agent",
                "allow_legacy_fallback", true));

        assertEquals(ToolPublicationProvider.SourceSelectionMode.MERGE, scope.sourceSelectionMode());
        assertEquals(List.of("artifact-catalog"), scope.requestedSourceIds());
        assertEquals(List.of(), scope.blockedSourceIds());
    }
}
