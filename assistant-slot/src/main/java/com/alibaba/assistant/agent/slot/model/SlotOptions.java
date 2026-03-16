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

import java.util.List;
import java.util.Map;

/**
 * Slot options configuration for enumerable slots.
 *
 * @author Assistant Agent Team
 * @since 1.0.0
 */
public class SlotOptions {

	public enum SourceType {
		/** Static values defined in schema */
		STATIC,
		/** Enum mapping (key-value pairs) */
		ENUM,
		/** Dynamic values from reusable tools */
		TOOL,
		/** Dynamic values from API */
		API,
		/** Search-based values */
		SEARCH,
		/** API with search capability */
		API_SEARCH
	}

	private SourceType source;

	private List<OptionValue> values;

	private Map<String, Object> enumMapping;

	private ApiConfig apiConfig;

	private ToolOptionResolverConfig toolConfig;

	private AutoSelectConfig autoSelect;

	private boolean multiSelect;

	private String separator;

	/**
	 * Single option value (supports tree structure for cascading select).
	 */
	public static class OptionValue {

		private Object value;

		private String label;

		private String description;

		private Map<String, Object> extras;

		private List<OptionValue> children;

		public OptionValue() {
		}

		public OptionValue(Object value, String label) {
			this.value = value;
			this.label = label;
		}

		public Object getValue() {
			return value;
		}

		public void setValue(Object value) {
			this.value = value;
		}

		public String getLabel() {
			return label;
		}

		public void setLabel(String label) {
			this.label = label;
		}

		public String getDescription() {
			return description;
		}

		public void setDescription(String description) {
			this.description = description;
		}

		public Map<String, Object> getExtras() {
			return extras;
		}

		public void setExtras(Map<String, Object> extras) {
			this.extras = extras;
		}

		public List<OptionValue> getChildren() {
			return children;
		}

		public void setChildren(List<OptionValue> children) {
			this.children = children;
		}

		public Object getExtra(String key) {
			return extras != null ? extras.get(key) : null;
		}

		public boolean isLeaf() {
			return children == null || children.isEmpty();
		}

	}

	/**
	 * Auto-select configuration for API-based options.
	 */
	public static class AutoSelectConfig {

		private boolean enabled;

		private String strategy;

		private String when;

		public boolean isEnabled() {
			return enabled;
		}

		public void setEnabled(boolean enabled) {
			this.enabled = enabled;
		}

		public String getStrategy() {
			return strategy;
		}

		public void setStrategy(String strategy) {
			this.strategy = strategy;
		}

		public String getWhen() {
			return when;
		}

		public void setWhen(String when) {
			this.when = when;
		}

	}

	/**
	 * API configuration for dynamic options (supports tree structure).
	 */
	public static class ApiConfig {

		private String endpoint;

		private String method;

		private Map<String, String> params;

		private String valueField;

		private String labelField;

		private String description;

		private List<String> extraFields;

		private String childrenField;

		private boolean treeStructure;

		public String getEndpoint() {
			return endpoint;
		}

		public void setEndpoint(String endpoint) {
			this.endpoint = endpoint;
		}

		public String getMethod() {
			return method;
		}

		public void setMethod(String method) {
			this.method = method;
		}

		public Map<String, String> getParams() {
			return params;
		}

		public void setParams(Map<String, String> params) {
			this.params = params;
		}

		public String getValueField() {
			return valueField;
		}

		public void setValueField(String valueField) {
			this.valueField = valueField;
		}

		public String getLabelField() {
			return labelField;
		}

		public void setLabelField(String labelField) {
			this.labelField = labelField;
		}

		public String getDescription() {
			return description;
		}

		public void setDescription(String description) {
			this.description = description;
		}

		public List<String> getExtraFields() {
			return extraFields;
		}

		public void setExtraFields(List<String> extraFields) {
			this.extraFields = extraFields;
		}

		public String getChildrenField() {
			return childrenField;
		}

		public void setChildrenField(String childrenField) {
			this.childrenField = childrenField;
		}

		public boolean isTreeStructure() {
			return treeStructure;
		}

		public void setTreeStructure(boolean treeStructure) {
			this.treeStructure = treeStructure;
		}

	}

	// Getters and Setters

	public SourceType getSource() {
		return source;
	}

	public void setSource(SourceType source) {
		this.source = source;
	}

	public List<OptionValue> getValues() {
		return values;
	}

	public void setValues(List<OptionValue> values) {
		this.values = values;
	}

	public ApiConfig getApiConfig() {
		return apiConfig;
	}

	public void setApiConfig(ApiConfig apiConfig) {
		this.apiConfig = apiConfig;
	}

	public ToolOptionResolverConfig getToolConfig() {
		return toolConfig;
	}

	public void setToolConfig(ToolOptionResolverConfig toolConfig) {
		this.toolConfig = toolConfig;
	}

	public AutoSelectConfig getAutoSelect() {
		return autoSelect;
	}

	public void setAutoSelect(AutoSelectConfig autoSelect) {
		this.autoSelect = autoSelect;
	}

	public boolean isMultiSelect() {
		return multiSelect;
	}

	public void setMultiSelect(boolean multiSelect) {
		this.multiSelect = multiSelect;
	}

	public Map<String, Object> getEnumMapping() {
		return enumMapping;
	}

	public void setEnumMapping(Map<String, Object> enumMapping) {
		this.enumMapping = enumMapping;
	}

	public String getSeparator() {
		return separator;
	}

	public void setSeparator(String separator) {
		this.separator = separator;
	}

}
