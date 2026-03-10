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
package com.alibaba.assistant.agent.runtime.tool.react;

import com.alibaba.assistant.agent.execution.flow.FlowDefinition;
import com.alibaba.assistant.agent.runtime.registry.ArtifactPublicationLookupService;
import com.alibaba.assistant.agent.runtime.registry.PublishedToolDescriptor;
import com.alibaba.assistant.agent.slot.SlotCollectorService;
import com.alibaba.assistant.agent.slot.SlotEnricherService;
import com.alibaba.assistant.agent.slot.SlotSchemaParser;
import com.alibaba.assistant.agent.slot.computed.ComputedFieldProcessor;
import com.alibaba.assistant.agent.slot.model.SlotAskMode;
import com.alibaba.assistant.agent.slot.model.SlotCollectStatus;
import com.alibaba.assistant.agent.slot.model.SlotDefinition;
import com.alibaba.assistant.agent.slot.model.SlotPriority;
import com.alibaba.assistant.agent.slot.model.SlotValue;
import com.alibaba.assistant.agent.slot.model.ToolMetaSnapshot;
import com.alibaba.assistant.agent.runtime.compiler.RuntimeArtifact;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ArtifactAwareSlotCollectToolTest {

    @Test
    void shouldResolveSlotSchemaFromPublishedArtifactWhenOnlyToolCodeIsProvided() {
        SlotCollectorService collector = mock(SlotCollectorService.class);
        SlotEnricherService enricher = mock(SlotEnricherService.class);
        ComputedFieldProcessor computed = mock(ComputedFieldProcessor.class);
        SlotSchemaParser parser = mock(SlotSchemaParser.class);
        ArtifactPublicationLookupService lookupService = mock(ArtifactPublicationLookupService.class);

        SlotDefinition reason = new SlotDefinition();
        reason.setName("reason");
        reason.setType("string");
        reason.setPriority(SlotPriority.CORE);
        reason.setAskMode(SlotAskMode.BATCH);
        reason.setRequired(true);
        when(parser.parse(any(ToolMetaSnapshot.class))).thenReturn(List.of(reason));
        when(collector.collectFromAgent(anyMap(), anyList(), anyMap())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Map<String, Object> extraction = invocation.getArgument(0);
            Map<String, SlotValue> collected = new LinkedHashMap<>();
            extraction.forEach((key, value) -> collected.put(key, SlotValue.resolved(key, String.valueOf(value), value, String.valueOf(value))));
            return collected;
        });
        when(collector.checkCollectionStatus(anyList(), anyMap())).thenReturn(SlotCollectStatus.COMPLETE);
        when(collector.buildFinalParams(anyList(), anyMap())).thenAnswer(invocation -> Map.of("reason", "事假"));
        when(lookupService.findPublishedArtifact(eq("oa.leave.apply"), any()))
                .thenReturn(Optional.of(descriptor("oa.leave.apply", "gougu_oa")));

        SlotCollectTool tool = new SlotCollectTool(
                collector,
                enricher,
                computed,
                parser,
                new ObjectMapper(),
                null,
                null,
                null,
                null,
                lookupService);
        SlotCollectTool.Request request = new SlotCollectTool.Request();
        request.toolCode = "oa.leave.apply";
        request.extractedSlots = Map.of("reason", "事假");

        SlotCollectTool.Response response = tool.apply(request, null);

        ArgumentCaptor<ToolMetaSnapshot> snapshotCaptor = ArgumentCaptor.forClass(ToolMetaSnapshot.class);
        verify(parser).parse(snapshotCaptor.capture());
        assertEquals("oa.leave.apply", snapshotCaptor.getValue().getToolCode());
        assertEquals("gougu_oa", snapshotCaptor.getValue().getSystemCode());
        assertTrue(snapshotCaptor.getValue().getSlotSchema().contains("reason"));
        assertEquals(SlotCollectStatus.COMPLETE.name(), response.status);
    }

    private PublishedToolDescriptor descriptor(String artifactCode, String systemCode) {
        RuntimeArtifact.Interaction interaction = new RuntimeArtifact.Interaction(
                1L,
                artifactCode + ".interaction",
                "{\"slots\":[{\"name\":\"reason\",\"type\":\"string\",\"required\":true}]}",
                null,
                null,
                null,
                null,
                null);
        RuntimeArtifact artifact = new RuntimeArtifact(
                1L,
                artifactCode,
                RuntimeArtifact.ArtifactType.WORKFLOW,
                "请假申请",
                1,
                null,
                null,
                null,
                null,
                interaction,
                new FlowDefinition(),
                Map.of(),
                Map.of());
        return PublishedToolDescriptor.forArtifact(
                "artifact-catalog",
                "workflow:" + artifactCode,
                "请假申请",
                null,
                null,
                false,
                systemCode,
                artifact);
    }
}
