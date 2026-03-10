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

import com.alibaba.assistant.agent.execution.model.StepResult;
import com.alibaba.assistant.agent.execution.model.StepStatus;

import java.util.Map;

/**
 * Complete result of a flow execution.
 *
 * @author Assistant Agent Team
 * @since 1.0.0
 */
public class FlowExecutionResult {

	private boolean success;

	private Map<String, StepResult> stepResults;

	private Map<String, StepStatus> stepStatuses;

	private Map<String, Object> finalOutputs;

	private String errorMessage;

	private long durationMs;

	private String lifecycleStatus;

	private String pausedStepId;

	private String approvalRequestId;

	public FlowExecutionResult() {
	}

	public boolean isSuccess() {
		return success;
	}

	public void setSuccess(boolean success) {
		this.success = success;
	}

	public Map<String, StepResult> getStepResults() {
		return stepResults;
	}

	public void setStepResults(Map<String, StepResult> stepResults) {
		this.stepResults = stepResults;
	}

	public Map<String, StepStatus> getStepStatuses() {
		return stepStatuses;
	}

	public void setStepStatuses(Map<String, StepStatus> stepStatuses) {
		this.stepStatuses = stepStatuses;
	}

	public Map<String, Object> getFinalOutputs() {
		return finalOutputs;
	}

	public void setFinalOutputs(Map<String, Object> finalOutputs) {
		this.finalOutputs = finalOutputs;
	}

	public String getErrorMessage() {
		return errorMessage;
	}

	public void setErrorMessage(String errorMessage) {
		this.errorMessage = errorMessage;
	}

	public long getDurationMs() {
		return durationMs;
	}

	public void setDurationMs(long durationMs) {
		this.durationMs = durationMs;
	}

	public String getLifecycleStatus() {
		return lifecycleStatus;
	}

	public void setLifecycleStatus(String lifecycleStatus) {
		this.lifecycleStatus = lifecycleStatus;
	}

	public String getPausedStepId() {
		return pausedStepId;
	}

	public void setPausedStepId(String pausedStepId) {
		this.pausedStepId = pausedStepId;
	}

	public String getApprovalRequestId() {
		return approvalRequestId;
	}

	public void setApprovalRequestId(String approvalRequestId) {
		this.approvalRequestId = approvalRequestId;
	}

}
