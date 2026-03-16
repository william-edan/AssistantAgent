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

import com.alibaba.assistant.agent.controlplane.toolregistry.ToolMeta;
import com.alibaba.assistant.agent.controlplane.toolregistry.ToolMetaControlPlaneMapper;
import com.alibaba.assistant.agent.controlplane.toolregistry.ToolMetaService;
import com.alibaba.assistant.agent.execution.flow.FlowDefinition;
import com.alibaba.assistant.agent.runtime.compiler.RuntimeArtifact;
import com.alibaba.assistant.agent.runtime.compiler.ToolMetaRuntimeArtifactCompiler;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ToolMetaCatalogToolPublicationProviderTest {

    @Test
    void shouldPublishCompiledArtifactsForTenantScopedToolMeta() {
        ToolMetaService toolMetaService = mock(ToolMetaService.class);
        ToolMetaRuntimeArtifactCompiler compiler = mock(ToolMetaRuntimeArtifactCompiler.class);
        ToolMetaControlPlaneMapper mapper = new ToolMetaControlPlaneMapper(new ObjectMapper());
        ToolMetaCatalogToolPublicationProvider provider =
                new ToolMetaCatalogToolPublicationProvider(toolMetaService, compiler, mapper);

        ToolMeta toolMeta = new ToolMeta();
        toolMeta.setToolCode("gougu_oa.leave_application");
        toolMeta.setToolName("请假申请");
        toolMeta.setDescription("提交请假申请");
        toolMeta.setSystemCode("gougu_oa");

        RuntimeArtifact artifact = new RuntimeArtifact(
                1L,
                "gougu_oa.leave_application",
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

        ToolPublicationProvider.PublicationScope scope =
                new ToolPublicationProvider.PublicationScope("tenant-a", 1L, "prod", "hr-assistant");
        when(toolMetaService.listEnabledByTenant("tenant-a")).thenReturn(List.of(toolMeta));
        when(compiler.compile(toolMeta)).thenReturn(artifact);

        List<PublishedToolDescriptor> descriptors = provider.listPublishedTools(scope);

        assertEquals(1, descriptors.size());
        assertTrue(descriptors.get(0).isArtifactPublication());
        assertEquals("tool-meta-catalog", descriptors.get(0).sourceType());
        assertEquals("tool:gougu_oa.leave_application", descriptors.get(0).publicationKey());
        assertEquals("ACTION", descriptors.get(0).toolType());
        assertEquals("USER", descriptors.get(0).visibility());
        assertEquals("DIRECT", descriptors.get(0).invocationPolicy());
        verify(toolMetaService).listEnabledByTenant("tenant-a");
        verify(compiler).compile(toolMeta);
    }

    @Test
    void shouldFallbackToDefaultTenantWhenScopeTenantBlank() {
        ToolMetaService toolMetaService = mock(ToolMetaService.class);
        ToolMetaRuntimeArtifactCompiler compiler = mock(ToolMetaRuntimeArtifactCompiler.class);
        ToolMetaControlPlaneMapper mapper = new ToolMetaControlPlaneMapper(new ObjectMapper());
        ToolMetaCatalogToolPublicationProvider provider =
                new ToolMetaCatalogToolPublicationProvider(toolMetaService, compiler, mapper);
        when(toolMetaService.listEnabledByTenant("default")).thenReturn(List.of());

        provider.listPublishedTools(new ToolPublicationProvider.PublicationScope(null, null, null, null));

        verify(toolMetaService).listEnabledByTenant("default");
    }

    @Test
    void shouldCarryTypedPublicationContractFromToolMeta() {
        ToolMetaService toolMetaService = mock(ToolMetaService.class);
        ToolMetaRuntimeArtifactCompiler compiler = mock(ToolMetaRuntimeArtifactCompiler.class);
        ToolMetaControlPlaneMapper mapper = new ToolMetaControlPlaneMapper(new ObjectMapper());
        ToolMetaCatalogToolPublicationProvider provider =
                new ToolMetaCatalogToolPublicationProvider(toolMetaService, compiler, mapper);

        ToolMeta toolMeta = new ToolMeta();
        toolMeta.setToolCode("gougu_oa.employee_search");
        toolMeta.setToolName("员工查询");
        toolMeta.setInteractionPolicy("""
                {"toolType":"QUERY","visibility":"PLANNER","invocationPolicy":"COMPOSABLE","executionMode":"SYNC"}
                """);

        RuntimeArtifact artifact = new RuntimeArtifact(
                2L,
                "gougu_oa.employee_search",
                RuntimeArtifact.ArtifactType.WORKFLOW,
                "员工查询",
                1,
                null,
                null,
                null,
                null,
                null,
                new FlowDefinition(),
                Map.of(),
                Map.of());
        when(toolMetaService.listEnabledByTenant("default")).thenReturn(List.of(toolMeta));
        when(compiler.compile(toolMeta)).thenReturn(artifact);

        PublishedToolDescriptor descriptor = provider.listPublishedTools(
                        new ToolPublicationProvider.PublicationScope("default", null, null, null))
                .get(0);

        assertEquals("QUERY", descriptor.toolType());
        assertEquals("PLANNER", descriptor.visibility());
        assertEquals("COMPOSABLE", descriptor.invocationPolicy());
    }
}
