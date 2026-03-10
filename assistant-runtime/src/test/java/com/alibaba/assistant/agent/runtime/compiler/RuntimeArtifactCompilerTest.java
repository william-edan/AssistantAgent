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
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeArtifactCompilerTest {

	@Test
	void shouldCompileWorkflowIntoRuntimeArtifact() {
		RuntimeArtifactCompiler compiler = new RuntimeArtifactCompiler(new ObjectMapper());

		RuntimeArtifact artifact = compiler.compileWorkflow(
				workflowSpec(),
				List.of(createLeaveStep(), submitApprovalStep()),
				interactionSpec(),
				List.of(createLeaveAction(), submitApprovalAction()));

		assertEquals(RuntimeArtifact.ArtifactType.WORKFLOW, artifact.getArtifactType());
		assertEquals("oa.leave.apply", artifact.getArtifactCode());
		assertEquals(List.of("create_leave"), artifact.getFlowDefinition().getEntry());
		assertEquals(List.of("submit_approval"), artifact.getFlowDefinition().getTerminal());
		assertEquals(List.of("submit_approval"), artifact.getFlowDefinition().getSteps().get("create_leave").getNext());
		assertEquals(List.of("create_leave"), artifact.getFlowDefinition().getSteps().get("submit_approval").getDependsOn());
		assertEquals("/home/leaves/add",
				artifact.getFlowDefinition().getSteps().get("create_leave").getConfig().getEndpoint());
		assertEquals("$.data.return_id",
				artifact.getFlowDefinition().getSteps().get("create_leave").getConfig().getOutputMapping().get("leave_id"));
		assertNotNull(artifact.getInteraction());
		assertEquals("oa.leave.apply.interaction", artifact.getInteraction().interactionCode());
		assertEquals("oa.leave.submit", artifact.getSteps().get("submit_approval").action().actionCode());
		assertEquals("[\"oa_user\"]", artifact.getSteps().get("submit_approval").allowedAuthProfilesJson());
		assertTrue(artifact.getActions().containsKey("oa.leave.create"));
	}

	@Test
	void shouldCompileStandaloneActionIntoSingleStepArtifact() {
		RuntimeArtifactCompiler compiler = new RuntimeArtifactCompiler(new ObjectMapper());

		RuntimeArtifact artifact = compiler.compileAction(createLeaveAction(), interactionSpec());

		assertEquals(RuntimeArtifact.ArtifactType.ACTION, artifact.getArtifactType());
		assertEquals("oa.leave.create", artifact.getArtifactCode());
		assertEquals(List.of("execute"), artifact.getFlowDefinition().getEntry());
		assertEquals(List.of("execute"), artifact.getFlowDefinition().getTerminal());
		assertEquals("/home/leaves/add",
				artifact.getFlowDefinition().getSteps().get("execute").getConfig().getEndpoint());
		assertEquals("oa.leave.create", artifact.getSteps().get("execute").action().actionCode());
	}

	@Test
	void shouldFailWhenWorkflowHttpStepReferencesMissingAction() {
		RuntimeArtifactCompiler compiler = new RuntimeArtifactCompiler(new ObjectMapper());

		IllegalStateException error = assertThrows(IllegalStateException.class,
				() -> compiler.compileWorkflow(
						workflowSpec(),
						List.of(createLeaveStep(), submitApprovalStep()),
						interactionSpec(),
						List.of(createLeaveAction())));

		assertTrue(error.getMessage().contains("oa.leave.submit"));
	}

	private WorkflowSpec workflowSpec() {
		WorkflowSpec spec = new WorkflowSpec();
		spec.setId(11L);
		spec.setSpaceId(1L);
		spec.setWorkflowCode("oa.leave.apply");
		spec.setDisplayName("请假申请");
		spec.setVersion(3);
		spec.setRiskAggregationPolicy("max_step_risk");
		spec.setApprovalAggregationPolicy("strictest_step_policy");
		spec.setFailurePolicyJson("{\"mode\":\"fail_fast\"}");
		spec.setAuditPolicyJson("{\"emit\":true}");
		return spec;
	}

	private InteractionSpec interactionSpec() {
		InteractionSpec spec = new InteractionSpec();
		spec.setId(21L);
		spec.setSpaceId(1L);
		spec.setInteractionCode("oa.leave.apply.interaction");
		spec.setSlotSchemaJson("{\"slots\":[{\"name\":\"reason\",\"type\":\"string\"}]}");
		spec.setAskStrategyJson("{\"collect\":{\"mode\":\"batch\"}}");
		spec.setConfirmationPolicyJson("{\"enabled\":true}");
		spec.setEditPolicyJson("{\"allow\":true}");
		return spec;
	}

	private ActionSpec createLeaveAction() {
		ActionSpec action = new ActionSpec();
		action.setId(31L);
		action.setSpaceId(1L);
		action.setActionCode("oa.leave.create");
		action.setConnectorId(101L);
		action.setOperationBindingJson("{\"method\":\"POST\",\"endpoint\":\"/home/leaves/add\",\"contentType\":\"application/x-www-form-urlencoded\",\"successCondition\":\"$.code == 0\"}");
		action.setAllowedAuthProfilesJson("[\"oa_user\"]");
		action.setDefaultAuthProfileCode("oa_user");
		action.setBindingStrategiesJson("[\"USER_MAPPED\"]");
		action.setInputSchemaJson("{\"type\":\"object\"}");
		action.setOutputSchemaJson("{\"type\":\"object\"}");
		action.setRiskLevel("medium");
		action.setSideEffectLevel("write");
		action.setVersion(2);
		return action;
	}

	private ActionSpec submitApprovalAction() {
		ActionSpec action = new ActionSpec();
		action.setId(32L);
		action.setSpaceId(1L);
		action.setActionCode("oa.leave.submit");
		action.setConnectorId(101L);
		action.setOperationBindingJson("{\"method\":\"POST\",\"endpoint\":\"/api/check/submit_check\",\"contentType\":\"application/x-www-form-urlencoded\",\"successCondition\":\"$.code == 0\"}");
		action.setAllowedAuthProfilesJson("[\"oa_user\"]");
		action.setDefaultAuthProfileCode("oa_user");
		action.setBindingStrategiesJson("[\"USER_MAPPED\"]");
		action.setInputSchemaJson("{\"type\":\"object\"}");
		action.setOutputSchemaJson("{\"type\":\"object\"}");
		action.setRiskLevel("high");
		action.setSideEffectLevel("write");
		action.setVersion(2);
		return action;
	}

	private WorkflowStep createLeaveStep() {
		WorkflowStep step = new WorkflowStep();
		step.setStepId("create_leave");
		step.setStepName("创建请假单");
		step.setStepType("HTTP");
		step.setTargetRef("oa.leave.create");
		step.setInputMappingJson("{\"reason\":\"${reason}\"}");
		step.setOutputMappingJson("{\"leave_id\":\"$.data.return_id\"}");
		step.setStepOrder(10);
		step.setStatus("enabled");
		return step;
	}

	private WorkflowStep submitApprovalStep() {
		WorkflowStep step = new WorkflowStep();
		step.setStepId("submit_approval");
		step.setStepName("提交审批");
		step.setStepType("HTTP");
		step.setTargetRef("oa.leave.submit");
		step.setAllowedAuthProfilesJson("[\"oa_user\"]");
		step.setBindingStrategiesJson("[\"USER_MAPPED\"]");
		step.setInputMappingJson("{\"action_id\":\"${create_leave.leave_id}\"}");
		step.setOutputMappingJson("{\"code\":\"$.code\"}");
		step.setDependsOnJson("[\"create_leave\"]");
		step.setRetryPolicyJson("{\"maxAttempts\":1}");
		step.setStepOrder(20);
		step.setStatus("enabled");
		return step;
	}

}
