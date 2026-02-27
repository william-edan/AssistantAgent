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

import java.util.Map;

/**
 * Step configuration for HTTP, condition, and other step types.
 *
 * @author Assistant Agent Team
 * @since 1.0.0
 */
public class StepConfig {

	private String method;

	private String endpoint;

	private String contentType;

	private Map<String, String> inputMapping;

	private Map<String, String> outputMapping;

	private String successCondition;

	private Object conditions;

	public StepConfig() {
	}

	public String getMethod() {
		return method;
	}

	public void setMethod(String method) {
		this.method = method;
	}

	public String getEndpoint() {
		return endpoint;
	}

	public void setEndpoint(String endpoint) {
		this.endpoint = endpoint;
	}

	public String getContentType() {
		return contentType;
	}

	public void setContentType(String contentType) {
		this.contentType = contentType;
	}

	public Map<String, String> getInputMapping() {
		return inputMapping;
	}

	public void setInputMapping(Map<String, String> inputMapping) {
		this.inputMapping = inputMapping;
	}

	public Map<String, String> getOutputMapping() {
		return outputMapping;
	}

	public void setOutputMapping(Map<String, String> outputMapping) {
		this.outputMapping = outputMapping;
	}

	public String getSuccessCondition() {
		return successCondition;
	}

	public void setSuccessCondition(String successCondition) {
		this.successCondition = successCondition;
	}

	public Object getConditions() {
		return conditions;
	}

	public void setConditions(Object conditions) {
		this.conditions = conditions;
	}

}
