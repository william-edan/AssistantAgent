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
import com.alibaba.assistant.agent.execution.flow.FlowDefinition;
import com.alibaba.assistant.agent.runtime.compiler.RuntimeArtifact;
import com.alibaba.assistant.agent.runtime.compiler.RuntimeArtifactCompiler;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RuntimeArtifactCatalogServiceTest {

	@Test
	void shouldLoadWorkflowArtifactDelegatingToCompiler() {
		WorkflowSpecService workflowSpecService = mock(WorkflowSpecService.class);
		WorkflowStepService workflowStepService = mock(WorkflowStepService.class);
		ActionSpecService actionSpecService = mock(ActionSpecService.class);
		InteractionSpecService interactionSpecService = mock(InteractionSpecService.class);
		AgentAppService agentAppService = mock(AgentAppService.class);
		AgentAppGrantService agentAppGrantService = mock(AgentAppGrantService.class);
		RuntimeArtifactCompiler compiler = mock(RuntimeArtifactCompiler.class);
		RuntimeArtifactCatalogService service = new RuntimeArtifactCatalogService(
				workflowSpecService,
				workflowStepService,
				actionSpecService,
				interactionSpecService,
				agentAppService,
				agentAppGrantService,
				compiler);

		WorkflowSpec workflowSpec = workflowSpec(11L, "oa.leave.apply", 21L);
		WorkflowStep workflowStep = workflowStep("create_leave", "oa.leave.create");
		InteractionSpec interactionSpec = interactionSpec(21L, "oa.leave.apply.interaction");
		ActionSpec actionSpec = actionSpec("oa.leave.create");
		RuntimeArtifact expected = artifact(RuntimeArtifact.ArtifactType.WORKFLOW, "oa.leave.apply");

		when(workflowSpecService.findLatestEnabledByCode(1L, "oa.leave.apply")).thenReturn(Optional.of(workflowSpec));
		when(workflowStepService.listEnabledByWorkflowId(11L)).thenReturn(List.of(workflowStep));
		when(interactionSpecService.getById(21L)).thenReturn(interactionSpec);
		when(actionSpecService.findLatestEnabledByCode(1L, "oa.leave.create")).thenReturn(Optional.of(actionSpec));
		when(compiler.compileWorkflow(eq(workflowSpec), eq(List.of(workflowStep)), eq(interactionSpec), eq(List.of(actionSpec))))
				.thenReturn(expected);

		Optional<RuntimeArtifact> loaded = service.loadWorkflowArtifact(1L, "oa.leave.apply");

		assertTrue(loaded.isPresent());
		assertSame(expected, loaded.get());
	}

	@Test
	void shouldLoadActionArtifactDelegatingToCompiler() {
		WorkflowSpecService workflowSpecService = mock(WorkflowSpecService.class);
		WorkflowStepService workflowStepService = mock(WorkflowStepService.class);
		ActionSpecService actionSpecService = mock(ActionSpecService.class);
		InteractionSpecService interactionSpecService = mock(InteractionSpecService.class);
		AgentAppService agentAppService = mock(AgentAppService.class);
		AgentAppGrantService agentAppGrantService = mock(AgentAppGrantService.class);
		RuntimeArtifactCompiler compiler = mock(RuntimeArtifactCompiler.class);
		RuntimeArtifactCatalogService service = new RuntimeArtifactCatalogService(
				workflowSpecService,
				workflowStepService,
				actionSpecService,
				interactionSpecService,
				agentAppService,
				agentAppGrantService,
				compiler);

		ActionSpec actionSpec = actionSpec("oa.leave.create");
		RuntimeArtifact expected = artifact(RuntimeArtifact.ArtifactType.ACTION, "oa.leave.create");
		when(actionSpecService.findLatestEnabledByCode(1L, "oa.leave.create")).thenReturn(Optional.of(actionSpec));
		when(compiler.compileAction(eq(actionSpec), isNull())).thenReturn(expected);

		Optional<RuntimeArtifact> loaded = service.loadActionArtifact(1L, "oa.leave.create");

		assertTrue(loaded.isPresent());
		assertSame(expected, loaded.get());
	}

	@Test
	void shouldListGrantedArtifactsApplyingDenyPrecedence() {
		WorkflowSpecService workflowSpecService = mock(WorkflowSpecService.class);
		WorkflowStepService workflowStepService = mock(WorkflowStepService.class);
		ActionSpecService actionSpecService = mock(ActionSpecService.class);
		InteractionSpecService interactionSpecService = mock(InteractionSpecService.class);
		AgentAppService agentAppService = mock(AgentAppService.class);
		AgentAppGrantService agentAppGrantService = mock(AgentAppGrantService.class);
		RuntimeArtifactCompiler compiler = mock(RuntimeArtifactCompiler.class);
		RuntimeArtifactCatalogService service = new RuntimeArtifactCatalogService(
				workflowSpecService,
				workflowStepService,
				actionSpecService,
				interactionSpecService,
				agentAppService,
				agentAppGrantService,
				compiler);

		AgentApp app = new AgentApp();
		app.setId(7L);
		app.setSpaceId(1L);
		app.setAgentAppCode("hr-assistant");
		WorkflowSpec allowedWorkflow = workflowSpec(31L, "oa.leave.apply", null);
		ActionSpec allowedAction = actionSpec("oa.leave.create");
		RuntimeArtifact workflowArtifact = artifact(RuntimeArtifact.ArtifactType.WORKFLOW, "oa.leave.apply");
		RuntimeArtifact actionArtifact = artifact(RuntimeArtifact.ArtifactType.ACTION, "oa.leave.create");

		when(agentAppService.findActiveByCode(1L, "hr-assistant")).thenReturn(Optional.of(app));
		when(agentAppGrantService.listByAgentAppId(7L)).thenReturn(List.of(
				grant("workflow", "oa.leave.apply", "allow"),
				grant("action", "oa.leave.create", "allow"),
				grant("workflow", "oa.leave.apply", "allow"),
				grant("workflow", "oa.leave.secret", "allow"),
				grant("workflow", "oa.leave.secret", "deny"),
				grant("resolver", "oa.leave.types", "allow")));
		when(workflowSpecService.findLatestEnabledByCode(1L, "oa.leave.apply")).thenReturn(Optional.of(allowedWorkflow));
		when(workflowStepService.listEnabledByWorkflowId(31L)).thenReturn(List.of());
		when(compiler.compileWorkflow(eq(allowedWorkflow), eq(List.of()), isNull(), eq(List.of()))).thenReturn(workflowArtifact);
		when(actionSpecService.findLatestEnabledByCode(1L, "oa.leave.create")).thenReturn(Optional.of(allowedAction));
		when(compiler.compileAction(eq(allowedAction), isNull())).thenReturn(actionArtifact);

		List<RuntimeArtifact> artifacts = service.listGrantedArtifacts(1L, "hr-assistant");

		assertEquals(List.of(workflowArtifact, actionArtifact), artifacts);
		verify(workflowSpecService, never()).findLatestEnabledByCode(1L, "oa.leave.secret");
	}

	private WorkflowSpec workflowSpec(Long id, String workflowCode, Long interactionSpecId) {
		WorkflowSpec spec = new WorkflowSpec();
		spec.setId(id);
		spec.setSpaceId(1L);
		spec.setWorkflowCode(workflowCode);
		spec.setDisplayName(workflowCode);
		spec.setInteractionSpecId(interactionSpecId);
		spec.setVersion(1);
		return spec;
	}

	private WorkflowStep workflowStep(String stepId, String targetRef) {
		WorkflowStep step = new WorkflowStep();
		step.setStepId(stepId);
		step.setStepName(stepId);
		step.setStepType("HTTP");
		step.setTargetRef(targetRef);
		step.setStepOrder(1);
		return step;
	}

	private InteractionSpec interactionSpec(Long id, String interactionCode) {
		InteractionSpec spec = new InteractionSpec();
		spec.setId(id);
		spec.setInteractionCode(interactionCode);
		return spec;
	}

	private ActionSpec actionSpec(String actionCode) {
		ActionSpec spec = new ActionSpec();
		spec.setId(41L);
		spec.setSpaceId(1L);
		spec.setActionCode(actionCode);
		return spec;
	}

	private AgentAppGrant grant(String targetType, String targetCode, String mode) {
		AgentAppGrant grant = new AgentAppGrant();
		grant.setTargetType(targetType);
		grant.setTargetCode(targetCode);
		grant.setGrantMode(mode);
		return grant;
	}

	private RuntimeArtifact artifact(RuntimeArtifact.ArtifactType type, String code) {
		FlowDefinition flowDefinition = new FlowDefinition();
		flowDefinition.setVersion("2.0");
		return new RuntimeArtifact(1L, code, type, code, 1, null, null, null, null, null, flowDefinition,
				Map.of(), Map.of());
	}

}
