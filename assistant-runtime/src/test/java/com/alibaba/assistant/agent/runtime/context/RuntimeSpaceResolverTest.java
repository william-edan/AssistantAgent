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
package com.alibaba.assistant.agent.runtime.context;

import com.alibaba.assistant.agent.controlplane.space.PlatformSpace;
import com.alibaba.assistant.agent.controlplane.space.PlatformSpaceService;
import com.alibaba.assistant.agent.runtime.agent.AssistantStateKeys;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.agent.tools.ToolContextConstants;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RuntimeSpaceResolverTest {

    @Test
    void shouldResolveSpaceIdFromToolContextStateUsingSpaceCode() {
        PlatformSpaceService platformSpaceService = mock(PlatformSpaceService.class);
        RuntimeSpaceResolver resolver = new RuntimeSpaceResolver(platformSpaceService, "prod");
        PlatformSpace platformSpace = new PlatformSpace();
        platformSpace.setId(18L);
        platformSpace.setSpaceCode("finance-space");
        when(platformSpaceService.findActiveByCode("finance-space", "test")).thenReturn(Optional.of(platformSpace));

        OverAllState state = new OverAllState();
        state.updateState(Map.of(
                AssistantStateKeys.SPACE_CODE, "finance-space",
                AssistantStateKeys.SPACE_ENVIRONMENT, "test"));
        ToolContext toolContext = new ToolContext(Map.of(ToolContextConstants.AGENT_STATE_CONTEXT_KEY, state));

        RuntimeSpaceResolver.ResolvedSpace resolvedSpace = resolver.resolve(toolContext);

        assertEquals(18L, resolvedSpace.spaceId());
        assertEquals("finance-space", resolvedSpace.spaceCode());
        assertEquals("test", resolvedSpace.environment());
    }

    @Test
    void shouldPreferExplicitSpaceIdFromAttributes() {
        PlatformSpaceService platformSpaceService = mock(PlatformSpaceService.class);
        RuntimeSpaceResolver resolver = new RuntimeSpaceResolver(platformSpaceService, "prod");

        RuntimeSpaceResolver.ResolvedSpace resolvedSpace = resolver.resolve(Map.of(
                AssistantStateKeys.SPACE_ID, 9L,
                AssistantStateKeys.SPACE_CODE, "finance-space"));

        assertEquals(9L, resolvedSpace.spaceId());
        assertEquals("finance-space", resolvedSpace.spaceCode());
        assertEquals("prod", resolvedSpace.environment());
    }

    @Test
    void shouldKeepSpaceIdNullWhenSpaceCodeCannotBeResolved() {
        PlatformSpaceService platformSpaceService = mock(PlatformSpaceService.class);
        RuntimeSpaceResolver resolver = new RuntimeSpaceResolver(platformSpaceService, "prod");
        when(platformSpaceService.findActiveByCode("missing-space", "prod")).thenReturn(Optional.empty());

        RuntimeSpaceResolver.ResolvedSpace resolvedSpace = resolver.resolve(Map.of(
                AssistantStateKeys.SPACE_CODE, "missing-space"));

        assertNull(resolvedSpace.spaceId());
        assertEquals("missing-space", resolvedSpace.spaceCode());
        assertEquals("prod", resolvedSpace.environment());
    }

    @Test
    void shouldFallbackToConfiguredDefaultSpaceWhenContextMissing() {
        PlatformSpaceService platformSpaceService = mock(PlatformSpaceService.class);
        PlatformSpace platformSpace = new PlatformSpace();
        platformSpace.setId(31L);
        platformSpace.setSpaceCode("default");
        when(platformSpaceService.resolveDefaultRuntimeSpace("prod")).thenReturn(Optional.of(platformSpace));
        RuntimeSpaceResolver resolver = new RuntimeSpaceResolver(platformSpaceService, "prod");

        RuntimeSpaceResolver.ResolvedSpace resolvedSpace = resolver.resolve(Map.of());

        assertEquals(31L, resolvedSpace.spaceId());
        assertEquals("default", resolvedSpace.spaceCode());
        assertEquals("prod", resolvedSpace.environment());
    }
}
