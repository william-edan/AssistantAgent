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

import com.alibaba.assistant.agent.common.enums.Language;
import com.alibaba.assistant.agent.common.tools.CodeactTool;
import com.alibaba.assistant.agent.common.tools.CodeactToolMetadata;
import com.alibaba.assistant.agent.common.tools.DefaultCodeactToolMetadata;
import com.alibaba.assistant.agent.execution.flow.FlowDefinition;
import com.alibaba.assistant.agent.runtime.compiler.RuntimeArtifact;
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
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;

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
        stubCompleteCollection(collector, parser);
        when(lookupService.findPublishedArtifact(eq("oa.leave.apply"), eq((ToolContext) null)))
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

    @Test
    void shouldResolveSlotSchemaFromPublishedDirectToolWhenOnlyToolCodeIsProvided() {
        SlotCollectorService collector = mock(SlotCollectorService.class);
        SlotEnricherService enricher = mock(SlotEnricherService.class);
        ComputedFieldProcessor computed = mock(ComputedFieldProcessor.class);
        SlotSchemaParser parser = mock(SlotSchemaParser.class);
        ArtifactPublicationLookupService lookupService = mock(ArtifactPublicationLookupService.class);
        stubCompleteCollection(collector, parser);

        ToolDefinition definition = DefaultToolDefinition.builder()
                .name("oa.leave.query")
                .description("Leave query")
                .inputSchema("""
                        {
                          "slots": [
                            {"name": "reason", "type": "string", "priority": "CORE", "required": true, "ask_mode": "BATCH"}
                          ]
                        }
                        """)
                .build();
        StubCodeactTool directTool = new StubCodeactTool(
                definition,
                DefaultCodeactToolMetadata.builder()
                        .addSupportedLanguage(Language.PYTHON)
                        .displayName("Leave query")
                        .addAlias("oa.leave.query")
                        .build(),
                "{}");
        when(lookupService.findPublishedArtifact(eq("oa.leave.query"), eq((ToolContext) null)))
                .thenReturn(Optional.of(new PublishedToolDescriptor(
                        "tool-meta-catalog",
                        "oa.leave.query@1",
                        "Leave query",
                        null,
                        null,
                        false,
                        "gougu_oa",
                        null,
                        directTool)));

        SlotCollectTool tool = new SlotCollectTool(
                collector,
                enricher,
                computed,
                parser,
                new ObjectMapper(),
                null,
                null,
                null,
                lookupService);
        SlotCollectTool.Request request = new SlotCollectTool.Request();
        request.toolCode = "oa.leave.query";
        request.extractedSlots = Map.of("reason", "事假");

        SlotCollectTool.Response response = tool.apply(request, null);

        ArgumentCaptor<ToolMetaSnapshot> snapshotCaptor = ArgumentCaptor.forClass(ToolMetaSnapshot.class);
        verify(parser).parse(snapshotCaptor.capture());
        assertEquals("oa.leave.query", snapshotCaptor.getValue().getToolCode());
        assertEquals("gougu_oa", snapshotCaptor.getValue().getSystemCode());
        assertTrue(snapshotCaptor.getValue().getSlotSchema().contains("reason"));
        assertEquals(SlotCollectStatus.COMPLETE.name(), response.status);
    }

    private void stubCompleteCollection(SlotCollectorService collector, SlotSchemaParser parser) {
        SlotDefinition reason = new SlotDefinition();
        reason.setName("reason");
        reason.setPriority(SlotPriority.CORE);
        reason.setAskMode(SlotAskMode.BATCH);
        reason.setRequired(true);

        when(parser.parse(any(ToolMetaSnapshot.class))).thenReturn(List.of(reason));
        when(collector.collectFromAgent(anyMap(), anyList(), anyMap())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Map<String, Object> extraction = invocation.getArgument(0);
            Map<String, SlotValue> mapped = new LinkedHashMap<>();
            extraction.forEach((key, value) -> mapped.put(key, SlotValue.resolved(key, String.valueOf(value), value, String.valueOf(value))));
            return mapped;
        });
        when(collector.checkCollectionStatus(anyList(), anyMap())).thenReturn(SlotCollectStatus.COMPLETE);
        when(collector.buildFinalParams(anyList(), anyMap())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Map<String, SlotValue> slotValues = invocation.getArgument(1);
            Map<String, Object> resolved = new LinkedHashMap<>();
            slotValues.forEach((key, value) -> resolved.put(key, value.getResolvedValue()));
            return resolved;
        });
    }

    private PublishedToolDescriptor descriptor(String artifactCode, String systemCode) {
        return PublishedToolDescriptor.forArtifact(
                "tool-meta-catalog",
                artifactCode + "@1",
                artifactCode,
                null,
                null,
                false,
                systemCode,
                new RuntimeArtifact(
                        1L,
                        artifactCode,
                        RuntimeArtifact.ArtifactType.WORKFLOW,
                        artifactCode,
                        1,
                        null,
                        null,
                        null,
                        null,
                        new RuntimeArtifact.Interaction(
                                1L,
                                artifactCode,
                                """
                                        {
                                          "slots": [
                                            {"name": "reason", "type": "string", "priority": "CORE", "required": true, "ask_mode": "BATCH"}
                                          ]
                                        }
                                        """,
                                null,
                                null),
                        new FlowDefinition(),
                        Map.of(),
                        Map.of()));
    }

    private static final class StubCodeactTool implements CodeactTool {

        private final ToolDefinition toolDefinition;
        private final CodeactToolMetadata metadata;
        private final String response;

        private StubCodeactTool(ToolDefinition toolDefinition, CodeactToolMetadata metadata, String response) {
            this.toolDefinition = toolDefinition;
            this.metadata = metadata;
            this.response = response;
        }

        @Override
        public String call(String toolInput) {
            return response;
        }

        @Override
        public String call(String toolInput, ToolContext toolContext) {
            return response;
        }

        @Override
        public ToolDefinition getToolDefinition() {
            return toolDefinition;
        }

        @Override
        public CodeactToolMetadata getCodeactMetadata() {
            return metadata;
        }
    }
}

