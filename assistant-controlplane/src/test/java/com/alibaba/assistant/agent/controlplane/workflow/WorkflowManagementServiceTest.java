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
package com.alibaba.assistant.agent.controlplane.workflow;

import com.alibaba.assistant.agent.controlplane.connector.Connector;
import com.alibaba.assistant.agent.controlplane.connector.ConnectorService;
import com.alibaba.assistant.agent.controlplane.interaction.InteractionSpec;
import com.alibaba.assistant.agent.controlplane.interaction.InteractionSpecService;
import com.alibaba.assistant.agent.controlplane.space.PlatformSpace;
import com.alibaba.assistant.agent.controlplane.space.PlatformSpaceService;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WorkflowManagementServiceTest {

    @Test
    void shouldFilterWorkflowsByKeywordWithResolvedSteps() {
        PlatformSpaceService platformSpaceService = mock(PlatformSpaceService.class);
        WorkflowSpecService workflowSpecService = mock(WorkflowSpecService.class);
        WorkflowStepService workflowStepService = mock(WorkflowStepService.class);
        InteractionSpecService interactionSpecService = mock(InteractionSpecService.class);
        ConnectorService connectorService = mock(ConnectorService.class);
        WorkflowManagementService service = new WorkflowManagementService(
                platformSpaceService,
                workflowSpecService,
                workflowStepService,
                interactionSpecService,
                connectorService,
                new ObjectMapper());

        PlatformSpace space = new PlatformSpace();
        space.setId(10L);
        space.setSpaceCode("enterprise-default");
        space.setEnvironment("prod");
        InteractionSpec leaveInteraction = new InteractionSpec();
        leaveInteraction.setId(51L);
        leaveInteraction.setInteractionCode("leave.apply.form");
        InteractionSpec expenseInteraction = new InteractionSpec();
        expenseInteraction.setId(52L);
        expenseInteraction.setInteractionCode("expense.apply.form");
        Connector connector = connector(21L, 10L, "oa-core", "prod");
        WorkflowSpec leaveWorkflow = workflow(31L, 10L, "oa.leave.apply", "请假申请", "enabled");
        leaveWorkflow.setInteractionSpecId(51L);
        leaveWorkflow.setRiskAggregationPolicy("max");
        leaveWorkflow.setApprovalAggregationPolicy("strictest");
        leaveWorkflow.setFailurePolicyJson("{\"mode\":\"fail_fast\"}");
        leaveWorkflow.setAuditPolicyJson("{\"level\":\"full\"}");
        WorkflowSpec expenseWorkflow = workflow(32L, 10L, "expense.approval", "报销审批", "enabled");
        expenseWorkflow.setInteractionSpecId(52L);
        expenseWorkflow.setRiskAggregationPolicy("sum");
        expenseWorkflow.setApprovalAggregationPolicy("strictest");
        expenseWorkflow.setFailurePolicyJson("{\"mode\":\"continue\"}");
        expenseWorkflow.setAuditPolicyJson("{\"level\":\"basic\"}");
        WorkflowStep leaveStep = step(71L, 31L, "create_leave", "创建请假", "HTTP", 21L, "/leave/create", 1, "enabled");
        leaveStep.setAllowedAuthProfilesJson("[\"oa-user\"]");
        leaveStep.setBindingStrategiesJson("[\"user_mapped\"]");
        leaveStep.setInputMappingJson("{\"reason\":\"${reason}\"}");
        leaveStep.setOutputMappingJson("{\"leaveId\":\"$.data.id\"}");
        leaveStep.setDependsOnJson("[]");
        leaveStep.setConditionJson("{\"expr\":\"${reason}\"}");
        leaveStep.setJoinPolicyJson("{\"type\":\"ALL\"}");
        leaveStep.setRetryPolicyJson("{\"maxRetries\":1}");
        leaveStep.setTimeoutPolicyJson("{\"seconds\":30}");
        leaveStep.setApprovalGateJson("{\"required\":false}");
        leaveStep.setResumePolicyJson("{\"mode\":\"continue\"}");
        WorkflowStep expenseStep = step(72L, 32L, "create_expense", "创建报销", "HTTP", 21L, "/expense/create", 1, "enabled");
        expenseStep.setAllowedAuthProfilesJson("[\"oa-user\"]");
        expenseStep.setBindingStrategiesJson("[\"service_account\"]");
        expenseStep.setInputMappingJson("{\"amount\":\"${amount}\"}");
        expenseStep.setOutputMappingJson("{\"expenseId\":\"$.data.id\"}");
        expenseStep.setDependsOnJson("[]");
        expenseStep.setConditionJson("{\"expr\":\"${amount}\"}");
        expenseStep.setJoinPolicyJson("{\"type\":\"ANY\"}");
        expenseStep.setRetryPolicyJson("{\"maxRetries\":2}");
        expenseStep.setTimeoutPolicyJson("{\"seconds\":45}");
        expenseStep.setApprovalGateJson("{\"required\":true}");
        expenseStep.setResumePolicyJson("{\"mode\":\"continue\"}");

        when(platformSpaceService.findActiveByCode("enterprise-default", "prod")).thenReturn(Optional.of(space));
        when(workflowSpecService.listEnabledBySpace(10L)).thenReturn(List.of(leaveWorkflow, expenseWorkflow));
        when(workflowStepService.listEnabledByWorkflowId(31L)).thenReturn(List.of(leaveStep));
        when(workflowStepService.listEnabledByWorkflowId(32L)).thenReturn(List.of(expenseStep));
        when(interactionSpecService.getById(51L)).thenReturn(leaveInteraction);
        when(interactionSpecService.getById(52L)).thenReturn(expenseInteraction);
        when(connectorService.getById(21L)).thenReturn(connector);

        List<ResolvedWorkflowManagementView> result = service.listWorkflows("enterprise-default", "prod", "leave");

        assertEquals(1, result.size());
        assertEquals("oa.leave.apply", result.get(0).workflowCode());
        assertEquals("leave.apply.form", result.get(0).interactionCode());
        assertEquals("fail_fast", result.get(0).failurePolicy().get("mode"));
        assertEquals("oa-core", result.get(0).steps().get(0).connectorCode());
        assertEquals("continue", result.get(0).steps().get(0).resumePolicy().get("mode"));
    }
    @Test
    void shouldGetWorkflowByCodeWithResolvedSteps() {
        PlatformSpaceService platformSpaceService = mock(PlatformSpaceService.class);
        WorkflowSpecService workflowSpecService = mock(WorkflowSpecService.class);
        WorkflowStepService workflowStepService = mock(WorkflowStepService.class);
        InteractionSpecService interactionSpecService = mock(InteractionSpecService.class);
        ConnectorService connectorService = mock(ConnectorService.class);
        WorkflowManagementService service = new WorkflowManagementService(
                platformSpaceService,
                workflowSpecService,
                workflowStepService,
                interactionSpecService,
                connectorService,
                new ObjectMapper());

        PlatformSpace space = new PlatformSpace();
        space.setId(10L);
        space.setSpaceCode("enterprise-default");
        space.setEnvironment("prod");
        InteractionSpec interaction = new InteractionSpec();
        interaction.setId(51L);
        interaction.setInteractionCode("leave.apply.form");
        Connector connector = connector(21L, 10L, "oa-core", "prod");
        WorkflowSpec workflow = workflow(31L, 10L, "oa.leave.apply", "请假申请", "enabled");
        workflow.setInteractionSpecId(51L);
        workflow.setRiskAggregationPolicy("max");
        workflow.setApprovalAggregationPolicy("strictest");
        workflow.setFailurePolicyJson("{\"mode\":\"fail_fast\"}");
        workflow.setAuditPolicyJson("{\"level\":\"full\"}");
        WorkflowStep step = step(71L, 31L, "create_leave", "创建请假", "HTTP", 21L, "/leave/create", 1, "enabled");
        step.setAllowedAuthProfilesJson("[\"oa-user\"]");
        step.setBindingStrategiesJson("[\"user_mapped\"]");
        step.setInputMappingJson("{\"reason\":\"${reason}\"}");
        step.setOutputMappingJson("{\"leaveId\":\"$.data.id\"}");
        step.setDependsOnJson("[]");
        step.setConditionJson("{\"expr\":\"${reason}\"}");
        step.setJoinPolicyJson("{\"type\":\"ALL\"}");
        step.setRetryPolicyJson("{\"maxRetries\":1}");
        step.setTimeoutPolicyJson("{\"seconds\":30}");
        step.setApprovalGateJson("{\"required\":false}");
        step.setResumePolicyJson("{\"mode\":\"continue\"}");

        when(platformSpaceService.findActiveByCode("enterprise-default", "prod")).thenReturn(Optional.of(space));
        when(workflowSpecService.findLatestEnabledByCode(10L, "oa.leave.apply")).thenReturn(Optional.of(workflow));
        when(workflowStepService.listEnabledByWorkflowId(31L)).thenReturn(List.of(step));
        when(interactionSpecService.getById(51L)).thenReturn(interaction);
        when(connectorService.getById(21L)).thenReturn(connector);

        Optional<ResolvedWorkflowManagementView> result = service.getWorkflow("enterprise-default", "prod", "oa.leave.apply");

        assertTrue(result.isPresent());
        assertEquals("oa.leave.apply", result.get().workflowCode());
        assertEquals("oa-core", result.get().steps().get(0).connectorCode());
    }
    @Test
    void shouldCreateWorkflowWhenCodeDoesNotExist() {
        PlatformSpaceService platformSpaceService = mock(PlatformSpaceService.class);
        WorkflowSpecService workflowSpecService = mock(WorkflowSpecService.class);
        WorkflowStepService workflowStepService = mock(WorkflowStepService.class);
        InteractionSpecService interactionSpecService = mock(InteractionSpecService.class);
        ConnectorService connectorService = mock(ConnectorService.class);
        WorkflowManagementService service = new WorkflowManagementService(
                platformSpaceService,
                workflowSpecService,
                workflowStepService,
                interactionSpecService,
                connectorService,
                new ObjectMapper());

        PlatformSpace space = new PlatformSpace();
        space.setId(11L);
        space.setSpaceCode("enterprise-default");
        space.setEnvironment("prod");
        InteractionSpec interaction = new InteractionSpec();
        interaction.setId(52L);
        interaction.setInteractionCode("leave.apply.form");
        Connector connector = connector(22L, 11L, "oa-core", "prod");

        when(platformSpaceService.findActiveByCode("enterprise-default", "prod")).thenReturn(Optional.of(space));
        when(interactionSpecService.findLatestEnabledByCode(11L, "leave.apply.form")).thenReturn(Optional.of(interaction));
        when(workflowSpecService.findLatestEnabledByCode(11L, "oa.leave.apply")).thenReturn(Optional.empty());
        when(workflowSpecService.save(any(WorkflowSpec.class))).thenAnswer(invocation -> { WorkflowSpec saved = invocation.getArgument(0); saved.setId(61L); return true; });
        when(connectorService.findLatestActiveByCodeAndEnvironment(11L, "prod", "oa-core")).thenReturn(Optional.of(connector));
        when(workflowStepService.saveBatch(any())).thenReturn(true);

        Optional<ResolvedWorkflowManagementView> result = service.upsertWorkflow(
                "enterprise-default",
                "prod",
                "oa.leave.apply",
                new WorkflowSpecUpsertCommand(
                        "请假申请",
                        "leave.apply.form",
                        "max",
                        "strictest",
                        Map.of("mode", "fail_fast"),
                        Map.of("level", "full"),
                        List.of(new WorkflowStepUpsertCommand(
                                "create_leave",
                                "创建请假",
                                "HTTP",
                                "oa-core",
                                "/leave/create",
                                List.of("oa-user"),
                                List.of("user_mapped"),
                                Map.of("reason", "${reason}"),
                                Map.of("leaveId", "$.data.id"),
                                List.of(),
                                Map.of("expr", "${reason}"),
                                Map.of("type", "ALL"),
                                Map.of("maxRetries", 1),
                                Map.of("seconds", 30),
                                Map.of("required", false),
                                null,
                                Map.of("mode", "continue"),
                                1,
                                "enabled")),
                        "enabled"));

        assertTrue(result.isPresent());
        assertEquals("oa.leave.apply", result.get().workflowCode());
        assertEquals("leave.apply.form", result.get().interactionCode());
        assertEquals("oa-core", result.get().steps().get(0).connectorCode());
    }

    @Test
    void shouldUpdateWorkflowWhenCodeExists() {
        PlatformSpaceService platformSpaceService = mock(PlatformSpaceService.class);
        WorkflowSpecService workflowSpecService = mock(WorkflowSpecService.class);
        WorkflowStepService workflowStepService = mock(WorkflowStepService.class);
        InteractionSpecService interactionSpecService = mock(InteractionSpecService.class);
        ConnectorService connectorService = mock(ConnectorService.class);
        WorkflowManagementService service = new WorkflowManagementService(
                platformSpaceService,
                workflowSpecService,
                workflowStepService,
                interactionSpecService,
                connectorService,
                new ObjectMapper());

        PlatformSpace space = new PlatformSpace();
        space.setId(12L);
        space.setSpaceCode("enterprise-default");
        space.setEnvironment("test");
        InteractionSpec interaction = new InteractionSpec();
        interaction.setId(53L);
        interaction.setInteractionCode("leave.apply.form");
        Connector connector = connector(23L, 12L, "oa-core", "test");
        WorkflowSpec existing = workflow(41L, 12L, "oa.leave.apply", "请假申请", "enabled");
        existing.setInteractionSpecId(53L);

        when(platformSpaceService.findActiveByCode("enterprise-default", "test")).thenReturn(Optional.of(space));
        when(interactionSpecService.findLatestEnabledByCode(12L, "leave.apply.form")).thenReturn(Optional.of(interaction));
        when(workflowSpecService.findLatestEnabledByCode(12L, "oa.leave.apply")).thenReturn(Optional.of(existing));
        when(workflowSpecService.updateById(any(WorkflowSpec.class))).thenReturn(true);
        when(workflowStepService.remove(any(Wrapper.class))).thenReturn(true);
        when(connectorService.findLatestActiveByCodeAndEnvironment(12L, "test", "oa-core")).thenReturn(Optional.of(connector));
        when(workflowStepService.saveBatch(any())).thenReturn(true);

        Optional<ResolvedWorkflowManagementView> result = service.upsertWorkflow(
                "enterprise-default",
                "test",
                "oa.leave.apply",
                new WorkflowSpecUpsertCommand(
                        "请假申请V2",
                        "leave.apply.form",
                        "sum",
                        "strictest",
                        Map.of("mode", "continue"),
                        Map.of("level", "basic"),
                        List.of(new WorkflowStepUpsertCommand(
                                "submit_leave",
                                "提交请假",
                                "HTTP",
                                "oa-core",
                                "/leave/submit",
                                List.of("oa-user"),
                                List.of("user_mapped"),
                                Map.of("reason", "${reason}"),
                                Map.of("ok", true),
                                List.of("prepare"),
                                Map.of("expr", true),
                                Map.of("type", "ANY"),
                                Map.of("maxRetries", 2),
                                Map.of("seconds", 45),
                                Map.of("required", true),
                                null,
                                Map.of("mode", "continue"),
                                2,
                                "enabled")),
                        "enabled"));

        assertTrue(result.isPresent());
        assertEquals("请假申请V2", result.get().displayName());
        assertEquals("sum", result.get().riskAggregationPolicy());
        assertEquals("submit_leave", result.get().steps().get(0).stepId());
    }

    private WorkflowSpec workflow(Long id, Long spaceId, String workflowCode, String displayName, String status) {
        WorkflowSpec workflow = new WorkflowSpec();
        workflow.setId(id);
        workflow.setSpaceId(spaceId);
        workflow.setWorkflowCode(workflowCode);
        workflow.setDisplayName(displayName);
        workflow.setStatus(status);
        return workflow;
    }

    private WorkflowStep step(
            Long id,
            Long workflowId,
            String stepId,
            String stepName,
            String stepType,
            Long connectorId,
            String targetRef,
            Integer stepOrder,
            String status) {
        WorkflowStep step = new WorkflowStep();
        step.setId(id);
        step.setWorkflowId(workflowId);
        step.setStepId(stepId);
        step.setStepName(stepName);
        step.setStepType(stepType);
        step.setConnectorId(connectorId);
        step.setTargetRef(targetRef);
        step.setStepOrder(stepOrder);
        step.setStatus(status);
        return step;
    }

    private Connector connector(Long id, Long spaceId, String connectorCode, String environment) {
        Connector connector = new Connector();
        connector.setId(id);
        connector.setSpaceId(spaceId);
        connector.setConnectorCode(connectorCode);
        connector.setEnvironment(environment);
        connector.setStatus("active");
        return connector;
    }
}



