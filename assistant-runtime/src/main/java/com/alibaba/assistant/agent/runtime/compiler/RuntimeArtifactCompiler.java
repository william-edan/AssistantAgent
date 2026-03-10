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
package com.alibaba.assistant.agent.runtime.compiler;

import com.alibaba.assistant.agent.controlplane.action.ActionSpec;
import com.alibaba.assistant.agent.controlplane.interaction.InteractionSpec;
import com.alibaba.assistant.agent.controlplane.workflow.WorkflowSpec;
import com.alibaba.assistant.agent.controlplane.workflow.WorkflowStep;
import com.alibaba.assistant.agent.execution.flow.FlowDefinition;
import com.alibaba.assistant.agent.execution.model.JoinType;
import com.alibaba.assistant.agent.execution.model.StepConfig;
import com.alibaba.assistant.agent.execution.model.StepDefinition;
import com.alibaba.assistant.agent.execution.model.StepType;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Compiles new control-plane definitions into runtime-friendly artifacts.
 */
@Component
public class RuntimeArtifactCompiler {


	private final ObjectMapper objectMapper;

	public RuntimeArtifactCompiler(ObjectMapper objectMapper) {
		this.objectMapper = objectMapper;
	}

	/**
	 * Compile a workflow definition and its dependencies into a runtime artifact.
	 */
	public RuntimeArtifact compileWorkflow(
			WorkflowSpec workflowSpec,
			List<WorkflowStep> workflowSteps,
			@Nullable InteractionSpec interactionSpec,
			List<ActionSpec> actionSpecs) {
		Objects.requireNonNull(workflowSpec, "workflowSpec must not be null");

		Map<String, ActionSpec> actionsByCode = indexActions(actionSpecs);
		List<WorkflowStep> sortedSteps = sortSteps(workflowSteps);
		Map<String, WorkflowStep> stepsById = sortedSteps.stream()
				.collect(Collectors.toMap(WorkflowStep::getStepId, step -> step, (left, right) -> left, LinkedHashMap::new));
		Map<String, List<String>> dependsOnByStep = new LinkedHashMap<>();
		Map<String, List<String>> nextByStep = new LinkedHashMap<>();
		for (WorkflowStep step : sortedSteps) {
			dependsOnByStep.put(step.getStepId(), parseStringList(step.getDependsOnJson()));
			nextByStep.put(step.getStepId(), new ArrayList<>());
		}
		for (WorkflowStep step : sortedSteps) {
			for (String dependency : dependsOnByStep.get(step.getStepId())) {
				if (!stepsById.containsKey(dependency)) {
					throw new IllegalStateException("Workflow step " + step.getStepId()
							+ " depends on missing step: " + dependency);
				}
				nextByStep.computeIfAbsent(dependency, key -> new ArrayList<>()).add(step.getStepId());
			}
		}
		for (List<String> nextSteps : nextByStep.values()) {
			nextSteps.sort(stepIdComparator(stepsById));
		}

		Map<String, RuntimeArtifact.ActionBinding> runtimeActions = new LinkedHashMap<>();
		for (ActionSpec actionSpec : actionSpecs != null ? actionSpecs : List.<ActionSpec>of()) {
			if (actionSpec != null && StringUtils.hasText(actionSpec.getActionCode())) {
				runtimeActions.put(actionSpec.getActionCode(), toActionBinding(actionSpec));
			}
		}

		Map<String, RuntimeArtifact.StepBinding> runtimeSteps = new LinkedHashMap<>();
		Map<String, StepDefinition> flowSteps = new LinkedHashMap<>();
		for (WorkflowStep step : sortedSteps) {
			ActionSpec actionSpec = resolveActionSpec(step, actionsByCode);
			StepDefinition stepDefinition = toStepDefinition(step, actionSpec, dependsOnByStep.get(step.getStepId()),
					nextByStep.getOrDefault(step.getStepId(), List.of()));
			flowSteps.put(step.getStepId(), stepDefinition);
			runtimeSteps.put(step.getStepId(), toStepBinding(step, actionSpec));
		}

		FlowDefinition flowDefinition = new FlowDefinition();
		flowDefinition.setVersion("2.0");
		flowDefinition.setSteps(flowSteps);
		flowDefinition.setEntry(sortedSteps.stream()
				.filter(step -> dependsOnByStep.getOrDefault(step.getStepId(), List.of()).isEmpty())
				.map(WorkflowStep::getStepId)
				.toList());
		flowDefinition.setTerminal(sortedSteps.stream()
				.filter(step -> nextByStep.getOrDefault(step.getStepId(), List.of()).isEmpty())
				.map(WorkflowStep::getStepId)
				.toList());

		return new RuntimeArtifact(
				workflowSpec.getSpaceId(),
				workflowSpec.getWorkflowCode(),
				RuntimeArtifact.ArtifactType.WORKFLOW,
				workflowSpec.getDisplayName(),
				workflowSpec.getVersion(),
				workflowSpec.getRiskAggregationPolicy(),
				workflowSpec.getApprovalAggregationPolicy(),
				workflowSpec.getFailurePolicyJson(),
				workflowSpec.getAuditPolicyJson(),
				toInteraction(interactionSpec),
				flowDefinition,
				runtimeActions,
				runtimeSteps);
	}

	/**
	 * Compile a standalone action into a single-step runtime artifact.
	 */
	public RuntimeArtifact compileAction(ActionSpec actionSpec, @Nullable InteractionSpec interactionSpec) {
		Objects.requireNonNull(actionSpec, "actionSpec must not be null");
		RuntimeArtifact.ActionBinding actionBinding = toActionBinding(actionSpec);

		StepDefinition stepDefinition = new StepDefinition();
		stepDefinition.setStepId("execute");
		stepDefinition.setName(actionSpec.getActionCode());
		stepDefinition.setType(StepType.HTTP);
		stepDefinition.setJoinType(JoinType.ALL);
		stepDefinition.setConfig(buildStepConfig(null, actionSpec));

		FlowDefinition flowDefinition = new FlowDefinition();
		flowDefinition.setVersion("2.0");
		flowDefinition.setSteps(Map.of("execute", stepDefinition));
		flowDefinition.setEntry(List.of("execute"));
		flowDefinition.setTerminal(List.of("execute"));

		RuntimeArtifact.StepBinding stepBinding = new RuntimeArtifact.StepBinding(
				"execute",
				actionSpec.getActionCode(),
				"HTTP",
				actionSpec.getConnectorId(),
				actionSpec.getActionCode(),
				actionSpec.getAllowedAuthProfilesJson(),
				actionSpec.getBindingStrategiesJson(),
				null,
				null,
				null,
				null,
				null,
				null,
				null,
				null,
				null,
				null,
				0,
				actionBinding);

				return new RuntimeArtifact(
				actionSpec.getSpaceId(),
				actionSpec.getActionCode(),
				RuntimeArtifact.ArtifactType.ACTION,
				actionSpec.getActionCode(),
				actionSpec.getVersion(),
				null,
				null,
				null,
				null,
				toInteraction(interactionSpec),
				flowDefinition,
				Map.of(actionSpec.getActionCode(), actionBinding),
				Map.of("execute", stepBinding));
	}

	private List<WorkflowStep> sortSteps(List<WorkflowStep> workflowSteps) {
		return (workflowSteps != null ? workflowSteps : List.<WorkflowStep>of()).stream()
				.filter(Objects::nonNull)
				.sorted(stepIdComparator())
				.toList();
	}

	private Comparator<WorkflowStep> stepIdComparator() {
		return Comparator
				.comparing(RuntimeArtifactCompiler::normalizeOrder)
				.thenComparing(WorkflowStep::getStepId, Comparator.nullsLast(String::compareTo));
	}

	private Comparator<String> stepIdComparator(Map<String, WorkflowStep> stepsById) {
		return Comparator
				.comparing((String stepId) -> normalizeOrder(stepsById.get(stepId)))
				.thenComparing(stepId -> stepId, Comparator.nullsLast(String::compareTo));
	}

	private static Integer normalizeOrder(@Nullable WorkflowStep step) {
		return step != null && step.getStepOrder() != null ? step.getStepOrder() : Integer.MAX_VALUE;
	}

	private Map<String, ActionSpec> indexActions(List<ActionSpec> actionSpecs) {
		Map<String, ActionSpec> actionsByCode = new LinkedHashMap<>();
		for (ActionSpec actionSpec : actionSpecs != null ? actionSpecs : List.<ActionSpec>of()) {
			if (actionSpec != null && StringUtils.hasText(actionSpec.getActionCode())) {
				actionsByCode.put(actionSpec.getActionCode(), actionSpec);
			}
		}
		return actionsByCode;
	}

	private ActionSpec resolveActionSpec(WorkflowStep step, Map<String, ActionSpec> actionsByCode) {
		if (step == null || !requiresAction(step.getStepType())) {
			return null;
		}
		String targetRef = step.getTargetRef();
		if (!StringUtils.hasText(targetRef)) {
			throw new IllegalStateException("Workflow step " + step.getStepId() + " is missing targetRef");
		}
		ActionSpec actionSpec = actionsByCode.get(targetRef);
		if (actionSpec == null) {
			throw new IllegalStateException("Workflow step " + step.getStepId()
					+ " references missing action spec: " + targetRef);
		}
		return actionSpec;
	}

	private boolean requiresAction(String stepType) {
		if (!StringUtils.hasText(stepType)) {
			return true;
		}
		String normalized = stepType.trim().toUpperCase(Locale.ROOT);
		return "HTTP".equals(normalized) || "ACTION".equals(normalized);
	}

	private StepDefinition toStepDefinition(
			WorkflowStep step,
			@Nullable ActionSpec actionSpec,
			List<String> dependsOn,
			List<String> next) {
		StepDefinition definition = new StepDefinition();
		definition.setStepId(step.getStepId());
		definition.setName(firstNonBlank(step.getStepName(), step.getStepId()));
		definition.setType(resolveStepType(step.getStepType()));
		definition.setDependsOn(dependsOn.isEmpty() ? null : List.copyOf(dependsOn));
		definition.setNext(next.isEmpty() ? null : List.copyOf(next));
		definition.setJoinType(resolveJoinType(step.getJoinPolicyJson()));
		definition.setConfig(buildStepConfig(step, actionSpec));
		return definition;
	}

	private StepType resolveStepType(String stepType) {
		if (!StringUtils.hasText(stepType)) {
			return StepType.HTTP;
		}
		String normalized = stepType.trim().toUpperCase(Locale.ROOT);
		return switch (normalized) {
			case "ACTION", "HTTP" -> StepType.HTTP;
			case "CONDITION" -> StepType.CONDITION;
			case "TRANSFORM" -> StepType.TRANSFORM;
			case "DELAY" -> StepType.DELAY;
			case "DATA_AGENT" -> StepType.DATA_AGENT;
			default -> StepType.HTTP;
		};
	}

	private JoinType resolveJoinType(String joinPolicyJson) {
		if (!StringUtils.hasText(joinPolicyJson)) {
			return JoinType.ALL;
		}
		String candidate = null;
		Object parsed = readJsonObject(joinPolicyJson);
		if (parsed instanceof Map<?, ?> map) {
			candidate = firstNonBlank(valueAsString(map.get("joinType")), valueAsString(map.get("mode")),
					valueAsString(map.get("strategy")));
		}
		if (!StringUtils.hasText(candidate) && parsed instanceof String text) {
			candidate = text;
		}
		return "ANY".equalsIgnoreCase(candidate) ? JoinType.ANY : JoinType.ALL;
	}

	private StepConfig buildStepConfig(@Nullable WorkflowStep step, @Nullable ActionSpec actionSpec) {
		StepConfig config = new StepConfig();
		Map<String, Object> binding = actionSpec != null ? readJsonMap(actionSpec.getOperationBindingJson()) : Map.of();
		config.setMethod(readString(binding, "method"));
		config.setEndpoint(readString(binding, "endpoint"));
		config.setContentType(firstNonBlank(readString(binding, "contentType"), readString(binding, "content_type")));
		config.setSuccessCondition(readString(binding, "successCondition"));
		if (step != null) {
			config.setInputMapping(parseStringMap(firstNonBlank(step.getInputMappingJson(), readString(binding, "inputMapping"))));
			config.setOutputMapping(parseStringMap(firstNonBlank(step.getOutputMappingJson(), readString(binding, "outputMapping"))));
			Object conditions = readJsonObject(step.getConditionJson());
			if (conditions != null) {
				config.setConditions(conditions);
			}
			Object approvalGate = readJsonObject(step.getApprovalGateJson());
			if (approvalGate != null) {
				config.setApprovalGate(approvalGate);
			}
		}
		else {
			config.setInputMapping(parseStringMap(readString(binding, "inputMapping")));
			config.setOutputMapping(parseStringMap(readString(binding, "outputMapping")));
		}
		return config;
	}

	private RuntimeArtifact.StepBinding toStepBinding(WorkflowStep step, @Nullable ActionSpec actionSpec) {
		return new RuntimeArtifact.StepBinding(
				step.getStepId(),
				step.getStepName(),
				step.getStepType(),
				step.getConnectorId() != null ? step.getConnectorId() : actionSpec != null ? actionSpec.getConnectorId() : null,
				step.getTargetRef(),
				firstNonBlank(step.getAllowedAuthProfilesJson(), actionSpec != null ? actionSpec.getAllowedAuthProfilesJson() : null),
				firstNonBlank(step.getBindingStrategiesJson(), actionSpec != null ? actionSpec.getBindingStrategiesJson() : null),
				step.getInputMappingJson(),
				step.getOutputMappingJson(),
				step.getDependsOnJson(),
				step.getConditionJson(),
				step.getJoinPolicyJson(),
				step.getRetryPolicyJson(),
				step.getTimeoutPolicyJson(),
				step.getApprovalGateJson(),
				step.getCompensationTargetRef(),
				step.getResumePolicyJson(),
				step.getStepOrder(),
				actionSpec != null ? toActionBinding(actionSpec) : null);
	}

	private RuntimeArtifact.ActionBinding toActionBinding(ActionSpec actionSpec) {
		return new RuntimeArtifact.ActionBinding(
				actionSpec.getId(),
				actionSpec.getActionCode(),
				actionSpec.getConnectorId(),
				actionSpec.getOperationBindingJson(),
				actionSpec.getAllowedAuthProfilesJson(),
				actionSpec.getDefaultAuthProfileCode(),
				actionSpec.getBindingStrategiesJson(),
				actionSpec.getInputSchemaJson(),
				actionSpec.getOutputSchemaJson(),
				actionSpec.getIdempotencyPolicyJson(),
				actionSpec.getRiskLevel(),
				actionSpec.getApprovalPolicyId(),
				actionSpec.getSideEffectLevel(),
				actionSpec.getObservabilityProfileJson(),
				actionSpec.getVersion());
	}

	private RuntimeArtifact.Interaction toInteraction(@Nullable InteractionSpec interactionSpec) {
		if (interactionSpec == null) {
			return null;
		}
		return new RuntimeArtifact.Interaction(
				interactionSpec.getId(),
				interactionSpec.getInteractionCode(),
				interactionSpec.getSlotSchemaJson(),
				interactionSpec.getAskStrategyJson(),
				interactionSpec.getAutoFillRulesJson(),
				interactionSpec.getSummaryLayoutJson(),
				interactionSpec.getConfirmationPolicyJson(),
				interactionSpec.getEditPolicyJson());
	}

	private List<String> parseStringList(String json) {
		Object parsed = readJsonObject(json);
		if (parsed instanceof List<?> list) {
			return list.stream().map(this::valueAsString).filter(StringUtils::hasText).toList();
		}
		if (parsed instanceof String text && StringUtils.hasText(text)) {
			return List.of(text);
		}
		return List.of();
	}

	private Map<String, String> parseStringMap(String json) {
		Object parsed = readJsonObject(json);
		if (!(parsed instanceof Map<?, ?> map)) {
			return Map.of();
		}
		Map<String, String> result = new LinkedHashMap<>();
		for (Map.Entry<?, ?> entry : map.entrySet()) {
			if (entry.getKey() != null) {
				result.put(String.valueOf(entry.getKey()), valueAsString(entry.getValue()));
			}
		}
		return result;
	}

	private Map<String, Object> readJsonMap(String json) {
		Object parsed = readJsonObject(json);
		if (parsed instanceof Map<?, ?> map) {
			Map<String, Object> result = new LinkedHashMap<>();
			for (Map.Entry<?, ?> entry : map.entrySet()) {
				if (entry.getKey() != null) {
					result.put(String.valueOf(entry.getKey()), entry.getValue());
				}
			}
			return result;
		}
		return Map.of();
	}

	private Object readJsonObject(String json) {
		if (!StringUtils.hasText(json)) {
			return null;
		}
		try {
			return objectMapper.readValue(json, Object.class);
		}
		catch (Exception ignored) {
			return json;
		}
	}

	private String readString(Map<String, Object> map, String key) {
		if (map == null || !StringUtils.hasText(key)) {
			return null;
		}
		return valueAsString(map.get(key));
	}

	private String valueAsString(Object value) {
		if (value == null) {
			return null;
		}
		if (value instanceof String text) {
			return text;
		}
		if (value instanceof Map<?, ?> || value instanceof List<?>) {
			try {
				return objectMapper.writeValueAsString(value);
			}
			catch (Exception ignored) {
				return String.valueOf(value);
			}
		}
		return String.valueOf(value);
	}

	private String firstNonBlank(String... values) {
		for (String value : values) {
			if (StringUtils.hasText(value)) {
				return value;
			}
		}
		return null;
	}

}





