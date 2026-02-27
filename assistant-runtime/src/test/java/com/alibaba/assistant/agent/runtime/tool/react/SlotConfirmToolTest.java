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

import com.alibaba.assistant.agent.slot.SlotEnricherService;
import com.alibaba.assistant.agent.slot.SlotSchemaParser;
import com.alibaba.assistant.agent.slot.computed.ComputedFieldProcessor;
import com.alibaba.assistant.agent.slot.computed.ConcatFunction;
import com.alibaba.assistant.agent.slot.computed.DateDiffFunction;
import com.alibaba.assistant.agent.slot.form.FormDisplayConfigService;
import com.alibaba.assistant.agent.slot.model.EnrichedSlot;
import com.alibaba.assistant.agent.slot.model.SlotDefinition;
import com.alibaba.assistant.agent.slot.model.SlotOption;
import com.alibaba.assistant.agent.slot.model.ToolMetaSnapshot;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

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
    void shouldBuildConfirmPayloadWithComputedAndAutoSelectedValues() {
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

        SlotConfirmTool.Request request = new SlotConfirmTool.Request();
        request.toolCode = "leave_application";
        request.slotSchema = LEAVE_SLOT_SCHEMA;
        request.systemCode = "oa";
        request.assistantUid = "u1";
        request.collectedSlots.put("types", 2);
        request.collectedSlots.put("start_date", "2026-02-26");
        request.collectedSlots.put("end_date", "2026-02-27");

        SlotConfirmTool.Response response = tool.apply(request, null);

        assertEquals("CONFIRMING", response.status);
        assertEquals("CONFIRMING", response.phase);
        assertNotNull(response.confirmForm);
        assertNotNull(response.confirmForm.collected);
        assertEquals(2L, ((Number) response.confirmForm.collected.get("duration")).longValue());
        assertEquals(1, ((Number) response.confirmForm.collected.get("check_flow_id")).intValue());
        assertNotNull(response.confirmForm.formSummary);
        assertTrue(response.confirmForm.formSummary.getSummaryItems()
                .stream()
                .anyMatch(item -> "Annual Leave".equals(item.getValue())));
    }
}
