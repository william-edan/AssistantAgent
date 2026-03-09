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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class SlotCollectorServiceTest {

	private SlotCollectorService collectorService;

	@BeforeEach
	void setUp() {
		collectorService = new SlotCollectorService();
	}

	@Test
	void shouldCollectFromAgentMapAndMergeWithExisting() {
		// Given: existing collected slots
		Map<String, SlotValue> existing = new HashMap<>();
		existing.put("leave_type", SlotValue.fromUser("leave_type", 1));

		// Given: slot definitions
		List<SlotDefinition> slots = List.of(createSlot("leave_type", "enum"), createSlot("start_date", "date"),
				createSlot("end_date", "date"));

		// Given: new extraction result (overrides leave_type, adds start_date)
		Map<String, Object> extraction = new LinkedHashMap<>();
		extraction.put("leave_type", 2); // override
		extraction.put("start_date", "2026-03-01"); // new

		// When
		Map<String, SlotValue> result = collectorService.collectFromAgent(extraction, slots, existing);

		// Then: merged result has all 3 slots
		assertEquals(2, result.size()); // leave_type (overridden) + start_date (new)
		// Actually the merge puts existing first, then overrides
		assertTrue(result.containsKey("leave_type"));
		assertTrue(result.containsKey("start_date"));

		// New value should override old
		assertEquals(2, ((Number) result.get("leave_type").getResolvedValue()).intValue());
		assertEquals("2026-03-01", result.get("start_date").getResolvedValue().toString());
	}

	@Test
	void shouldSkipNullValuesInExtraction() {
		List<SlotDefinition> slots = List.of(createSlot("leave_type", "enum"));

		Map<String, Object> extraction = new HashMap<>();
		extraction.put("leave_type", null);

		Map<String, SlotValue> result = collectorService.collectFromAgent(extraction, slots, null);

		assertTrue(result.isEmpty());
	}

	@Test
	void shouldSkipUnknownSlotNames() {
		List<SlotDefinition> slots = List.of(createSlot("leave_type", "enum"));

		Map<String, Object> extraction = new HashMap<>();
		extraction.put("unknown_field", "value");

		Map<String, SlotValue> result = collectorService.collectFromAgent(extraction, slots, null);

		assertTrue(result.isEmpty());
	}

	@Test
	void shouldMapEnumLabelToIntegerValueWhenCollecting() {
		SlotDefinition reportType = createSlot("types", "integer");
		SlotOptions options = new SlotOptions();
		options.setSource(SlotOptions.SourceType.ENUM);
		Map<String, Object> enumMapping = new LinkedHashMap<>();
		enumMapping.put("日报", 1);
		enumMapping.put("周报", 2);
		options.setEnumMapping(enumMapping);
		reportType.setOptions(options);

		Map<String, Object> extraction = new HashMap<>();
		extraction.put("types", "日报");

		Map<String, SlotValue> result = collectorService.collectFromAgent(extraction, List.of(reportType), null);

		assertTrue(result.containsKey("types"));
		assertEquals(1, ((Number) result.get("types").getResolvedValue()).intValue());
	}

	@Test
	void shouldGetNextSlotsByPriority() {
		SlotDefinition core1 = createSlot("leave_type", "enum");
		core1.setPriority(SlotPriority.CORE);
		core1.setRequired(true);

		SlotDefinition core2 = createSlot("start_date", "date");
		core2.setPriority(SlotPriority.CORE);
		core2.setRequired(true);

		SlotDefinition optional = createSlot("reason", "string");
		optional.setPriority(SlotPriority.OPTIONAL);

		List<SlotDefinition> allSlots = List.of(optional, core1, core2);

		CollectBehavior behavior = new CollectBehavior();
		behavior.setBatchSize(2);

		List<SlotDefinition> next = collectorService.getNextSlotsToCollect(allSlots, new HashMap<>(), behavior, 1);

		assertEquals(2, next.size());
		// CORE slots should come first
		assertEquals("leave_type", next.get(0).getName());
		assertEquals("start_date", next.get(1).getName());
	}

	@Test
	void shouldDeferInferredSlotsUntilSourceSlotsAreCollected() {
		SlotDefinition types = createSlot("types", "integer");
		types.setPriority(SlotPriority.CORE);
		types.setRequired(true);
		types.setInferredFrom(List.of("reason"));

		SlotDefinition start = createSlot("start_date", "date");
		start.setPriority(SlotPriority.CORE);
		start.setRequired(true);

		SlotDefinition end = createSlot("end_date", "date");
		end.setPriority(SlotPriority.CORE);
		end.setRequired(true);

		SlotDefinition reason = createSlot("reason", "string");
		reason.setPriority(SlotPriority.CORE);
		reason.setRequired(true);

		CollectBehavior behavior = new CollectBehavior();
		behavior.setBatchSize(4);

		List<SlotDefinition> next = collectorService.getNextSlotsToCollect(
				List.of(types, start, end, reason),
				new HashMap<>(),
				behavior,
				1);

		assertEquals(List.of("start_date", "end_date", "reason"),
				next.stream().map(SlotDefinition::getName).toList());
	}
	@Test
	void shouldCheckCollectionStatusComplete() {
		SlotDefinition slot = createSlot("leave_type", "enum");
		slot.setPriority(SlotPriority.CORE);
		slot.setRequired(true);

		Map<String, SlotValue> collected = new HashMap<>();
		collected.put("leave_type", SlotValue.fromUser("leave_type", 1));

		SlotCollectStatus status = collectorService.checkCollectionStatus(List.of(slot), collected);
		assertEquals(SlotCollectStatus.COMPLETE, status);
	}

	@Test
	void shouldCheckCollectionStatusCollecting() {
		SlotDefinition slot = createSlot("leave_type", "enum");
		slot.setPriority(SlotPriority.CORE);
		slot.setRequired(true);

		SlotCollectStatus status = collectorService.checkCollectionStatus(List.of(slot), new HashMap<>());
		assertEquals(SlotCollectStatus.COLLECTING, status);
	}

	@Test
	void shouldRespectDependencies() {
		SlotDefinition start = createSlot("start_date", "date");
		start.setPriority(SlotPriority.CORE);

		SlotDefinition end = createSlot("end_date", "date");
		end.setPriority(SlotPriority.CORE);
		end.setDependsOn(List.of("start_date"));

		List<SlotDefinition> allSlots = List.of(start, end);
		CollectBehavior behavior = CollectBehavior.defaults();

		// Without start_date collected, only start_date should be returned
		List<SlotDefinition> next = collectorService.getNextSlotsToCollect(allSlots, new HashMap<>(), behavior, 1);
		assertEquals(1, next.size());
		assertEquals("start_date", next.get(0).getName());

		// With start_date collected, end_date should now be available
		Map<String, SlotValue> collected = new HashMap<>();
		collected.put("start_date", SlotValue.fromUser("start_date", "2026-03-01"));

		next = collectorService.getNextSlotsToCollect(allSlots, collected, behavior, 1);
		assertEquals(1, next.size());
		assertEquals("end_date", next.get(0).getName());
	}

	private SlotDefinition createSlot(String name, String type) {
		SlotDefinition slot = new SlotDefinition();
		slot.setName(name);
		slot.setType(type);
		return slot;
	}

}

