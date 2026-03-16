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

/**
 * Tool-backed option resolver metadata.
 *
 * <p>Instead of binding slot options to a raw HTTP endpoint, the slot can reference
 * a reusable query tool and describe how to read option items from the tool result.
 *
 * @author Assistant Agent Team
 * @since 1.0.0
 */
public class ToolOptionResolverConfig {

	private String toolCode;

	private String resultPath;

	private String labelField;

	private String valueField;

	private String descriptionField;

	private String disabledField;

	public String getToolCode() {
		return toolCode;
	}

	public void setToolCode(String toolCode) {
		this.toolCode = toolCode;
	}

	public String getResultPath() {
		return resultPath;
	}

	public void setResultPath(String resultPath) {
		this.resultPath = resultPath;
	}

	public String getLabelField() {
		return labelField;
	}

	public void setLabelField(String labelField) {
		this.labelField = labelField;
	}

	public String getValueField() {
		return valueField;
	}

	public void setValueField(String valueField) {
		this.valueField = valueField;
	}

	public String getDescriptionField() {
		return descriptionField;
	}

	public void setDescriptionField(String descriptionField) {
		this.descriptionField = descriptionField;
	}

	public String getDisabledField() {
		return disabledField;
	}

	public void setDisabledField(String disabledField) {
		this.disabledField = disabledField;
	}

}
