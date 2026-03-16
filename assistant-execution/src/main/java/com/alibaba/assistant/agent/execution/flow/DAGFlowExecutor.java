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

import com.alibaba.assistant.agent.execution.model.JoinType;
import com.alibaba.assistant.agent.execution.model.StepConfig;
import com.alibaba.assistant.agent.execution.model.StepDefinition;
import com.alibaba.assistant.agent.execution.model.StepResult;
import com.alibaba.assistant.agent.execution.model.StepStatus;
import com.alibaba.assistant.agent.execution.model.StepType;
import com.alibaba.assistant.agent.execution.step.HttpStepExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * DAG flow executor with dependency-aware sequential execution semantics.
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
		Map<String, StepStatus> stepStatuses = new LinkedHashMap<>(context.getRestoredStepStatuses());
		Map<String, StepResult> stepResults = buildRestoredStepResults(context, stepStatuses);

		try {
			List<String> candidates = orderedCandidates(flow);
			Map<String, List<String>> nextPredecessors = nextPredecessorIndex(flow.getSteps());
			Set<String> remaining = new LinkedHashSet<>();
			for (String candidate : candidates) {
				StepStatus restored = stepStatuses.get(candidate);
				if (restored == StepStatus.COMPLETED || restored == StepStatus.SKIPPED || restored == StepStatus.CANCELLED) {
					continue;
				}
				remaining.add(candidate);
			}
			while (!remaining.isEmpty()) {
				boolean progressed = false;
				for (String stepId : candidates) {
					if (!remaining.contains(stepId)) {
						continue;
					}
					StepDefinition step = flow.getSteps().get(stepId);
					if (step == null) {
						logger.error("DAGFlowExecutor#execute - step not found, stepId={}", stepId);
						remaining.remove(stepId);
						progressed = true;
						continue;
					}
					if (shouldSkipStep(stepId, step, stepStatuses, nextPredecessors)) {
						StepResult skipped = StepResult.success(Map.of("skipped", true));
						stepStatuses.put(stepId, StepStatus.SKIPPED);
						stepResults.put(stepId, skipped);
						notifyStepSkipped(step, skipped, context);
						remaining.remove(stepId);
						progressed = true;
						continue;
					}
					if (!isStepReady(stepId, step, stepStatuses, nextPredecessors)) {
						continue;
					}
					progressed = true;
					remaining.remove(stepId);
					context.setCurrentStepId(stepId);

					if (step.getType() != StepType.CONDITION && !shouldExecute(step, context)) {
						StepResult skipped = StepResult.success(Map.of("skipped", true));
						stepStatuses.put(stepId, StepStatus.SKIPPED);
						stepResults.put(stepId, skipped);
						notifyStepSkipped(step, skipped, context);
						context.setCurrentStepId(null);
						continue;
					}

					if (requiresApproval(step) && !context.isStepApproved(stepId)) {
						stepStatuses.put(stepId, StepStatus.WAITING_APPROVAL);
						StepResult waiting = new StepResult();
						waiting.setSuccess(false);
						waiting.setOutputs(Map.of("waitingApproval", true));
						stepResults.put(stepId, waiting);
						notifyStepWaitingApproval(step, context);
						context.setCurrentStepId(null);
						return waitingResult(stepStatuses, stepResults, stepId, startTime);
					}

					logger.info("DAGFlowExecutor#execute - executing step, stepId={}, name={}", stepId, step.getName());
					stepStatuses.put(stepId, StepStatus.RUNNING);
					notifyStepStarted(step, context);

					StepResult result = executeStep(step, context);
					stepResults.put(stepId, result);
					if (result.isSuccess()) {
						stepStatuses.put(stepId, StepStatus.COMPLETED);
						context.putStepOutput(stepId, result.getOutputs());
						notifyStepCompleted(step, result, context);
						logger.info("DAGFlowExecutor#execute - step completed, stepId={}", stepId);
					}
					else {
						stepStatuses.put(stepId, StepStatus.FAILED);
						notifyStepFailed(step, result, context);
						logger.error("DAGFlowExecutor#execute - step failed, stepId={}, error={}",
								stepId, result.getErrorMessage());
						context.setCurrentStepId(null);
						return failedResult(stepStatuses, stepResults, result.getErrorMessage(), startTime);
					}
					context.setCurrentStepId(null);
				}

				if (!progressed) {
					return failedResult(
							stepStatuses,
							stepResults,
							"Flow execution stalled due to unsatisfied dependencies or cyclic graph",
							startTime);
				}
			}

			FlowExecutionResult successResult = new FlowExecutionResult();
			successResult.setSuccess(true);
			successResult.setLifecycleStatus("COMPLETED");
			successResult.setStepResults(stepResults);
			successResult.setStepStatuses(stepStatuses);
			successResult.setFinalOutputs(resolveFinalOutputs(stepStatuses, stepResults));
			successResult.setDurationMs(System.currentTimeMillis() - startTime);
			return successResult;
		}
		catch (Exception e) {
			logger.error("DAGFlowExecutor#execute - flow execution error", e);
			return failedResult(stepStatuses, stepResults, "Flow execution error: " + e.getMessage(), startTime);
		}
	}

	private Map<String, StepResult> buildRestoredStepResults(FlowContext context, Map<String, StepStatus> stepStatuses) {
		Map<String, StepResult> restored = new LinkedHashMap<>();
		Map<String, Map<String, Object>> stepOutputs = context.getStepOutputsSnapshot();
		for (Map.Entry<String, StepStatus> entry : stepStatuses.entrySet()) {
			StepStatus status = entry.getValue();
			if (status == StepStatus.COMPLETED || status == StepStatus.SKIPPED) {
				restored.put(entry.getKey(), StepResult.success(stepOutputs.getOrDefault(entry.getKey(), Map.of())));
			}
		}
		return restored;
	}

	private List<String> orderedCandidates(FlowDefinition flow) {
		if (flow == null || flow.getSteps() == null || flow.getSteps().isEmpty()) {
			return List.of();
		}
		Map<String, StepDefinition> steps = flow.getSteps();
		LinkedHashSet<String> reachable = reachableStepIds(flow, steps);
		LinkedHashSet<String> ordered = new LinkedHashSet<>();
		if (flow.getEntry() != null) {
			for (String stepId : flow.getEntry()) {
				if (reachable.contains(stepId)) {
					ordered.add(stepId);
				}
			}
		}
		for (String stepId : steps.keySet()) {
			if (reachable.contains(stepId)) {
				ordered.add(stepId);
			}
		}
		return new ArrayList<>(ordered);
	}

	private LinkedHashSet<String> reachableStepIds(FlowDefinition flow, Map<String, StepDefinition> steps) {
		if (flow.getEntry() == null || flow.getEntry().isEmpty()) {
			return new LinkedHashSet<>(steps.keySet());
		}
		LinkedHashSet<String> forwardReachable = forwardReachableStepIds(flow, steps);
		Map<String, List<String>> nextPredecessors = nextPredecessorIndex(steps);
		return includeDependencies(forwardReachable, steps, nextPredecessors);
	}

	private LinkedHashSet<String> forwardReachableStepIds(FlowDefinition flow, Map<String, StepDefinition> steps) {
		Map<String, List<String>> dependents = dependentIndex(steps);
		LinkedHashSet<String> reachable = new LinkedHashSet<>();
		List<String> frontier = new ArrayList<>(flow.getEntry());
		for (int index = 0; index < frontier.size(); index++) {
			String stepId = frontier.get(index);
			if (!steps.containsKey(stepId) || !reachable.add(stepId)) {
				continue;
			}
			StepDefinition step = steps.get(stepId);
			if (step.getNext() != null) {
				for (String nextStep : step.getNext()) {
					if (steps.containsKey(nextStep)) {
						frontier.add(nextStep);
					}
				}
			}
			for (String dependent : dependents.getOrDefault(stepId, List.of())) {
				frontier.add(dependent);
			}
		}
		return reachable;
	}

	private LinkedHashSet<String> includeDependencies(
			Set<String> seeds,
			Map<String, StepDefinition> steps,
			Map<String, List<String>> nextPredecessors) {
		LinkedHashSet<String> reachable = new LinkedHashSet<>();
		List<String> frontier = new ArrayList<>(seeds);
		for (int index = 0; index < frontier.size(); index++) {
			String stepId = frontier.get(index);
			if (!steps.containsKey(stepId) || !reachable.add(stepId)) {
				continue;
			}
			StepDefinition step = steps.get(stepId);
			for (String dependency : mergedDependencyIds(stepId, step, nextPredecessors)) {
				if (steps.containsKey(dependency)) {
					frontier.add(dependency);
				}
			}
		}
		return reachable;
	}

	private Map<String, List<String>> dependentIndex(Map<String, StepDefinition> steps) {
		Map<String, List<String>> dependents = new LinkedHashMap<>();
		for (String stepId : steps.keySet()) {
			dependents.put(stepId, new ArrayList<>());
		}
		for (Map.Entry<String, StepDefinition> entry : steps.entrySet()) {
			StepDefinition step = entry.getValue();
			if (step == null || step.getDependsOn() == null) {
				continue;
			}
			for (String dependency : step.getDependsOn()) {
				if (steps.containsKey(dependency)) {
					dependents.computeIfAbsent(dependency, ignored -> new ArrayList<>()).add(entry.getKey());
				}
			}
		}
		return dependents;
	}

	private Map<String, List<String>> nextPredecessorIndex(Map<String, StepDefinition> steps) {
		Map<String, List<String>> predecessors = new LinkedHashMap<>();
		if (steps == null || steps.isEmpty()) {
			return predecessors;
		}
		for (String stepId : steps.keySet()) {
			predecessors.put(stepId, new ArrayList<>());
		}
		for (Map.Entry<String, StepDefinition> entry : steps.entrySet()) {
			StepDefinition step = entry.getValue();
			if (step == null || step.getNext() == null) {
				continue;
			}
			for (String nextStepId : step.getNext()) {
				if (steps.containsKey(nextStepId)) {
					predecessors.computeIfAbsent(nextStepId, ignored -> new ArrayList<>()).add(entry.getKey());
				}
			}
		}
		return predecessors;
	}

	private boolean isStepReady(
			String stepId,
			StepDefinition step,
			Map<String, StepStatus> stepStatuses,
			Map<String, List<String>> nextPredecessors) {
		List<String> dependsOn = mergedDependencyIds(stepId, step, nextPredecessors);
		if (dependsOn.isEmpty()) {
			return true;
		}
		JoinType joinType = step.getJoinType() != null ? step.getJoinType() : JoinType.ALL;
		if (joinType == JoinType.ANY) {
			boolean anySatisfied = false;
			for (String dependencyId : dependsOn) {
				StepStatus status = stepStatuses.get(dependencyId);
				if (isSatisfiedDependencyStatus(status)) {
					anySatisfied = true;
				}
				if (!isTerminalDependencyStatus(status)) {
					return false;
				}
			}
			return anySatisfied;
		}
		return dependsOn.stream().map(stepStatuses::get).allMatch(this::isSatisfiedDependencyStatus);
	}

	private List<String> mergedDependencyIds(
			String stepId,
			StepDefinition step,
			Map<String, List<String>> nextPredecessors) {
		LinkedHashSet<String> dependencyIds = new LinkedHashSet<>();
		if (step.getDependsOn() != null) {
			dependencyIds.addAll(step.getDependsOn());
		}
		if (nextPredecessors != null && nextPredecessors.get(stepId) != null) {
			dependencyIds.addAll(nextPredecessors.get(stepId));
		}
		return new ArrayList<>(dependencyIds);
	}

	private boolean shouldSkipStep(
			String stepId,
			StepDefinition step,
			Map<String, StepStatus> stepStatuses,
			Map<String, List<String>> nextPredecessors) {
		List<String> dependencyIds = mergedDependencyIds(stepId, step, nextPredecessors);
		if (dependencyIds.isEmpty()) {
			return false;
		}
		for (String dependencyId : dependencyIds) {
			StepStatus status = stepStatuses.get(dependencyId);
			if (status == null || status == StepStatus.COMPLETED) {
				return false;
			}
			if (status != StepStatus.SKIPPED && status != StepStatus.CANCELLED) {
				return false;
			}
		}
		return true;
	}

	private boolean isSatisfiedDependencyStatus(StepStatus status) {
		return status == StepStatus.COMPLETED || status == StepStatus.SKIPPED;
	}

	private boolean isTerminalDependencyStatus(StepStatus status) {
		return status == StepStatus.COMPLETED
				|| status == StepStatus.SKIPPED
				|| status == StepStatus.CANCELLED
				|| status == StepStatus.FAILED;
	}

	private boolean shouldExecute(StepDefinition step, FlowContext context) {
		StepConfig config = step.getConfig();
		if (config == null || config.getConditions() == null) {
			return true;
		}
		Object conditions = config.getConditions();
		if (conditions instanceof Map<?, ?> map) {
			Object enabled = map.get("enabled");
			if (enabled != null && !parseBoolean(resolveConditionOperand(enabled, context), true)) {
				return true;
			}
			Object expression = firstNonNull(map.get("expression"), map.get("result"), map.get("value"), map.get("when"));
			if (expression == null && map.containsKey("operator")) {
				expression = map;
			}
			return expression == null || evaluateConditionExpression(expression, context);
		}
		return evaluateConditionExpression(conditions, context);
	}

	private boolean evaluateConditionExpression(Object expression, FlowContext context) {
		if (expression instanceof Map<?, ?> map) {
			Object nested = firstNonNull(map.get("expression"), map.get("result"), map.get("value"), map.get("when"));
			Object operator = firstNonNull(map.get("operator"), map.get("op"), map.get("matcher"));
			if (operator == null && nested != null && nested != expression) {
				return evaluateConditionExpression(nested, context);
			}
			return evaluateStructuredCondition(map, context, operator);
		}
		return parseBoolean(resolveConditionOperand(expression, context), true);
	}

	private boolean evaluateStructuredCondition(Map<?, ?> conditionMap, FlowContext context, Object operator) {
		String normalizedOperator = operator != null
				? String.valueOf(operator).trim().toLowerCase(Locale.ROOT)
				: "exists";
		Object left = resolveConditionOperand(
				firstNonNull(conditionMap.get("left"), conditionMap.get("source"), conditionMap.get("actual"), conditionMap.get("value")),
				context);
		Object right = resolveConditionOperand(
				firstNonNull(conditionMap.get("right"), conditionMap.get("expected"), conditionMap.get("equals")),
				context);
		return switch (normalizedOperator) {
			case "exists" -> left != null && (!(left instanceof String text) || !text.isBlank());
			case "missing", "not_exists", "not-exists" -> left == null || (left instanceof String text && text.isBlank());
			case "eq", "equals", "==" -> conditionEquals(left, right);
			case "neq", "not_equals", "not-equals", "!=" -> !conditionEquals(left, right);
			case "gt", ">" -> compareConditionValues(left, right) > 0;
			case "gte", ">=" -> compareConditionValues(left, right) >= 0;
			case "lt", "<" -> compareConditionValues(left, right) < 0;
			case "lte", "<=" -> compareConditionValues(left, right) <= 0;
			default -> parseBoolean(resolveConditionOperand(firstNonNull(conditionMap.get("value"), conditionMap.get("left")), context), true);
		};
	}

	private Object resolveConditionOperand(Object operand, FlowContext context) {
		if (operand instanceof String text && context != null) {
			return context.resolve(text);
		}
		return operand;
	}

	private boolean conditionEquals(Object left, Object right) {
		if (left == null || right == null) {
			return left == right;
		}
		BigDecimal leftDecimal = toBigDecimal(left);
		BigDecimal rightDecimal = toBigDecimal(right);
		if (leftDecimal != null && rightDecimal != null) {
			return leftDecimal.compareTo(rightDecimal) == 0;
		}
		if (isBooleanLike(left) && isBooleanLike(right)) {
			return parseBoolean(left, false) == parseBoolean(right, false);
		}
		return String.valueOf(left).equals(String.valueOf(right));
	}

	private int compareConditionValues(Object left, Object right) {
		BigDecimal leftDecimal = toBigDecimal(left);
		BigDecimal rightDecimal = toBigDecimal(right);
		if (leftDecimal != null && rightDecimal != null) {
			return leftDecimal.compareTo(rightDecimal);
		}
		String leftText = left != null ? String.valueOf(left) : "";
		String rightText = right != null ? String.valueOf(right) : "";
		return leftText.compareTo(rightText);
	}

	private BigDecimal toBigDecimal(Object candidate) {
		if (candidate == null) {
			return null;
		}
		try {
			return new BigDecimal(String.valueOf(candidate).trim());
		}
		catch (NumberFormatException ignored) {
			return null;
		}
	}

	private boolean isBooleanLike(Object candidate) {
		if (candidate instanceof Boolean) {
			return true;
		}
		if (candidate == null) {
			return false;
		}
		String normalized = String.valueOf(candidate).trim().toLowerCase(Locale.ROOT);
		return normalized.equals("1")
				|| normalized.equals("0")
				|| normalized.equals("true")
				|| normalized.equals("false")
				|| normalized.equals("yes")
				|| normalized.equals("no")
				|| normalized.equals("on")
				|| normalized.equals("off")
				|| normalized.equals("y")
				|| normalized.equals("n");
	}

	private boolean requiresApproval(StepDefinition step) {
		StepConfig config = step.getConfig();
		if (config == null || config.getApprovalGate() == null) {
			return false;
		}
		Object approvalGate = config.getApprovalGate();
		if (approvalGate instanceof Boolean bool) {
			return bool;
		}
		if (approvalGate instanceof Number number) {
			return number.intValue() != 0;
		}
		if (approvalGate instanceof String text) {
			return parseBoolean(text, true);
		}
		if (approvalGate instanceof Map<?, ?> map) {
			Object enabled = firstNonNull(map.get("enabled"), map.get("required"));
			return enabled == null || parseBoolean(enabled, true);
		}
		return true;
	}

	private boolean parseBoolean(Object candidate, boolean defaultValue) {
		if (candidate == null) {
			return defaultValue;
		}
		if (candidate instanceof Boolean bool) {
			return bool;
		}
		if (candidate instanceof Number number) {
			return number.intValue() != 0;
		}
		String normalized = String.valueOf(candidate).trim().toLowerCase(Locale.ROOT);
		return switch (normalized) {
			case "1", "true", "yes", "y", "on" -> true;
			case "0", "false", "no", "n", "off" -> false;
			default -> defaultValue;
		};
	}

	private Object firstNonNull(Object... values) {
		if (values == null) {
			return null;
		}
		for (Object value : values) {
			if (value != null) {
				return value;
			}
		}
		return null;
	}

	private StepResult executeStep(StepDefinition step, FlowContext context) {
		if (step.getType() == StepType.CONDITION) {
			return executeConditionStep(step, context);
		}
		if (step.getType() == StepType.HTTP) {
			return httpStepExecutor.execute(step.getConfig(), context);
		}
		return StepResult.failure("Unsupported step type: " + step.getType());
	}

	private StepResult executeConditionStep(StepDefinition step, FlowContext context) {
		StepConfig config = step.getConfig();
		if (config == null || config.getConditions() == null) {
			return StepResult.success(Map.of("result", true));
		}
		Object conditions = config.getConditions();
		if (conditions instanceof Map<?, ?> map) {
			Object enabled = map.get("enabled");
			if (enabled != null && !parseBoolean(resolveConditionOperand(enabled, context), true)) {
				return StepResult.success(Map.of("result", true));
			}
			Object expression = firstNonNull(map.get("expression"), map.get("result"), map.get("value"), map.get("when"));
			if (expression == null && map.containsKey("operator")) {
				expression = map;
			}
			return StepResult.success(Map.of("result", expression == null || evaluateConditionExpression(expression, context)));
		}
		return StepResult.success(Map.of("result", evaluateConditionExpression(conditions, context)));
	}

	private Map<String, Object> resolveFinalOutputs(
			Map<String, StepStatus> stepStatuses,
			Map<String, StepResult> stepResults) {
		if (stepResults == null || stepResults.isEmpty()) {
			return Map.of();
		}
		Map<String, Object> finalOutputs = new LinkedHashMap<>();
		for (Map.Entry<String, StepResult> entry : stepResults.entrySet()) {
			if (entry == null || entry.getValue() == null) {
				continue;
			}
			if (stepStatuses == null || stepStatuses.get(entry.getKey()) != StepStatus.COMPLETED) {
				continue;
			}
			Map<String, Object> outputs = entry.getValue().getOutputs();
			if (outputs == null || outputs.isEmpty()) {
				continue;
			}
			finalOutputs.putAll(outputs);
		}
		return finalOutputs.isEmpty() ? Map.of() : finalOutputs;
	}

	private FlowExecutionResult waitingResult(
			Map<String, StepStatus> stepStatuses,
			Map<String, StepResult> stepResults,
			String pausedStepId,
			long startTime) {
		FlowExecutionResult waitingResult = new FlowExecutionResult();
		waitingResult.setSuccess(false);
		waitingResult.setLifecycleStatus("WAITING_APPROVAL");
		waitingResult.setPausedStepId(pausedStepId);
		waitingResult.setStepResults(stepResults);
		waitingResult.setStepStatuses(stepStatuses);
		waitingResult.setFinalOutputs(resolveFinalOutputs(stepStatuses, stepResults));
		waitingResult.setDurationMs(System.currentTimeMillis() - startTime);
		return waitingResult;
	}

	private FlowExecutionResult failedResult(
			Map<String, StepStatus> stepStatuses,
			Map<String, StepResult> stepResults,
			String errorMessage,
			long startTime) {
		FlowExecutionResult errorResult = new FlowExecutionResult();
		errorResult.setSuccess(false);
		errorResult.setLifecycleStatus("FAILED");
		errorResult.setStepResults(stepResults);
		errorResult.setStepStatuses(stepStatuses);
		errorResult.setFinalOutputs(resolveFinalOutputs(stepStatuses, stepResults));
		errorResult.setErrorMessage(errorMessage);
		errorResult.setDurationMs(System.currentTimeMillis() - startTime);
		return errorResult;
	}

	private void notifyStepStarted(StepDefinition step, FlowContext context) {
		for (FlowExecutionListener listener : context.getExecutionListeners()) {
			listener.onStepStarted(step, context);
		}
	}

	private void notifyStepCompleted(StepDefinition step, StepResult result, FlowContext context) {
		for (FlowExecutionListener listener : context.getExecutionListeners()) {
			listener.onStepCompleted(step, result, context);
		}
	}

	private void notifyStepFailed(StepDefinition step, StepResult result, FlowContext context) {
		for (FlowExecutionListener listener : context.getExecutionListeners()) {
			listener.onStepFailed(step, result, context);
		}
	}

	private void notifyStepSkipped(StepDefinition step, StepResult result, FlowContext context) {
		for (FlowExecutionListener listener : context.getExecutionListeners()) {
			listener.onStepSkipped(step, result, context);
		}
	}

	private void notifyStepWaitingApproval(StepDefinition step, FlowContext context) {
		for (FlowExecutionListener listener : context.getExecutionListeners()) {
			listener.onStepWaitingApproval(step, context);
		}
	}

}
