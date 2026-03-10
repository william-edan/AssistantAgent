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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Runtime execution context holding initial inputs, step outputs,
 * and system metadata. Supports variable resolution using ${...} expressions.
 *
 * @author Assistant Agent Team
 * @since 1.0.0
 */
public class FlowContext {

	private static final Logger logger = LoggerFactory.getLogger(FlowContext.class);

	private final Map<String, Object> initialInputs;

	private final Map<String, Map<String, Object>> stepOutputs = new ConcurrentHashMap<>();

	private final Map<String, String> stepBaseUrls = new ConcurrentHashMap<>();

	private final Map<String, Map<String, String>> stepRequestHeaders = new ConcurrentHashMap<>();

	private final List<FlowExecutionListener> executionListeners = new CopyOnWriteArrayList<>();

	private final Set<String> approvedSteps = ConcurrentHashMap.newKeySet();

	private String systemCode;

	private String assistantUid;

	private String threadId;

	private String runId;

	private String currentStepId;

	public FlowContext(Map<String, Object> initialInputs) {
		this.initialInputs = new HashMap<>(initialInputs);
	}

	public String getSystemCode() {
		return systemCode;
	}

	public void setSystemCode(String systemCode) {
		this.systemCode = systemCode;
	}

	public String getAssistantUid() {
		return assistantUid;
	}

	public void setAssistantUid(String assistantUid) {
		this.assistantUid = assistantUid;
	}

	public String getThreadId() {
		return threadId;
	}

	public void setThreadId(String threadId) {
		this.threadId = threadId;
	}

	public String getRunId() {
		return runId;
	}

	public void setRunId(String runId) {
		this.runId = runId;
	}

	public String getCurrentStepId() {
		return currentStepId;
	}

	public void setCurrentStepId(String currentStepId) {
		this.currentStepId = currentStepId;
	}

	public void putStepOutput(String stepId, Map<String, Object> outputs) {
		stepOutputs.put(stepId, outputs);
	}

	public void putStepBaseUrl(String stepId, String baseUrl) {
		if (stepId == null || baseUrl == null) {
			return;
		}
		stepBaseUrls.put(stepId, baseUrl);
	}

	public String getStepBaseUrl(String stepId) {
		return stepId != null ? stepBaseUrls.get(stepId) : null;
	}

	public void putStepRequestHeaders(String stepId, Map<String, String> headers) {
		if (stepId == null || headers == null || headers.isEmpty()) {
			return;
		}
		stepRequestHeaders.put(stepId, new LinkedHashMap<>(headers));
	}

	public Map<String, String> getStepRequestHeaders(String stepId) {
		if (stepId == null) {
			return Map.of();
		}
		Map<String, String> headers = stepRequestHeaders.get(stepId);
		return headers != null ? Map.copyOf(headers) : Map.of();
	}

	public void addExecutionListener(FlowExecutionListener listener) {
		if (listener == null) {
			return;
		}
		executionListeners.add(listener);
	}

	public List<FlowExecutionListener> getExecutionListeners() {
		return List.copyOf(executionListeners);
	}

	public void approveStep(String stepId) {
		if (stepId == null) {
			return;
		}
		approvedSteps.add(stepId);
	}

	public boolean isStepApproved(String stepId) {
		return stepId != null && approvedSteps.contains(stepId);
	}

	/**
	 * Resolve variable expression.
	 * Formats:
	 *   ${slot_name}           -> from initialInputs
	 *   ${step_id.output_name} -> from stepOutputs
	 */
	public Object resolve(String expression) {
		if (expression == null || !expression.startsWith("${") || !expression.endsWith("}")) {
			return expression;
		}

		String varPath = expression.substring(2, expression.length() - 1);

		if (varPath.contains(".")) {
			String[] parts = varPath.split("\\.", 2);
			String stepId = parts[0];
			String outputName = parts[1];

			Map<String, Object> outputs = stepOutputs.get(stepId);
			if (outputs == null) {
				logger.warn("FlowContext#resolve - stepOutput not found, stepId={}", stepId);
				return null;
			}
			return outputs.get(outputName);
		}
		else {
			return initialInputs.get(varPath);
		}
	}

	/**
	 * Flatten all variables (initial inputs + step outputs) into a single map.
	 */
	public Map<String, Object> toMap() {
		Map<String, Object> result = new HashMap<>(initialInputs);
		for (Map.Entry<String, Map<String, Object>> entry : stepOutputs.entrySet()) {
			for (Map.Entry<String, Object> output : entry.getValue().entrySet()) {
				result.put(entry.getKey() + "." + output.getKey(), output.getValue());
			}
		}
		return result;
	}

}
