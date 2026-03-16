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
import com.alibaba.assistant.agent.runtime.agent.AssistantStateKeys;
import com.alibaba.assistant.agent.runtime.registry.ArtifactPublicationLookupService;
import com.alibaba.assistant.agent.runtime.registry.PublishedToolDescriptor;
import com.alibaba.assistant.agent.slot.SlotEnricherService;
import com.alibaba.assistant.agent.slot.SlotSchemaParser;
import com.alibaba.assistant.agent.slot.computed.ComputedFieldProcessor;
import com.alibaba.assistant.agent.slot.computed.ConcatFunction;
import com.alibaba.assistant.agent.slot.computed.DateDiffFunction;
import com.alibaba.assistant.agent.slot.form.FormDisplayConfigService;
import com.alibaba.assistant.agent.slot.model.EnrichedSlot;
import com.alibaba.assistant.agent.slot.model.SlotDefinition;
import com.alibaba.assistant.agent.slot.model.SlotOption;
import com.alibaba.assistant.agent.slot.model.SlotValue;
import com.alibaba.assistant.agent.slot.model.ToolMetaSnapshot;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.agent.tools.ToolContextConstants;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SlotConfirmToolTest {

    private static final String LEAVE_SLOT_SCHEMA = """
            {
              "slots": [
                {
                  "name": "types",
                  "type": "integer",
                  "priority": "CORE",
                  "required": true,
                  "ask_mode": "BATCH",
                  "options": {
                    "source": "ENUM",
                    "enum_mapping": {
                      "Annual Leave": 2,
                      "Sick Leave": 4
                    }
                  },
                  "displayConfig": {
                    "showInSummary": true,
                    "summaryOrder": 1,
                    "summaryGroup": "CORE"
                  }
                },
                {
                  "name": "start_date",
                  "type": "date",
                  "priority": "CORE",
                  "required": true,
                  "ask_mode": "BATCH",
                  "displayConfig": {
                    "showInSummary": true,
                    "summaryOrder": 2,
                    "summaryGroup": "CORE"
                  }
                },
                {
                  "name": "end_date",
                  "type": "date",
                  "priority": "CORE",
                  "required": true,
                  "ask_mode": "BATCH",
                  "displayConfig": {
                    "showInSummary": true,
                    "summaryOrder": 3,
                    "summaryGroup": "CORE"
                  }
                },
                {
                  "name": "duration",
                  "type": "number",
                  "priority": "OPTIONAL",
                  "ask_mode": "AUTO",
                  "computed": {
                    "enabled": true,
                    "type": "FUNCTION",
                    "function": "date_diff",
                    "params": {
                      "start": "start_date",
                      "end": "end_date",
                      "unit": "days",
                      "include_start": "true",
                      "include_end": "true"
                    }
                  },
                  "displayConfig": {
                    "showInSummary": true,
                    "summaryOrder": 4,
                    "summaryGroup": "CORE",
                    "displaySuffix": " days"
                  }
                },
                {
                  "name": "check_flow_id",
                  "type": "integer",
                  "priority": "OPTIONAL",
                  "ask_mode": "AUTO",
                  "displayConfig": {
                    "showInSummary": true,
                    "summaryOrder": 5,
                    "summaryGroup": "SECONDARY"
                  }
                }
              ]
            }
            """;

    @Test
    void shouldBuildConfirmPayloadFromMatchedToolSnapshot() {
        ObjectMapper objectMapper = new ObjectMapper();
        SlotSchemaParser slotSchemaParser = new SlotSchemaParser(objectMapper);
        SlotEnricherService slotEnricherService = mock(SlotEnricherService.class);
        ComputedFieldProcessor computedFieldProcessor = new ComputedFieldProcessor(
                List.of(new DateDiffFunction(), new ConcatFunction()));
        FormDisplayConfigService formDisplayConfigService = new FormDisplayConfigService();

        ToolMetaSnapshot snapshot = new ToolMetaSnapshot();
        snapshot.setToolCode("leave_application");
        snapshot.setSlotSchema(LEAVE_SLOT_SCHEMA);
        List<SlotDefinition> definitions = slotSchemaParser.parse(snapshot);
        SlotDefinition flowSlot = definitions.stream()
                .filter(item -> "check_flow_id".equals(item.getName()))
                .findFirst()
                .orElseThrow();
        EnrichedSlot flowEnriched = new EnrichedSlot(flowSlot);
        flowEnriched.setOptions(List.of(new SlotOption("Default Flow", 1), new SlotOption("Backup Flow", 2)));

        when(slotEnricherService.enrichSlots(anyList(), eq("oa"), eq("u1"))).thenReturn(List.of(flowEnriched));

        SlotConfirmTool tool = new SlotConfirmTool(
                slotSchemaParser,
                slotEnricherService,
                computedFieldProcessor,
                formDisplayConfigService,
                objectMapper);

        OverAllState state = new OverAllState();
        state.updateState(Map.of(
                AssistantStateKeys.MATCHED_TOOL_META, snapshot,
                AssistantStateKeys.SYSTEM_CODE, "oa",
                AssistantStateKeys.ASSISTANT_UID, "u1"));
        ToolContext toolContext = new ToolContext(Map.of(ToolContextConstants.AGENT_STATE_CONTEXT_KEY, state));

        SlotConfirmTool.Request request = new SlotConfirmTool.Request();
        request.collectedSlots.put("types", 2);
        request.collectedSlots.put("start_date", "2026-02-26");
        request.collectedSlots.put("end_date", "2026-02-27");

        SlotConfirmTool.Response response = tool.apply(request, toolContext);

        assertEquals("CONFIRMING", response.status);
        assertEquals("CONFIRMING", response.phase);
        assertNotNull(response.confirmForm);
        assertEquals("leave_application", response.confirmForm.toolCode);
        assertEquals(2L, ((Number) response.confirmForm.collected.get("duration")).longValue());
        assertEquals(1, ((Number) response.confirmForm.collected.get("check_flow_id")).intValue());
        assertTrue(response.confirmForm.formSummary.getSummaryItems()
                .stream()
                .anyMatch(item -> "Annual Leave".equals(item.getValue())));
    }

    @Test
    void shouldRejectConfirmWhenStateStillMissesRequiredSlot() {
        ObjectMapper objectMapper = new ObjectMapper();
        SlotSchemaParser slotSchemaParser = new SlotSchemaParser(objectMapper);
        SlotEnricherService slotEnricherService = mock(SlotEnricherService.class);
        ComputedFieldProcessor computedFieldProcessor = new ComputedFieldProcessor(
                List.of(new DateDiffFunction(), new ConcatFunction()));
        FormDisplayConfigService formDisplayConfigService = new FormDisplayConfigService();

        SlotConfirmTool tool = new SlotConfirmTool(
                slotSchemaParser,
                slotEnricherService,
                computedFieldProcessor,
                formDisplayConfigService,
                objectMapper);

        ToolMetaSnapshot snapshot = new ToolMetaSnapshot();
        snapshot.setToolCode("gougu_oa.work_report");
        snapshot.setSlotSchema("""
                {
                  "slots": [
                    {"name": "types", "type": "integer", "priority": "CORE", "required": true, "ask_mode": "BATCH"},
                    {"name": "works", "type": "string", "priority": "CORE", "required": true, "ask_mode": "BATCH"}
                  ]
                }
                """);

        OverAllState state = new OverAllState();
        state.updateState(Map.of(
                AssistantStateKeys.MATCHED_TOOL_META, snapshot,
                AssistantStateKeys.COLLECTED_SLOTS, Map.of("types", SlotValue.fromUser("types", 2))));
        ToolContext toolContext = new ToolContext(Map.of(ToolContextConstants.AGENT_STATE_CONTEXT_KEY, state));

        SlotConfirmTool.Request request = new SlotConfirmTool.Request();
        request.collectedSlots.put("works", "本周完成了部署上线");

        SlotConfirmTool.Response response = tool.apply(request, toolContext);

        assertEquals("COLLECTING", response.status);
        assertEquals("COLLECTING", response.phase);
        assertTrue(response.message.contains("works"));
    }

    @Test
    void shouldResolvePublishedDirectToolWhenStateSnapshotMissing() {
        ObjectMapper objectMapper = new ObjectMapper();
        SlotSchemaParser slotSchemaParser = new SlotSchemaParser(objectMapper);
        SlotEnricherService slotEnricherService = mock(SlotEnricherService.class);
        ComputedFieldProcessor computedFieldProcessor = new ComputedFieldProcessor(
                List.of(new DateDiffFunction(), new ConcatFunction()));
        FormDisplayConfigService formDisplayConfigService = new FormDisplayConfigService();
        ArtifactPublicationLookupService lookupService = mock(ArtifactPublicationLookupService.class);

        ToolDefinition definition = DefaultToolDefinition.builder()
                .name("oa.leave.confirm")
                .description("Leave confirmation")
                .inputSchema(LEAVE_SLOT_SCHEMA)
                .build();
        CodeactToolMetadata metadata = DefaultCodeactToolMetadata.builder()
                .addSupportedLanguage(Language.PYTHON)
                .displayName("Leave confirmation")
                .addAlias("oa.leave.confirm")
                .build();
        StubCodeactTool directTool = new StubCodeactTool(definition, metadata, "{}");

        when(lookupService.findPublishedArtifact(eq("oa.leave.confirm"), eq((ToolContext) null)))
                .thenReturn(Optional.of(new PublishedToolDescriptor(
                        "tool-meta-catalog",
                        "oa.leave.confirm@1",
                        "Leave confirmation",
                        null,
                        null,
                        false,
                        "oa",
                        null,
                        directTool)));
        when(slotEnricherService.enrichSlots(anyList(), eq("oa"), eq("u1"))).thenReturn(List.of());

        SlotConfirmTool tool = new SlotConfirmTool(
                slotSchemaParser,
                slotEnricherService,
                computedFieldProcessor,
                formDisplayConfigService,
                objectMapper,
                lookupService);

        SlotConfirmTool.Request request = new SlotConfirmTool.Request();
        request.toolCode = "oa.leave.confirm";
        request.systemCode = "oa";
        request.assistantUid = "u1";
        request.collectedSlots.put("types", 2);
        request.collectedSlots.put("start_date", "2026-02-26");
        request.collectedSlots.put("end_date", "2026-02-27");

        SlotConfirmTool.Response response = tool.apply(request, null);

        assertEquals("CONFIRMING", response.status);
        assertNotNull(response.confirmForm);
        assertEquals("oa.leave.confirm", response.confirmForm.toolCode);
        assertEquals(2L, ((Number) response.confirmForm.collected.get("duration")).longValue());
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
