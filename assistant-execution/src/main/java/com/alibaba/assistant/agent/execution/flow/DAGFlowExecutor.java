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

import com.alibaba.assistant.agent.execution.model.*;
import com.alibaba.assistant.agent.execution.step.HttpStepExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * DAG flow executor with linear execution semantics.
 * Supports sequential step execution, variable replacement, and failure short-circuit.
 *
 * @author Assistant Agent Team
 * @since 1.0.0
 */
@Service
public class DAGFlowExecutor {

	private static final Logger logger = LoggerFactory.getLogger(DAGFlowExecutor.class);

	private final HttpStepExecutor httpStepExecutor;

	public DAGFlowExecutor(HttpStepExecutor httpStepExecutor) {
		this.httpStepExecutor = httpStepExecutor;
	}

	/**
	 * Execute flow with initial context (slot values).
	 */
	public FlowExecutionResult execute(FlowDefinition flow, Map<String, Object> initialContext) {
		FlowContext context = new FlowContext(initialContext);
		return execute(flow, context);
	}

	/**
	 * Execute flow with pre-configured FlowContext.
	 */
	public FlowExecutionResult execute(FlowDefinition flow, FlowContext context) {
		long startTime = System.currentTimeMillis();

		Map<String, StepStatus> stepStatuses = new LinkedHashMap<>();
		Map<String, StepResult> stepResults = new LinkedHashMap<>();

		try {
			List<String> executionOrder = buildExecutionOrder(flow);

			for (String stepId : executionOrder) {
				StepDefinition step = flow.getSteps().get(stepId);
				if (step == null) {
					logger.error("DAGFlowExecutor#execute - step not found, stepId={}", stepId);
					continue;
				}

				logger.info("DAGFlowExecutor#execute - executing step, stepId={}, name={}", stepId, step.getName());
				stepStatuses.put(stepId, StepStatus.RUNNING);

				StepResult result = executeStep(step, context);
				stepResults.put(stepId, result);

				if (result.isSuccess()) {
					stepStatuses.put(stepId, StepStatus.COMPLETED);
					context.putStepOutput(stepId, result.getOutputs());
					logger.info("DAGFlowExecutor#execute - step completed, stepId={}", stepId);
				}
				else {
					stepStatuses.put(stepId, StepStatus.FAILED);
					logger.error("DAGFlowExecutor#execute - step failed, stepId={}, error={}",
							stepId, result.getErrorMessage());

					FlowExecutionResult failResult = new FlowExecutionResult();
					failResult.setSuccess(false);
					failResult.setStepResults(stepResults);
					failResult.setStepStatuses(stepStatuses);
					failResult.setErrorMessage("Step execution failed: " + result.getErrorMessage());
					failResult.setDurationMs(System.currentTimeMillis() - startTime);
					return failResult;
				}
			}

			FlowExecutionResult successResult = new FlowExecutionResult();
			successResult.setSuccess(true);
			successResult.setStepResults(stepResults);
			successResult.setStepStatuses(stepStatuses);
			successResult.setFinalOutputs(context.toMap());
			successResult.setDurationMs(System.currentTimeMillis() - startTime);
			return successResult;
		}
		catch (Exception e) {
			logger.error("DAGFlowExecutor#execute - flow execution error", e);

			FlowExecutionResult errorResult = new FlowExecutionResult();
			errorResult.setSuccess(false);
			errorResult.setStepResults(stepResults);
			errorResult.setStepStatuses(stepStatuses);
			errorResult.setErrorMessage("Flow execution error: " + e.getMessage());
			errorResult.setDurationMs(System.currentTimeMillis() - startTime);
			return errorResult;
		}
	}

	/**
	 * Build linear execution order from entry points following next references.
	 */
	private List<String> buildExecutionOrder(FlowDefinition flow) {
		List<String> order = new ArrayList<>();
		Set<String> visited = new HashSet<>();

		logger.info("DAGFlowExecutor#buildExecutionOrder - entry={}, totalSteps={}",
				flow.getEntry(), flow.getSteps().size());

		for (String entryId : flow.getEntry()) {
			buildOrderRecursive(entryId, flow, order, visited);
		}

		logger.info("DAGFlowExecutor#buildExecutionOrder - order={}", order);
		return order;
	}

	private void buildOrderRecursive(String stepId, FlowDefinition flow, List<String> order, Set<String> visited) {
		if (visited.contains(stepId)) {
			return;
		}

		visited.add(stepId);
		order.add(stepId);

		StepDefinition step = flow.getSteps().get(stepId);
		if (step == null) {
			logger.warn("DAGFlowExecutor#buildOrderRecursive - step not found, stepId={}", stepId);
			return;
		}

		if (step.getNext() != null && !step.getNext().isEmpty()) {
			for (String nextId : step.getNext()) {
				buildOrderRecursive(nextId, flow, order, visited);
			}
		}
	}

	private StepResult executeStep(StepDefinition step, FlowContext context) {
		if (step.getType() == StepType.HTTP) {
			return httpStepExecutor.execute(step.getConfig(), context);
		}

		return StepResult.failure("Unsupported step type: " + step.getType());
	}

}
