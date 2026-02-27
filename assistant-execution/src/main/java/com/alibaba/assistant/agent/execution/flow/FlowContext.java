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
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

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

	private String systemCode;

	private String assistantUid;

	private String threadId;

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

	public void putStepOutput(String stepId, Map<String, Object> outputs) {
		stepOutputs.put(stepId, outputs);
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
