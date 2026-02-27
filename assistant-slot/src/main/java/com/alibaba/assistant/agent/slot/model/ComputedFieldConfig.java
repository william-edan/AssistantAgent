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

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;

/**
 * Configuration for computed fields that are automatically calculated
 * based on other field values.
 *
 * @author Assistant Agent Team
 * @since 1.0.0
 */
public class ComputedFieldConfig {

	public enum ComputationType {
		/** Built-in function call */
		FUNCTION,
		/** Arithmetic expression */
		EXPRESSION,
		/** Script-based computation (future) */
		SCRIPT
	}

	@JsonProperty("enabled")
	private boolean enabled = false;

	@JsonProperty("type")
	private ComputationType type;

	@JsonProperty("function")
	private String function;

	@JsonProperty("params")
	private Map<String, Object> params;

	@JsonProperty("expression")
	private String expression;

	@JsonProperty("script")
	private String script;

	@JsonProperty("default_value")
	private Object defaultValue;

	public boolean isEnabled() {
		return enabled;
	}

	public void setEnabled(boolean enabled) {
		this.enabled = enabled;
	}

	public ComputationType getType() {
		return type;
	}

	public void setType(ComputationType type) {
		this.type = type;
	}

	public String getFunction() {
		return function;
	}

	public void setFunction(String function) {
		this.function = function;
	}

	public Map<String, Object> getParams() {
		return params;
	}

	public void setParams(Map<String, Object> params) {
		this.params = params;
	}

	public String getExpression() {
		return expression;
	}

	public void setExpression(String expression) {
		this.expression = expression;
	}

	public String getScript() {
		return script;
	}

	public void setScript(String script) {
		this.script = script;
	}

	public Object getDefaultValue() {
		return defaultValue;
	}

	public void setDefaultValue(Object defaultValue) {
		this.defaultValue = defaultValue;
	}

}
