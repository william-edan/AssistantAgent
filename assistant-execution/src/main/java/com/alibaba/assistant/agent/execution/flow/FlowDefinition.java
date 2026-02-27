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
package com.alibaba.assistant.agent.execution.flow;

import com.alibaba.assistant.agent.execution.model.StepDefinition;

import java.util.List;
import java.util.Map;

/**
 * DAG flow definition with steps, entry points, and terminal nodes.
 *
 * @author Assistant Agent Team
 * @since 1.0.0
 */
public class FlowDefinition {

	private String version = "2.0";

	private Map<String, StepDefinition> steps;

	private List<String> entry;

	private List<String> terminal;

	public FlowDefinition() {
	}

	public String getVersion() {
		return version;
	}

	public void setVersion(String version) {
		this.version = version;
	}

	public Map<String, StepDefinition> getSteps() {
		return steps;
	}

	public void setSteps(Map<String, StepDefinition> steps) {
		this.steps = steps;
	}

	public List<String> getEntry() {
		return entry;
	}

	public void setEntry(List<String> entry) {
		this.entry = entry;
	}

	public List<String> getTerminal() {
		return terminal;
	}

	public void setTerminal(List<String> terminal) {
		this.terminal = terminal;
	}

}
