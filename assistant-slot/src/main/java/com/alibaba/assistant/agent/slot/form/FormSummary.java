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
package com.alibaba.assistant.agent.slot.form;

import java.util.ArrayList;
import java.util.List;

/**
 * Form summary containing core and secondary items for confirmation display.
 *
 * @author Assistant Agent Team
 * @since 1.0.0
 */
public class FormSummary {

	private List<SummaryItem> summaryItems = new ArrayList<>();

	private List<SecondaryItem> secondaryItems = new ArrayList<>();

	public List<SummaryItem> getSummaryItems() {
		return summaryItems;
	}

	public void setSummaryItems(List<SummaryItem> summaryItems) {
		this.summaryItems = summaryItems;
	}

	public List<SecondaryItem> getSecondaryItems() {
		return secondaryItems;
	}

	public void setSecondaryItems(List<SecondaryItem> secondaryItems) {
		this.secondaryItems = secondaryItems;
	}

	/**
	 * Core summary item (e.g., leave type, date range, duration).
	 */
	public static class SummaryItem {

		private String label;

		private String value;

		private String icon;

		private int order;

		private String group;

		public static SummaryItem of(String label, String value, String icon, int order, String group) {
			SummaryItem item = new SummaryItem();
			item.setLabel(label);
			item.setValue(value);
			item.setIcon(icon);
			item.setOrder(order);
			item.setGroup(group);
			return item;
		}

		public String getLabel() {
			return label;
		}

		public void setLabel(String label) {
			this.label = label;
		}

		public String getValue() {
			return value;
		}

		public void setValue(String value) {
			this.value = value;
		}

		public String getIcon() {
			return icon;
		}

		public void setIcon(String icon) {
			this.icon = icon;
		}

		public int getOrder() {
			return order;
		}

		public void setOrder(int order) {
			this.order = order;
		}

		public String getGroup() {
			return group;
		}

		public void setGroup(String group) {
			this.group = group;
		}

	}

	/**
	 * Secondary summary item (e.g., approver, CC recipients).
	 */
	public static class SecondaryItem {

		private String label;

		private String value;

		private String icon;

		private String type;

		private String group;

		public static SecondaryItem of(String label, String value, String icon) {
			SecondaryItem item = new SecondaryItem();
			item.setLabel(label);
			item.setValue(value);
			item.setIcon(icon);
			return item;
		}

		public String getLabel() {
			return label;
		}

		public void setLabel(String label) {
			this.label = label;
		}

		public String getValue() {
			return value;
		}

		public void setValue(String value) {
			this.value = value;
		}

		public String getIcon() {
			return icon;
		}

		public void setIcon(String icon) {
			this.icon = icon;
		}

		public String getType() {
			return type;
		}

		public void setType(String type) {
			this.type = type;
		}

		public String getGroup() {
			return group;
		}

		public void setGroup(String group) {
			this.group = group;
		}

	}

}
