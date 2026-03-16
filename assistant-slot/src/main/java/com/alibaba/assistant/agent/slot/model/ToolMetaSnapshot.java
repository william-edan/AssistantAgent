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
 * Snapshot of a tool meta record, used as the adapter model replacing
 * the old CapabilityRegistry dependency. Carries the schema data needed
 * by SlotSchemaParser without coupling to the persistence layer.
 *
 * @author Assistant Agent Team
 * @since 1.0.0
 */
public class ToolMetaSnapshot {

	private String toolCode;

	private String slotSchema;

	private String executionPlan;

	private String behaviorConfig;

	private String systemCode;

	public ToolMetaSnapshot() {
	}

	public ToolMetaSnapshot(String toolCode, String slotSchema) {
		this.toolCode = toolCode;
		this.slotSchema = slotSchema;
	}

	public String getToolCode() {
		return toolCode;
	}

	public void setToolCode(String toolCode) {
		this.toolCode = toolCode;
	}

	public String getSlotSchema() {
		return slotSchema;
	}

	public void setSlotSchema(String slotSchema) {
		this.slotSchema = slotSchema;
	}

	public String getExecutionPlan() {
		return executionPlan;
	}

	public void setExecutionPlan(String executionPlan) {
		this.executionPlan = executionPlan;
	}

	public String getBehaviorConfig() {
		return behaviorConfig;
	}

	public void setBehaviorConfig(String behaviorConfig) {
		this.behaviorConfig = behaviorConfig;
	}

	public String getSystemCode() {
		return systemCode;
	}

	public void setSystemCode(String systemCode) {
		this.systemCode = systemCode;
	}

}
