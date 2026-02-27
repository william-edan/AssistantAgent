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
import java.util.Map;

/**
 * Result of a single step execution.
 *
 * @author Assistant Agent Team
 * @since 1.0.0
 */
public class StepResult {

	private boolean success;

	private Map<String, Object> outputs;

	private String errorMessage;

	private List<String> nextSteps;

	public StepResult() {
	}

	public static StepResult success(Map<String, Object> outputs) {
		StepResult result = new StepResult();
		result.setSuccess(true);
		result.setOutputs(outputs);
		return result;
	}

	public static StepResult failure(String errorMessage) {
		StepResult result = new StepResult();
		result.setSuccess(false);
		result.setErrorMessage(errorMessage);
		return result;
	}

	public boolean isSuccess() {
		return success;
	}

	public void setSuccess(boolean success) {
		this.success = success;
	}

	public Map<String, Object> getOutputs() {
		return outputs;
	}

	public void setOutputs(Map<String, Object> outputs) {
		this.outputs = outputs;
	}

	public String getErrorMessage() {
		return errorMessage;
	}

	public void setErrorMessage(String errorMessage) {
		this.errorMessage = errorMessage;
	}

	public List<String> getNextSteps() {
		return nextSteps;
	}

	public void setNextSteps(List<String> nextSteps) {
		this.nextSteps = nextSteps;
	}

}
