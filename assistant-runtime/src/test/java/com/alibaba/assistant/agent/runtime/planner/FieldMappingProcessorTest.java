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
package com.alibaba.assistant.agent.runtime.planner;

import com.alibaba.assistant.agent.slot.model.SlotValue;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FieldMappingProcessorTest {

	@Test
	void shouldApplyMappingsIntoCollectedSlots() {
		FieldMappingProcessor processor = new FieldMappingProcessor();
		List<DependencyResolver.FieldMapping> mappings = List.of(
				new DependencyResolver.FieldMapping("current_user", "employeeId", "employeeId"),
				new DependencyResolver.FieldMapping("current_user", "name", "employeeName"));
		Map<String, Map<String, Object>> results = Map.of(
				"current_user", Map.of("employeeId", "E1001", "name", "Alice"));

		Map<String, SlotValue> merged = processor.applyMappings(mappings, results, new LinkedHashMap<>());

		assertEquals(2, merged.size());
		assertEquals("E1001", merged.get("employeeId").getResolvedValue());
		assertEquals("Alice", merged.get("employeeName").getResolvedValue());
		assertEquals(SlotValue.Source.INFERRED, merged.get("employeeName").getSource());
	}

	@Test
	void shouldNotOverrideExistingCollectedValue() {
		FieldMappingProcessor processor = new FieldMappingProcessor();
		List<DependencyResolver.FieldMapping> mappings = List.of(
				new DependencyResolver.FieldMapping("current_user", "employeeId", "employeeId"));
		Map<String, Map<String, Object>> results = Map.of(
				"current_user", Map.of("employeeId", "E1001"));

		Map<String, SlotValue> collected = new LinkedHashMap<>();
		collected.put("employeeId", SlotValue.fromUser("employeeId", "E2002"));

		Map<String, SlotValue> merged = processor.applyMappings(mappings, results, collected);

		assertEquals("E2002", merged.get("employeeId").getResolvedValue());
		assertEquals(SlotValue.Source.USER, merged.get("employeeId").getSource());
	}

	@Test
	void shouldMatchSourceFieldCaseInsensitively() {
		FieldMappingProcessor processor = new FieldMappingProcessor();
		List<DependencyResolver.FieldMapping> mappings = List.of(
				new DependencyResolver.FieldMapping("current_user", "employeeId", "employeeId"));
		Map<String, Map<String, Object>> results = Map.of(
				"current_user", Map.of("EmployeeId", "E1001"));

		Map<String, SlotValue> merged = processor.applyMappings(mappings, results, new LinkedHashMap<>());

		assertTrue(merged.containsKey("employeeId"));
		assertEquals("E1001", merged.get("employeeId").getResolvedValue());
	}

}
