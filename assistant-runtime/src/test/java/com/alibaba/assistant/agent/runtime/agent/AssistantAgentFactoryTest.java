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
package com.alibaba.assistant.agent.runtime.agent;

import com.alibaba.assistant.agent.common.tools.CodeactTool;
import com.alibaba.assistant.agent.extension.experience.config.ExperienceExtensionProperties;
import com.alibaba.assistant.agent.extension.experience.fastintent.FastIntentService;
import com.alibaba.assistant.agent.extension.experience.hook.FastIntentReactHook;
import com.alibaba.assistant.agent.extension.experience.spi.ExperienceProvider;
import com.alibaba.assistant.agent.runtime.intent.AssistantFastIntentHook;
import com.alibaba.assistant.agent.runtime.intent.AssistantIntentRouter;
import com.alibaba.assistant.agent.runtime.registry.TenantAwareToolRegistry;
import com.alibaba.cloud.ai.graph.agent.hook.Hook;
import com.alibaba.cloud.ai.graph.agent.hook.hip.HumanInTheLoopHook;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;

import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AssistantAgentFactoryTest {

    @Test
    void shouldHaveMigrationProfileAnnotation() {
        assertTrue(AssistantAgentFactory.class.isAnnotationPresent(
                org.springframework.context.annotation.Profile.class));
        String[] profiles = AssistantAgentFactory.class
                .getAnnotation(org.springframework.context.annotation.Profile.class).value();
        assertArrayEquals(new String[] { "migration" }, profiles);
    }

    @Test
    void shouldHaveConfigurationAnnotation() {
        assertTrue(AssistantAgentFactory.class.isAnnotationPresent(
                org.springframework.context.annotation.Configuration.class));
    }

    @Test
    void shouldFilterLegacyFastIntentHookWhenRuntimeHookPresent() {
        ExperienceProvider experienceProvider = mock(ExperienceProvider.class);
        FastIntentReactHook legacyHook = new FastIntentReactHook(
                experienceProvider,
                new ExperienceExtensionProperties(),
                new FastIntentService(List.of()));

        AssistantFastIntentHook runtimeHook =
                new AssistantFastIntentHook(mock(AssistantIntentRouter.class), new ObjectMapper());
        Hook otherHook = mock(Hook.class);

        List<Hook> filtered = AssistantAgentFactory.filterReactHooks(List.of(legacyHook, runtimeHook, otherHook));

        assertEquals(2, filtered.size());
        assertTrue(filtered.contains(runtimeHook));
        assertTrue(filtered.contains(otherHook));
        assertFalse(filtered.contains(legacyHook));
    }

    @Test
    void shouldMergeCodeactToolsIntoReactCallbacks() {
        ToolCallback slotCollect = mockTool("slot_collect");
        CodeactTool executeTool = mockCodeactTool("gougu_oa_leave_application_execute");

        ToolCallback[] merged = AssistantAgentFactory.mergeReactAndCodeactToolCallbacks(
                List.of(slotCollect),
                List.of(executeTool));

        List<String> names = List.of(merged).stream()
                .map(callback -> callback.getToolDefinition().name())
                .collect(Collectors.toList());
        assertEquals(2, merged.length);
        assertTrue(names.contains("slot_collect"));
        assertTrue(names.contains("gougu_oa_leave_application_execute"));
    }

    @Test
    void shouldKeepFirstToolWhenDuplicateNamesExist() {
        ToolCallback reactTool = mockTool("same_execute");
        CodeactTool codeactTool = mockCodeactTool("same_execute");

        ToolCallback[] merged = AssistantAgentFactory.mergeReactAndCodeactToolCallbacks(
                List.of(reactTool),
                List.of(codeactTool));

        assertEquals(1, merged.length);
        assertSame(reactTool, merged[0]);
    }

    @Test
    void shouldMergeRegistryReactAccessibleToolsWithoutUsingAllTools() {
        CodeactTool staticTool = mockCodeactTool("unified_search");
        CodeactTool scopedDirectTool = mockCodeactTool("kb_search");
        TenantAwareToolRegistry registry = mock(TenantAwareToolRegistry.class);
        when(registry.getReactAccessibleTools()).thenReturn(List.of(scopedDirectTool));

        List<CodeactTool> collected = AssistantAgentFactory.collectReactAccessibleCodeactTools(
                List.of(staticTool),
                registry);

        List<String> names = collected.stream()
                .map(tool -> tool.getToolDefinition().name())
                .collect(Collectors.toList());
        assertEquals(2, collected.size());
        assertTrue(names.contains("unified_search"));
        assertTrue(names.contains("kb_search"));
        verify(registry).getReactAccessibleTools();
        verify(registry, never()).getAllTools();
    }

    @Test
    void shouldBuildHumanInTheLoopHookForExecuteToolsAndArtifactExecute() {
        ToolCallback slotCollect = mockTool("slot_collect");
        ToolCallback artifactExecute = mockTool("artifact_execute");

        HumanInTheLoopHook hook = AssistantAgentFactory.buildHumanInTheLoopHook(
                new ToolCallback[] { slotCollect, artifactExecute });

        assertNotNull(hook);
    }

    @Test
    void shouldReturnNullHookWhenNoExecuteTools() {
        ToolCallback slotCollect = mockTool("slot_collect");
        ToolCallback slotConfirm = mockTool("slot_confirm");

        HumanInTheLoopHook hook = AssistantAgentFactory.buildHumanInTheLoopHook(
                new ToolCallback[] { slotCollect, slotConfirm });

        assertNull(hook);
    }

    @Test
    void shouldReturnNullHookWhenToolsEmpty() {
        assertNull(AssistantAgentFactory.buildHumanInTheLoopHook(new ToolCallback[0]));
        assertNull(AssistantAgentFactory.buildHumanInTheLoopHook(null));
    }

    private ToolCallback mockTool(String toolName) {
        ToolCallback callback = mock(ToolCallback.class);
        when(callback.getToolDefinition()).thenReturn(DefaultToolDefinition.builder()
                .name(toolName)
                .description("test")
                .inputSchema("{}")
                .build());
        return callback;
    }

    private CodeactTool mockCodeactTool(String toolName) {
        CodeactTool callback = mock(CodeactTool.class);
        when(callback.getToolDefinition()).thenReturn(DefaultToolDefinition.builder()
                .name(toolName)
                .description("test")
                .inputSchema("{}")
                .build());
        return callback;
    }

}



