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
 * Auto-selection rule for slot values.
 *
 * @author Assistant Agent Team
 * @since 1.0.0
 */
public class SlotAutoSelect {

	/**
	 * Auto-select strategy
	 */
	public enum Strategy {
		/** Select first option */
		FIRST,
		/** Select option with specific value */
		VALUE,
		/** Select based on condition */
		CONDITION,
		/** No auto-select, always ask user */
		NONE
	}

	private Strategy strategy;

	private String value;

	private String condition;

	private boolean confirmRequired;

	public SlotAutoSelect() {
		this.strategy = Strategy.NONE;
		this.confirmRequired = true;
	}

	public static SlotAutoSelect first() {
		SlotAutoSelect rule = new SlotAutoSelect();
		rule.setStrategy(Strategy.FIRST);
		rule.setConfirmRequired(false);
		return rule;
	}

	public static SlotAutoSelect value(String value) {
		SlotAutoSelect rule = new SlotAutoSelect();
		rule.setStrategy(Strategy.VALUE);
		rule.setValue(value);
		return rule;
	}

	public Strategy getStrategy() {
		return strategy;
	}

	public void setStrategy(Strategy strategy) {
		this.strategy = strategy;
	}

	public String getValue() {
		return value;
	}

	public void setValue(String value) {
		this.value = value;
	}

	public String getCondition() {
		return condition;
	}

	public void setCondition(String condition) {
		this.condition = condition;
	}

	public boolean isConfirmRequired() {
		return confirmRequired;
	}

	public void setConfirmRequired(boolean confirmRequired) {
		this.confirmRequired = confirmRequired;
	}

}
