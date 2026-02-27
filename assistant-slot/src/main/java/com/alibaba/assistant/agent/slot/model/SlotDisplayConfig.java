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
 * Slot display configuration for form summary rendering.
 *
 * @author Assistant Agent Team
 * @since 1.0.0
 */
public class SlotDisplayConfig {

	private boolean showInSummary;

	private Integer summaryOrder;

	private String summaryIcon;

	private String summaryGroup;

	private String displayFormat;

	private String displaySuffix;

	private String displayPrefix;

	private boolean inlineEditable;

	private String displayLabel;

	private String displayType;

	public SlotDisplayConfig() {
		this.showInSummary = false;
		this.inlineEditable = true;
		this.summaryGroup = "CORE";
	}

	public boolean isShowInSummary() {
		return showInSummary;
	}

	public void setShowInSummary(boolean showInSummary) {
		this.showInSummary = showInSummary;
	}

	public Integer getSummaryOrder() {
		return summaryOrder;
	}

	public void setSummaryOrder(Integer summaryOrder) {
		this.summaryOrder = summaryOrder;
	}

	public String getSummaryIcon() {
		return summaryIcon;
	}

	public void setSummaryIcon(String summaryIcon) {
		this.summaryIcon = summaryIcon;
	}

	public String getSummaryGroup() {
		return summaryGroup;
	}

	public void setSummaryGroup(String summaryGroup) {
		this.summaryGroup = summaryGroup;
	}

	public String getDisplayFormat() {
		return displayFormat;
	}

	public void setDisplayFormat(String displayFormat) {
		this.displayFormat = displayFormat;
	}

	public String getDisplaySuffix() {
		return displaySuffix;
	}

	public void setDisplaySuffix(String displaySuffix) {
		this.displaySuffix = displaySuffix;
	}

	public String getDisplayPrefix() {
		return displayPrefix;
	}

	public void setDisplayPrefix(String displayPrefix) {
		this.displayPrefix = displayPrefix;
	}

	public boolean isInlineEditable() {
		return inlineEditable;
	}

	public void setInlineEditable(boolean inlineEditable) {
		this.inlineEditable = inlineEditable;
	}

	public String getDisplayLabel() {
		return displayLabel;
	}

	public void setDisplayLabel(String displayLabel) {
		this.displayLabel = displayLabel;
	}

	public String getDisplayType() {
		return displayType;
	}

	public void setDisplayType(String displayType) {
		this.displayType = displayType;
	}

	public String formatValue(Object value) {
		if (value == null) {
			return "";
		}

		String strValue = String.valueOf(value);

		if (displayFormat != null && !displayFormat.isEmpty()) {
			return displayFormat.replace("{value}", strValue);
		}

		StringBuilder sb = new StringBuilder();
		if (displayPrefix != null) {
			sb.append(displayPrefix);
		}
		sb.append(strValue);
		if (displaySuffix != null) {
			sb.append(displaySuffix);
		}

		return sb.toString();
	}

	public boolean isCoreGroup() {
		return "CORE".equalsIgnoreCase(summaryGroup);
	}

	public boolean isSecondaryGroup() {
		return "SECONDARY".equalsIgnoreCase(summaryGroup);
	}

}
