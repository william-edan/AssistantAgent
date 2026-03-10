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

import com.alibaba.assistant.agent.controlplane.connector.Connector;
import com.alibaba.assistant.agent.controlplane.connector.ConnectorService;
import com.alibaba.assistant.agent.execution.flow.FlowDefinition;
import com.alibaba.assistant.agent.runtime.compiler.RuntimeArtifact;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ArtifactCatalogToolPublicationProviderTest {

    @Test
    void shouldPublishGrantedArtifactsForScopedAgentApp() {
        RuntimeArtifactCatalogService catalogService = mock(RuntimeArtifactCatalogService.class);
        ConnectorService connectorService = mock(ConnectorService.class);
        ArtifactCatalogToolPublicationProvider provider = new ArtifactCatalogToolPublicationProvider(catalogService, connectorService);
        RuntimeArtifact workflow = new RuntimeArtifact(
                1L,
                "oa.leave.apply",
                RuntimeArtifact.ArtifactType.WORKFLOW,
                "请假申请",
                2,
                null,
                null,
                null,
                null,
                null,
                new FlowDefinition(),
                Map.of(),
                Map.of("create", new RuntimeArtifact.StepBinding(
                        "create",
                        "创建请假",
                        "HTTP",
                        101L,
                        "oa.leave.create",
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        1,
                        null)));
        Connector connector = new Connector();
        connector.setId(101L);
        connector.setSystemCode("gougu_oa");
        when(catalogService.listGrantedArtifacts(1L, "hr-assistant")).thenReturn(List.of(workflow));
        when(connectorService.getById(101L)).thenReturn(connector);

        List<PublishedToolDescriptor> descriptors = provider.listPublishedTools(
                new ToolPublicationProvider.PublicationScope("default", 1L, "prod", "hr-assistant"));

        assertEquals(1, descriptors.size());
        assertEquals("artifact-catalog", descriptors.get(0).sourceType());
        assertEquals("workflow:oa.leave.apply", descriptors.get(0).publicationKey());
        assertEquals("请假申请", descriptors.get(0).displayName());
        assertEquals("gougu_oa", descriptors.get(0).executionSystemCode());
        assertTrue(descriptors.get(0).isArtifactPublication());
    }

    @Test
    void shouldReturnEmptyWhenSpaceOrAgentAppIsMissing() {
        RuntimeArtifactCatalogService catalogService = mock(RuntimeArtifactCatalogService.class);
        ConnectorService connectorService = mock(ConnectorService.class);
        ArtifactCatalogToolPublicationProvider provider = new ArtifactCatalogToolPublicationProvider(catalogService, connectorService);

        assertTrue(provider.listPublishedTools(new ToolPublicationProvider.PublicationScope("default", null, "prod", "hr-assistant")).isEmpty());
        assertTrue(provider.listPublishedTools(new ToolPublicationProvider.PublicationScope("default", 1L, "prod", null)).isEmpty());
    }

}
