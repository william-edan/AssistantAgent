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
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Applies dependency field mappings into collected slot values.
 *
 * @author Assistant Agent Team
 * @since 1.0.0
 */
@Component
@Profile("migration")
public class FieldMappingProcessor {

	/**
	 * Merge mapped values into target collected slots.
	 *
	 * @param mappings field mappings
	 * @param dependencyResults tool result map, key=toolCode, value=map payload
	 * @param collectedSlots current collected slots
	 * @return merged slot map
	 */
	public Map<String, SlotValue> applyMappings(
			List<DependencyResolver.FieldMapping> mappings,
			Map<String, Map<String, Object>> dependencyResults,
			Map<String, SlotValue> collectedSlots) {
		if (mappings == null || mappings.isEmpty()) {
			return collectedSlots != null ? collectedSlots : new LinkedHashMap<>();
		}

		Map<String, Map<String, Object>> safeResults =
				dependencyResults != null ? dependencyResults : Collections.emptyMap();
		Map<String, SlotValue> merged = collectedSlots != null ? collectedSlots : new LinkedHashMap<>();

		for (DependencyResolver.FieldMapping mapping : mappings) {
			if (mapping == null || !StringUtils.hasText(mapping.toField())) {
				continue;
			}
			if (merged.containsKey(mapping.toField())) {
				continue;
			}
			Map<String, Object> sourcePayload = safeResults.get(mapping.fromTool());
			Object sourceValue = lookupValue(sourcePayload, mapping.fromField());
			if (sourceValue == null) {
				continue;
			}
			SlotValue slotValue = SlotValue.resolved(mapping.toField(), sourceValue, sourceValue, String.valueOf(sourceValue));
			slotValue.setSource(SlotValue.Source.INFERRED);
			merged.put(mapping.toField(), slotValue);
		}
		return merged;
	}

	private Object lookupValue(Map<String, Object> sourcePayload, String key) {
		if (sourcePayload == null || sourcePayload.isEmpty() || !StringUtils.hasText(key)) {
			return null;
		}
		if (sourcePayload.containsKey(key)) {
			return sourcePayload.get(key);
		}
		String normalized = key.toLowerCase(Locale.ROOT);
		for (Map.Entry<String, Object> entry : sourcePayload.entrySet()) {
			if (entry.getKey() != null && entry.getKey().toLowerCase(Locale.ROOT).equals(normalized)) {
				return entry.getValue();
			}
		}
		return null;
	}

}
