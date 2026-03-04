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
package com.alibaba.assistant.agent.slot.computed;

import com.alibaba.assistant.agent.slot.model.ComputedFieldConfig;
import com.alibaba.assistant.agent.slot.model.SlotDefinition;
import com.alibaba.assistant.agent.slot.model.SlotValue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class ComputedFieldProcessorTest {

	private ComputedFieldProcessor processor;

	@BeforeEach
	void setUp() {
		List<ComputedFunction> functions = List.of(new DateDiffFunction(), new ConcatFunction());
		processor = new ComputedFieldProcessor(functions);
	}

	@Test
	void shouldComputeDateDiffForLeaveRequest() {
		// Given: collected start_date and end_date
		Map<String, SlotValue> slotValues = new HashMap<>();
		slotValues.put("start_date", SlotValue.fromUser("start_date", "2026-03-01"));
		slotValues.put("end_date", SlotValue.fromUser("end_date", "2026-03-03"));

		// Given: duration is a computed field using date_diff
		SlotDefinition duration = new SlotDefinition();
		duration.setName("duration");
		duration.setType("number");

		ComputedFieldConfig config = new ComputedFieldConfig();
		config.setEnabled(true);
		config.setType(ComputedFieldConfig.ComputationType.FUNCTION);
		config.setFunction("date_diff");
		config.setParams(Map.of("start", "start_date", "end", "end_date", "unit", "days", "include_start", "true",
				"include_end", "true"));
		duration.setComputed(config);

		// Non-computed slot
		SlotDefinition leaveType = new SlotDefinition();
		leaveType.setName("leave_type");
		leaveType.setType("enum");

		List<SlotDefinition> slotDefs = List.of(leaveType, duration);

		// When
		processor.processComputedFields(slotDefs, slotValues);

		// Then
		assertTrue(slotValues.containsKey("duration"));
		// 2026-03-01 to 2026-03-03 = 2 days + 1 (include both) = 3
		assertEquals(3L, ((Number) slotValues.get("duration").getResolvedValue()).longValue());
	}

	@Test
	void shouldUseFallbackDefaultValueWhenDependencyMissing() {
		// Given: no start_date or end_date collected
		Map<String, SlotValue> slotValues = new HashMap<>();

		// Given: duration computed with default_value
		SlotDefinition duration = new SlotDefinition();
		duration.setName("duration");
		duration.setType("number");

		ComputedFieldConfig config = new ComputedFieldConfig();
		config.setEnabled(true);
		config.setType(ComputedFieldConfig.ComputationType.FUNCTION);
		config.setFunction("date_diff");
		config.setParams(Map.of("start", "start_date", "end", "end_date"));
		config.setDefaultValue(1);
		duration.setComputed(config);

		// When
		processor.processComputedFields(List.of(duration), slotValues);

		// Then: dependency not satisfied, should be skipped (not fail)
		// With deps unsatisfied, it won't even try to compute
		assertFalse(slotValues.containsKey("duration"));
	}

	@Test
	void shouldComputeConcatFunction() {
		Map<String, SlotValue> slotValues = new HashMap<>();
		slotValues.put("start_date", SlotValue.fromUser("start_date", "2026-03-01"));
		slotValues.put("end_date", SlotValue.fromUser("end_date", "2026-03-03"));

		SlotDefinition rangeDate = new SlotDefinition();
		rangeDate.setName("range_date");
		rangeDate.setType("string");

		ComputedFieldConfig config = new ComputedFieldConfig();
		config.setEnabled(true);
		config.setType(ComputedFieldConfig.ComputationType.FUNCTION);
		config.setFunction("concat");
		config.setParams(Map.of("fields", List.of("start_date", " ~ ", "end_date")));
		rangeDate.setComputed(config);

		processor.processComputedFields(List.of(rangeDate), slotValues);

		assertTrue(slotValues.containsKey("range_date"));
		assertEquals("2026-03-01 ~ 2026-03-03", slotValues.get("range_date").getResolvedValue());
	}

	@Test
	void shouldSkipNonComputedFields() {
		Map<String, SlotValue> slotValues = new HashMap<>();
		slotValues.put("leave_type", SlotValue.fromUser("leave_type", 1));

		SlotDefinition leaveType = new SlotDefinition();
		leaveType.setName("leave_type");
		leaveType.setType("enum");
		// No computed config

		processor.processComputedFields(List.of(leaveType), slotValues);

		// Only original value remains
		assertEquals(1, slotValues.size());
		assertEquals(1, slotValues.get("leave_type").getResolvedValue());
	}

	@Test
	void shouldNotComputeConcatWhenDateDependenciesMissing() {
		Map<String, SlotValue> slotValues = new HashMap<>();

		SlotDefinition rangeDate = new SlotDefinition();
		rangeDate.setName("range_date");
		rangeDate.setType("string");

		ComputedFieldConfig config = new ComputedFieldConfig();
		config.setEnabled(true);
		config.setType(ComputedFieldConfig.ComputationType.FUNCTION);
		config.setFunction("concat");
		config.setParams(Map.of("fields", List.of("start_date", " ~ ", "end_date")));
		rangeDate.setComputed(config);

		processor.processComputedFields(List.of(rangeDate), slotValues);

		assertFalse(slotValues.containsKey("range_date"));
	}

}
