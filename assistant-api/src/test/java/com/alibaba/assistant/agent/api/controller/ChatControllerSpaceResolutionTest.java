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
package com.alibaba.assistant.agent.api.controller;

import com.alibaba.assistant.agent.controlplane.space.PlatformSpace;
import com.alibaba.assistant.agent.controlplane.space.PlatformSpaceService;
import com.alibaba.assistant.agent.runtime.agent.AssistantStateKeys;
import com.alibaba.assistant.agent.runtime.context.RuntimeSpaceResolver;
import com.alibaba.cloud.ai.agent.studio.loader.AgentLoader;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ChatControllerSpaceResolutionTest {

    @Test
    void shouldKeepCurrentTurnSlotInputsInMergedStateDelta() {
        PlatformSpaceService platformSpaceService = mock(PlatformSpaceService.class);
        RuntimeSpaceResolver runtimeSpaceResolver = new RuntimeSpaceResolver(platformSpaceService, "prod");
        ChatController controller = new ChatController(
                mock(AgentLoader.class),
                "finance-agent",
                "gougu_oa",
                "finance-space",
                "test",
                null,
                null,
                null,
                null,
                null,
                runtimeSpaceResolver);

        @SuppressWarnings("unchecked")
        Map<String, Object> merged = (Map<String, Object>) ReflectionTestUtils.invokeMethod(
                controller,
                "mergeStateDelta",
                Map.of("types", 2, "works", "完成需求评审"),
                "thread-1",
                "assistant-1",
                "gougu_oa",
                "finance-agent");

        assertThat(merged).containsEntry("types", 2);
        assertThat(merged).containsEntry("works", "完成需求评审");
        assertThat(merged).containsKey(AssistantStateKeys.CURRENT_TURN_SLOT_INPUTS);
        assertThat((Map<String, Object>) merged.get(AssistantStateKeys.CURRENT_TURN_SLOT_INPUTS))
                .containsEntry("types", 2)
                .containsEntry("works", "完成需求评审");
    }

    @Test
    void shouldResolveSpaceIdIntoMergedStateDelta() {
        PlatformSpaceService platformSpaceService = mock(PlatformSpaceService.class);
        PlatformSpace platformSpace = new PlatformSpace();
        platformSpace.setId(18L);
        when(platformSpaceService.findActiveByCode("finance-space", "test")).thenReturn(Optional.of(platformSpace));
        RuntimeSpaceResolver runtimeSpaceResolver = new RuntimeSpaceResolver(platformSpaceService, "prod");
        ChatController controller = new ChatController(
                mock(AgentLoader.class),
                "finance-agent",
                "gougu_oa",
                "finance-space",
                "test",
                null,
                null,
                null,
                null,
                null,
                runtimeSpaceResolver);

        @SuppressWarnings("unchecked")
        Map<String, Object> merged = (Map<String, Object>) ReflectionTestUtils.invokeMethod(
                controller,
                "mergeStateDelta",
                Map.of(),
                "thread-1",
                "assistant-1",
                "gougu_oa",
                "finance-agent");

        assertThat(merged).containsEntry(AssistantStateKeys.THREAD_ID, "thread-1");
        assertThat(merged).containsEntry(AssistantStateKeys.SPACE_CODE, "finance-space");
        assertThat(merged).containsEntry(AssistantStateKeys.SPACE_ENVIRONMENT, "test");
        assertThat(merged).containsEntry(AssistantStateKeys.SPACE_ID, 18L);
    }
}
