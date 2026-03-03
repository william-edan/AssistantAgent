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

import com.alibaba.assistant.agent.slot.model.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SlotSchemaParserTest {

	private SlotSchemaParser parser;

	@BeforeEach
	void setUp() {
		parser = new SlotSchemaParser(new ObjectMapper());
	}

	@Test
	void shouldParseRealLeaveSlotSchema() throws Exception {
		String slotSchema = loadResource("leave-slot-schema.json");
		ToolMetaSnapshot snapshot = new ToolMetaSnapshot("create_leave", slotSchema, null);

		List<SlotDefinition> slots = parser.parse(snapshot);

		assertEquals(6, slots.size());

		// leave_type
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

		// end_date depends_on
		SlotDefinition endDate = slots.get(2);
		assertEquals("end_date", endDate.getName());
		assertNotNull(endDate.getDependsOn());
		assertEquals(1, endDate.getDependsOn().size());
		assertEquals("start_date", endDate.getDependsOn().get(0));

		// duration computed
		SlotDefinition duration = slots.get(3);
		assertTrue(duration.isComputed());
		assertEquals(ComputedFieldConfig.ComputationType.FUNCTION, duration.getComputed().getType());
		assertEquals("date_diff", duration.getComputed().getFunction());
		assertNotNull(duration.getComputed().getDefaultValue());

		// reason default
		SlotDefinition reason = slots.get(4);
		assertEquals("个人事务", reason.getDefaultValue());
		assertEquals(SlotPriority.CONFIRM, reason.getPriority());

		// check_uids API options
		SlotDefinition checkUids = slots.get(5);
		assertEquals(SlotAskMode.FORM_ONLY, checkUids.getAskMode());
		assertNotNull(checkUids.getOptions().getApiConfig());
		assertEquals("/api/employees", checkUids.getOptions().getApiConfig().getEndpoint());
	}

	@Test
	void shouldFallbackToLegacyRequestSchema() throws Exception {
		String requestSchema = loadResource("leave-request-schema.json");
		ToolMetaSnapshot snapshot = new ToolMetaSnapshot("create_leave", null, requestSchema);

		List<SlotDefinition> slots = parser.parse(snapshot);

		assertEquals(4, slots.size());

		SlotDefinition leaveType = slots.stream()
			.filter(s -> "leave_type".equals(s.getName()))
			.findFirst()
			.orElseThrow();
		assertTrue(leaveType.isRequired());
		assertEquals(SlotPriority.CORE, leaveType.getPriority());
		assertNotNull(leaveType.getOptions());

		SlotDefinition reason = slots.stream()
			.filter(s -> "reason".equals(s.getName()))
			.findFirst()
			.orElseThrow();
		assertFalse(reason.isRequired());
		assertEquals(SlotPriority.OPTIONAL, reason.getPriority());
		assertEquals("个人事务", reason.getDefaultValue());
	}

	@Test
	void shouldReturnEmptyForNullSnapshot() {
		assertTrue(parser.parse(null).isEmpty());
	}

	@Test
	void shouldReturnEmptyForEmptySchemas() {
		ToolMetaSnapshot snapshot = new ToolMetaSnapshot("test", null, null);
		assertTrue(parser.parse(snapshot).isEmpty());
	}

	@Test
	void shouldPreferSlotSchemaOverRequestSchema() throws Exception {
		String slotSchema = loadResource("leave-slot-schema.json");
		String requestSchema = loadResource("leave-request-schema.json");
		ToolMetaSnapshot snapshot = new ToolMetaSnapshot("create_leave", slotSchema, requestSchema);

		List<SlotDefinition> slots = parser.parse(snapshot);

		// slot_schema has 6 slots, request_schema has 4 - should use slot_schema
		assertEquals(6, slots.size());
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
		ToolMetaSnapshot snapshot = new ToolMetaSnapshot("work_report", slotSchema, null);

		List<SlotDefinition> slots = parser.parse(snapshot);

		assertEquals(1, slots.size());
		assertEquals(0, slots.get(0).getDefaultValue());
	}

	private String loadResource(String name) throws Exception {
		try (InputStream is = getClass().getClassLoader().getResourceAsStream(name)) {
			assertNotNull(is, "Resource not found: " + name);
			return new String(is.readAllBytes(), StandardCharsets.UTF_8);
		}
	}

}
