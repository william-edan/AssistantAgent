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
package com.alibaba.assistant.agent.execution.model;

import java.util.List;

/**
 * Definition of a single step in a DAG flow.
 *
 * @author Assistant Agent Team
 * @since 1.0.0
 */
public class StepDefinition {

	private String stepId;

	private String name;

	private StepType type;

	private List<String> dependsOn;

	private List<String> next;

	private JoinType joinType = JoinType.ALL;

	private StepConfig config;

	public StepDefinition() {
	}

	public String getStepId() {
		return stepId;
	}

	public void setStepId(String stepId) {
		this.stepId = stepId;
	}

	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}

	public StepType getType() {
		return type;
	}

	public void setType(StepType type) {
		this.type = type;
	}

	public List<String> getDependsOn() {
		return dependsOn;
	}

	public void setDependsOn(List<String> dependsOn) {
		this.dependsOn = dependsOn;
	}

	public List<String> getNext() {
		return next;
	}

	public void setNext(List<String> next) {
		this.next = next;
	}

	public JoinType getJoinType() {
		return joinType;
	}

	public void setJoinType(JoinType joinType) {
		this.joinType = joinType;
	}

	public StepConfig getConfig() {
		return config;
	}

	public void setConfig(StepConfig config) {
		this.config = config;
	}

}
