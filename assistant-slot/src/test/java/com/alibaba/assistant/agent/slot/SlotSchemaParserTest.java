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
package com.alibaba.assistant.agent.slot;

import com.alibaba.assistant.agent.slot.model.ComputedFieldConfig;
import com.alibaba.assistant.agent.slot.model.SlotAskMode;
import com.alibaba.assistant.agent.slot.model.SlotDefinition;
import com.alibaba.assistant.agent.slot.model.SlotOptions;
import com.alibaba.assistant.agent.slot.model.SlotPriority;
import com.alibaba.assistant.agent.slot.model.ToolMetaSnapshot;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SlotSchemaParserTest {

    private SlotSchemaParser parser;

    @BeforeEach
    void setUp() {
        parser = new SlotSchemaParser(new ObjectMapper());
    }

    @Test
    void shouldParseRealLeaveSlotSchema() throws Exception {
        String slotSchema = loadResource("leave-slot-schema.json");
        ToolMetaSnapshot snapshot = new ToolMetaSnapshot("create_leave", slotSchema);

        List<SlotDefinition> slots = parser.parse(snapshot);

        assertEquals(6, slots.size());

        SlotDefinition leaveType = slots.get(0);
        assertEquals("leave_type", leaveType.getName());
        assertEquals("enum", leaveType.getType());
        assertEquals(SlotPriority.CORE, leaveType.getPriority());
        assertTrue(leaveType.isRequired());
        assertEquals(SlotAskMode.BATCH, leaveType.getAskMode());
        assertNotNull(leaveType.getOptions());
        assertNotNull(leaveType.getOptions().getEnumMapping());
        assertEquals(4, leaveType.getOptions().getEnumMapping().size());
        assertNotNull(leaveType.getDisplayConfig());
        assertTrue(leaveType.getDisplayConfig().isShowInSummary());

        SlotDefinition endDate = slots.get(2);
        assertEquals("end_date", endDate.getName());
        assertNotNull(endDate.getDependsOn());
        assertEquals(List.of("start_date"), endDate.getDependsOn());

        SlotDefinition duration = slots.get(3);
        assertTrue(duration.isComputed());
        assertEquals(ComputedFieldConfig.ComputationType.FUNCTION, duration.getComputed().getType());
        assertEquals("date_diff", duration.getComputed().getFunction());
        assertNotNull(duration.getComputed().getDefaultValue());

        SlotDefinition reason = slots.get(4);
        assertEquals("个人事务", reason.getDefaultValue());
        assertEquals(SlotPriority.CONFIRM, reason.getPriority());

        SlotDefinition checkUids = slots.get(5);
        assertEquals(SlotAskMode.FORM_ONLY, checkUids.getAskMode());
        assertNotNull(checkUids.getOptions().getApiConfig());
        assertEquals("/api/employees", checkUids.getOptions().getApiConfig().getEndpoint());
    }

    @Test
    void shouldReturnEmptyForNonSlotSchemaPayload() throws Exception {
        String jsonSchema = loadResource("leave-request-schema.json");
        ToolMetaSnapshot snapshot = new ToolMetaSnapshot("create_leave", jsonSchema);

        assertTrue(parser.parse(snapshot).isEmpty());
    }

    @Test
    void shouldReturnEmptyForNullSnapshot() {
        assertTrue(parser.parse(null).isEmpty());
    }

    @Test
    void shouldReturnEmptyForEmptySchemas() {
        ToolMetaSnapshot snapshot = new ToolMetaSnapshot("test", null);
        assertTrue(parser.parse(snapshot).isEmpty());
    }

    @Test
    void shouldParseCamelCaseDefaultValue() {
        String slotSchema = """
                {
                  "slots": [
                    {
                      "name": "send",
                      "type": "integer",
                      "required": false,
                      "askMode": "FORM_ONLY",
                      "defaultValue": 0
                    }
                  ]
                }
                """;
        ToolMetaSnapshot snapshot = new ToolMetaSnapshot("work_report", slotSchema);

        List<SlotDefinition> slots = parser.parse(snapshot);

        assertEquals(1, slots.size());
        assertEquals(0, slots.get(0).getDefaultValue());
    }

    @Test
    void shouldParseToolBackedOptions() {
        String slotSchema = """
                {
                  "slots": [
                    {
                      "name": "check_uids",
                      "type": "string",
                      "title": "审批人",
                      "options": {
                        "source": "TOOL",
                        "tool": {
                          "toolCode": "gougu_oa.approver_candidates",
                          "resultPath": "data",
                          "labelField": "name",
                          "valueField": "id",
                          "descriptionField": "role"
                        }
                      }
                    }
                  ]
                }
                """;
        ToolMetaSnapshot snapshot = new ToolMetaSnapshot("leave_application", slotSchema);

        List<SlotDefinition> slots = parser.parse(snapshot);

        assertEquals(1, slots.size());
        assertNotNull(slots.get(0).getOptions());
        assertEquals(SlotOptions.SourceType.TOOL, slots.get(0).getOptions().getSource());
        assertNotNull(slots.get(0).getOptions().getToolConfig());
        assertEquals("gougu_oa.approver_candidates", slots.get(0).getOptions().getToolConfig().getToolCode());
        assertEquals("data", slots.get(0).getOptions().getToolConfig().getResultPath());
        assertEquals("name", slots.get(0).getOptions().getToolConfig().getLabelField());
        assertEquals("id", slots.get(0).getOptions().getToolConfig().getValueField());
        assertEquals("role", slots.get(0).getOptions().getToolConfig().getDescriptionField());
    }

    private String loadResource(String name) throws Exception {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(name)) {
            assertNotNull(is, "Resource not found: " + name);
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
