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

import java.util.HashMap;
import java.util.Map;

/**
 * Context for computed field calculation.
 * Provides access to all collected slot values.
 *
 * @author Assistant Agent Team
 * @since 1.0.0
 */
public class ComputationContext {

	private final Map<String, Object> slotValues;

	private final Map<String, Object> metadata;

	public ComputationContext() {
		this.slotValues = new HashMap<>();
		this.metadata = new HashMap<>();
	}

	public ComputationContext(Map<String, Object> slotValues) {
		this.slotValues = new HashMap<>(slotValues);
		this.metadata = new HashMap<>();
	}

	public Object getValue(String slotName) {
		return slotValues.get(slotName);
	}

	public void setValue(String slotName, Object value) {
		slotValues.put(slotName, value);
	}

	public Map<String, Object> getAllValues() {
		return new HashMap<>(slotValues);
	}

	public boolean hasValue(String slotName) {
		return slotValues.containsKey(slotName);
	}

	public Object getMetadata(String key) {
		return metadata.get(key);
	}

	public void setMetadata(String key, Object value) {
		metadata.put(key, value);
	}

}
