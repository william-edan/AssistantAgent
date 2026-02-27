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

import java.util.List;

/**
 * Enriched slot with dynamic options loaded from data source.
 * Used for frontend rendering and LLM prompt enhancement.
 *
 * @author Assistant Agent Team
 * @since 1.0.0
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class EnrichedSlot {

	private SlotDefinition definition;

	private List<SlotOption> options;

	private boolean optionsLoaded;

	private String optionsError;

	public EnrichedSlot() {
	}

	public EnrichedSlot(SlotDefinition definition) {
		this.definition = definition;
		this.optionsLoaded = false;
	}

	public SlotDefinition getDefinition() {
		return definition;
	}

	public void setDefinition(SlotDefinition definition) {
		this.definition = definition;
	}

	public List<SlotOption> getOptions() {
		return options;
	}

	public void setOptions(List<SlotOption> options) {
		this.options = options;
		this.optionsLoaded = (options != null && !options.isEmpty());
	}

	public boolean isOptionsLoaded() {
		return optionsLoaded;
	}

	public void setOptionsLoaded(boolean optionsLoaded) {
		this.optionsLoaded = optionsLoaded;
	}

	public String getOptionsError() {
		return optionsError;
	}

	public void setOptionsError(String optionsError) {
		this.optionsError = optionsError;
	}

	public String getName() {
		return definition != null ? definition.getName() : null;
	}

	/**
	 * Get formatted options for LLM prompt.
	 * Example: "1=事假, 2=年假, 3=调休假, 4=病假"
	 */
	public String getOptionsForPrompt() {
		if (options == null || options.isEmpty()) {
			return null;
		}

		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < options.size(); i++) {
			SlotOption option = options.get(i);
			if (i > 0) {
				sb.append(", ");
			}
			sb.append(option.getValue()).append("=").append(option.getLabel());
		}
		return sb.toString();
	}

	@Override
	public String toString() {
		return "EnrichedSlot{definition=" + (definition != null ? definition.getName() : "null") +
				", optionsCount=" + (options != null ? options.size() : 0) +
				", optionsLoaded=" + optionsLoaded +
				", optionsError='" + optionsError + "'}";
	}

}
