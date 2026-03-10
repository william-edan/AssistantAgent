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

import com.alibaba.assistant.agent.execution.flow.FlowDefinition;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Runtime-ready artifact compiled from control-plane workflow or action definitions.
 */
public final class RuntimeArtifact {

	private final Long spaceId;
	private final String artifactCode;
	private final ArtifactType artifactType;
	private final String displayName;
	private final Integer version;
	private final String riskAggregationPolicy;
	private final String approvalAggregationPolicy;
	private final String failurePolicyJson;
	private final String auditPolicyJson;
	private final Interaction interaction;
	private final FlowDefinition flowDefinition;
	private final Map<String, ActionBinding> actions;
	private final Map<String, StepBinding> steps;

	/**
	 * Create an immutable runtime artifact aggregate.
	 */
	public RuntimeArtifact(
			Long spaceId,
			String artifactCode,
			ArtifactType artifactType,
			String displayName,
			Integer version,
			String riskAggregationPolicy,
			String approvalAggregationPolicy,
			String failurePolicyJson,
			String auditPolicyJson,
			Interaction interaction,
			FlowDefinition flowDefinition,
			Map<String, ActionBinding> actions,
			Map<String, StepBinding> steps) {
		this.spaceId = spaceId;
		this.artifactCode = artifactCode;
		this.artifactType = artifactType;
		this.displayName = displayName;
		this.version = version;
		this.riskAggregationPolicy = riskAggregationPolicy;
		this.approvalAggregationPolicy = approvalAggregationPolicy;
		this.failurePolicyJson = failurePolicyJson;
		this.auditPolicyJson = auditPolicyJson;
		this.interaction = interaction;
		this.flowDefinition = flowDefinition;
		this.actions = Map.copyOf(actions != null ? new LinkedHashMap<>(actions) : Map.of());
		this.steps = Map.copyOf(steps != null ? new LinkedHashMap<>(steps) : Map.of());
	}

	public Long getSpaceId() {
		return spaceId;
	}

	public String getArtifactCode() {
		return artifactCode;
	}

	public ArtifactType getArtifactType() {
		return artifactType;
	}

	public String getDisplayName() {
		return displayName;
	}

	public Integer getVersion() {
		return version;
	}

	public String getRiskAggregationPolicy() {
		return riskAggregationPolicy;
	}

	public String getApprovalAggregationPolicy() {
		return approvalAggregationPolicy;
	}

	public String getFailurePolicyJson() {
		return failurePolicyJson;
	}

	public String getAuditPolicyJson() {
		return auditPolicyJson;
	}

	public Interaction getInteraction() {
		return interaction;
	}

	public FlowDefinition getFlowDefinition() {
		return flowDefinition;
	}

	public Map<String, ActionBinding> getActions() {
		return actions;
	}

	public Map<String, StepBinding> getSteps() {
		return steps;
	}

	/**
	 * Artifact type compiled for runtime usage.
	 */
	public enum ArtifactType {
		ACTION,
		WORKFLOW
	}

	/**
	 * Runtime snapshot of the interaction definition attached to an artifact.
	 */
	public record Interaction(
			Long interactionSpecId,
			String interactionCode,
			String slotSchemaJson,
			String askStrategyJson,
			String autoFillRulesJson,
			String summaryLayoutJson,
			String confirmationPolicyJson,
			String editPolicyJson) {
	}

	/**
	 * Runtime snapshot of an action definition used by workflow steps.
	 */
	public record ActionBinding(
			Long actionSpecId,
			String actionCode,
			Long connectorId,
			String operationBindingJson,
			String allowedAuthProfilesJson,
			String defaultAuthProfileCode,
			String bindingStrategiesJson,
			String inputSchemaJson,
			String outputSchemaJson,
			String idempotencyPolicyJson,
			String riskLevel,
			Long approvalPolicyId,
			String sideEffectLevel,
			String observabilityProfileJson,
			Integer version) {
	}

	/**
	 * Runtime snapshot of a workflow step definition.
	 */
	public record StepBinding(
			String stepId,
			String stepName,
			String stepType,
			Long connectorId,
			String targetRef,
			String allowedAuthProfilesJson,
			String bindingStrategiesJson,
			String inputMappingJson,
			String outputMappingJson,
			String dependsOnJson,
			String conditionJson,
			String joinPolicyJson,
			String retryPolicyJson,
			String timeoutPolicyJson,
			String approvalGateJson,
			String compensationTargetRef,
			String resumePolicyJson,
			Integer stepOrder,
			ActionBinding action) {
	}

}
