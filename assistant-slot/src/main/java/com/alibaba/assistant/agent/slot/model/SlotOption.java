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
package com.alibaba.assistant.agent.slot.model;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Slot option model for dynamic option lists.
 *
 * @author Assistant Agent Team
 * @since 1.0.0
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SlotOption {

	private String label;

	private Object value;

	private String description;

	private boolean disabled;

	public SlotOption() {
	}

	public SlotOption(String label, Object value) {
		this.label = label;
		this.value = value;
	}

	public SlotOption(String label, Object value, String description) {
		this.label = label;
		this.value = value;
		this.description = description;
	}

	public String getLabel() {
		return label;
	}

	public void setLabel(String label) {
		this.label = label;
	}

	public Object getValue() {
		return value;
	}

	public void setValue(Object value) {
		this.value = value;
	}

	public String getDescription() {
		return description;
	}

	public void setDescription(String description) {
		this.description = description;
	}

	public boolean isDisabled() {
		return disabled;
	}

	public void setDisabled(boolean disabled) {
		this.disabled = disabled;
	}

	@Override
	public String toString() {
		return "SlotOption{label='" + label + "', value=" + value +
				", description='" + description + "', disabled=" + disabled + '}';
	}

}
