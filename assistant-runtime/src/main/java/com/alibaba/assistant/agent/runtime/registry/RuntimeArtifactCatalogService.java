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
package com.alibaba.assistant.agent.runtime.registry;

import com.alibaba.assistant.agent.controlplane.action.ActionSpec;
import com.alibaba.assistant.agent.controlplane.action.ActionSpecService;
import com.alibaba.assistant.agent.controlplane.agentapp.AgentApp;
import com.alibaba.assistant.agent.controlplane.agentapp.AgentAppGrant;
import com.alibaba.assistant.agent.controlplane.agentapp.AgentAppGrantService;
import com.alibaba.assistant.agent.controlplane.agentapp.AgentAppService;
import com.alibaba.assistant.agent.controlplane.interaction.InteractionSpec;
import com.alibaba.assistant.agent.controlplane.interaction.InteractionSpecService;
import com.alibaba.assistant.agent.controlplane.workflow.WorkflowSpec;
import com.alibaba.assistant.agent.controlplane.workflow.WorkflowSpecService;
import com.alibaba.assistant.agent.controlplane.workflow.WorkflowStep;
import com.alibaba.assistant.agent.controlplane.workflow.WorkflowStepService;
import com.alibaba.assistant.agent.runtime.compiler.RuntimeArtifact;
import com.alibaba.assistant.agent.runtime.compiler.RuntimeArtifactCompiler;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Loads runtime artifacts from the new control-plane definitions.
 */
@Service
public class RuntimeArtifactCatalogService {

	private final WorkflowSpecService workflowSpecService;
	private final WorkflowStepService workflowStepService;
	private final ActionSpecService actionSpecService;
	private final InteractionSpecService interactionSpecService;
	private final AgentAppService agentAppService;
	private final AgentAppGrantService agentAppGrantService;
	private final RuntimeArtifactCompiler runtimeArtifactCompiler;

	public RuntimeArtifactCatalogService(
			WorkflowSpecService workflowSpecService,
			WorkflowStepService workflowStepService,
			ActionSpecService actionSpecService,
			InteractionSpecService interactionSpecService,
			AgentAppService agentAppService,
			AgentAppGrantService agentAppGrantService,
			RuntimeArtifactCompiler runtimeArtifactCompiler) {
		this.workflowSpecService = workflowSpecService;
		this.workflowStepService = workflowStepService;
		this.actionSpecService = actionSpecService;
		this.interactionSpecService = interactionSpecService;
		this.agentAppService = agentAppService;
		this.agentAppGrantService = agentAppGrantService;
		this.runtimeArtifactCompiler = runtimeArtifactCompiler;
	}

	/**
	 * Load a compiled workflow artifact by space and workflow code.
	 */
	public Optional<RuntimeArtifact> loadWorkflowArtifact(Long spaceId, String workflowCode) {
		if (spaceId == null || !StringUtils.hasText(workflowCode)) {
			return Optional.empty();
		}

		Optional<WorkflowSpec> workflowSpec = workflowSpecService.findLatestEnabledByCode(spaceId, workflowCode);
		if (workflowSpec.isEmpty()) {
			return Optional.empty();
		}

		WorkflowSpec spec = workflowSpec.get();
		List<WorkflowStep> steps = workflowStepService.listEnabledByWorkflowId(spec.getId());
		InteractionSpec interactionSpec = spec.getInteractionSpecId() != null
				? interactionSpecService.getById(spec.getInteractionSpecId())
				: null;
		List<ActionSpec> actions = resolveWorkflowActions(spaceId, steps);
		return Optional.of(runtimeArtifactCompiler.compileWorkflow(spec, steps, interactionSpec, actions));
	}

	/**
	 * Load a compiled standalone action artifact by space and action code.
	 */
	public Optional<RuntimeArtifact> loadActionArtifact(Long spaceId, String actionCode) {
		if (spaceId == null || !StringUtils.hasText(actionCode)) {
			return Optional.empty();
		}
		return actionSpecService.findLatestEnabledByCode(spaceId, actionCode)
				.map(actionSpec -> runtimeArtifactCompiler.compileAction(actionSpec, null));
	}

	/**
	 * List compiled artifacts granted to an active agent app.
	 */
	public List<RuntimeArtifact> listGrantedArtifacts(Long spaceId, String agentAppCode) {
		if (spaceId == null || !StringUtils.hasText(agentAppCode)) {
			return List.of();
		}

		Optional<AgentApp> agentApp = agentAppService.findActiveByCode(spaceId, agentAppCode);
		if (agentApp.isEmpty()) {
			return List.of();
		}

		List<AgentAppGrant> grants = agentAppGrantService.listByAgentAppId(agentApp.get().getId());
		Set<GrantedTarget> deniedTargets = new LinkedHashSet<>();
		for (AgentAppGrant grant : grants) {
			GrantedTarget target = toGrantedTarget(grant);
			if (target != null && isDeny(grant)) {
				deniedTargets.add(target);
			}
		}

		Set<GrantedTarget> allowedTargets = new LinkedHashSet<>();
		for (AgentAppGrant grant : grants) {
			GrantedTarget target = toGrantedTarget(grant);
			if (target == null || isDeny(grant) || deniedTargets.contains(target)) {
				continue;
			}
			allowedTargets.add(target);
		}

		List<RuntimeArtifact> artifacts = new ArrayList<>();
		for (GrantedTarget target : allowedTargets) {
			Optional<RuntimeArtifact> artifact = switch (target.targetType()) {
				case "workflow" -> loadWorkflowArtifact(spaceId, target.targetCode());
				case "action" -> loadActionArtifact(spaceId, target.targetCode());
				default -> Optional.empty();
			};
			artifact.ifPresent(artifacts::add);
		}
		return List.copyOf(artifacts);
	}

	private List<ActionSpec> resolveWorkflowActions(Long spaceId, List<WorkflowStep> steps) {
		Set<String> actionCodes = new LinkedHashSet<>();
		for (WorkflowStep step : steps != null ? steps : List.<WorkflowStep>of()) {
			if (step == null || !requiresAction(step.getStepType()) || !StringUtils.hasText(step.getTargetRef())) {
				continue;
			}
			actionCodes.add(step.getTargetRef());
		}

		List<ActionSpec> actions = new ArrayList<>();
		for (String actionCode : actionCodes) {
			actionSpecService.findLatestEnabledByCode(spaceId, actionCode).ifPresent(actions::add);
		}
		return actions;
	}

	private boolean requiresAction(String stepType) {
		if (!StringUtils.hasText(stepType)) {
			return true;
		}
		String normalized = stepType.trim().toUpperCase(Locale.ROOT);
		return "HTTP".equals(normalized) || "ACTION".equals(normalized);
	}

	private GrantedTarget toGrantedTarget(AgentAppGrant grant) {
		if (grant == null || !StringUtils.hasText(grant.getTargetType()) || !StringUtils.hasText(grant.getTargetCode())) {
			return null;
		}
		String targetType = grant.getTargetType().trim().toLowerCase(Locale.ROOT);
		if (!"workflow".equals(targetType) && !"action".equals(targetType)) {
			return null;
		}
		return new GrantedTarget(targetType, grant.getTargetCode());
	}

	private boolean isDeny(AgentAppGrant grant) {
		if (grant == null || !StringUtils.hasText(grant.getGrantMode())) {
			return false;
		}
		return "deny".equalsIgnoreCase(grant.getGrantMode());
	}

	private record GrantedTarget(String targetType, String targetCode) {
	}

}
