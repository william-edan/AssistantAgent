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
package com.alibaba.assistant.agent.controlplane.compiler;

import com.alibaba.assistant.agent.controlplane.action.ActionSpec;
import com.alibaba.assistant.agent.controlplane.action.ActionSpecService;
import com.alibaba.assistant.agent.controlplane.interaction.InteractionSpec;
import com.alibaba.assistant.agent.controlplane.interaction.InteractionSpecService;
import com.alibaba.assistant.agent.controlplane.space.PlatformSpace;
import com.alibaba.assistant.agent.controlplane.space.PlatformSpaceService;
import com.alibaba.assistant.agent.controlplane.workflow.WorkflowSpec;
import com.alibaba.assistant.agent.controlplane.workflow.WorkflowSpecService;
import com.alibaba.assistant.agent.controlplane.workflow.WorkflowStep;
import com.alibaba.assistant.agent.controlplane.workflow.WorkflowStepService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LegacyCapabilityCompilerTest {

    @Test
    void shouldCompileFlowLegacyCapabilityIntoNewArtifacts() {
        LegacyCapabilityCompiler compiler = new LegacyCapabilityCompiler(
                mock(JdbcTemplate.class),
                mock(PlatformSpaceService.class),
                mock(InteractionSpecService.class),
                mock(ActionSpecService.class),
                mock(WorkflowSpecService.class),
                mock(WorkflowStepService.class),
                new ObjectMapper());

        CompiledLegacyCapability compiled = compiler.compile(flowLegacyRow(), 1L);

        assertNotNull(compiled);
        assertEquals("gougu_oa.leave_application", compiled.baseCode());
        assertEquals("gougu_oa.leave_application.interaction", compiled.interactionSpec().getInteractionCode());
        assertEquals("gougu_oa.leave_application", compiled.workflowSpec().getWorkflowCode());
        assertEquals(2, compiled.actionSpecs().size());
        assertEquals("gougu_oa.leave_application.create_leave", compiled.actionSpecs().get(0).getActionCode());
        assertTrue(compiled.actionSpecs().get(0).getOperationBindingJson().contains("/home/leaves/add"));
        assertEquals(2, compiled.workflowSteps().size());
        assertEquals("gougu_oa.leave_application.create_leave", compiled.workflowSteps().get(0).getTargetRef());
        assertEquals("gougu_oa.leave_application.submit_approval", compiled.workflowSteps().get(1).getTargetRef());
    }

    @Test
    void shouldInsertCompiledArtifactsWhenNoExistingDefinitionsPresent() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        PlatformSpaceService platformSpaceService = mock(PlatformSpaceService.class);
        InteractionSpecService interactionSpecService = mock(InteractionSpecService.class);
        ActionSpecService actionSpecService = mock(ActionSpecService.class);
        WorkflowSpecService workflowSpecService = mock(WorkflowSpecService.class);
        WorkflowStepService workflowStepService = mock(WorkflowStepService.class);
        LegacyCapabilityCompiler compiler = new LegacyCapabilityCompiler(
                jdbcTemplate,
                platformSpaceService,
                interactionSpecService,
                actionSpecService,
                workflowSpecService,
                workflowStepService,
                new ObjectMapper());

        when(jdbcTemplate.queryForList("SELECT * FROM assistant_capability_registry ORDER BY id ASC"))
                .thenReturn(List.of(flowLegacyRow()));
        PlatformSpace space = new PlatformSpace();
        space.setId(1L);
        space.setSpaceCode("default");
        when(platformSpaceService.findActiveByCode("default", "prod")).thenReturn(Optional.of(space));
        when(interactionSpecService.getOne(any(Wrapper.class), eq(false))).thenReturn(null);
        when(actionSpecService.getOne(any(Wrapper.class), eq(false))).thenReturn(null);
        when(workflowSpecService.getOne(any(Wrapper.class), eq(false))).thenReturn(null);
        when(workflowStepService.getOne(any(Wrapper.class), eq(false))).thenReturn(null);
        when(interactionSpecService.save(any(InteractionSpec.class))).thenReturn(true);
        when(actionSpecService.save(any(ActionSpec.class))).thenReturn(true);
        doAnswer(invocation -> {
            WorkflowSpec workflowSpec = invocation.getArgument(0);
            workflowSpec.setId(101L);
            return true;
        }).when(workflowSpecService).save(any(WorkflowSpec.class));
        when(workflowStepService.save(any(WorkflowStep.class))).thenReturn(true);

        LegacyCapabilityCompiler.CompilationResult result = compiler.compileAll("prod");

        assertEquals(1, result.scanned());
        assertEquals(1, result.inserted());
        assertEquals(0, result.updated());
        assertEquals(0, result.skipped());
        verify(interactionSpecService, times(1)).save(any(InteractionSpec.class));
        verify(actionSpecService, times(2)).save(any(ActionSpec.class));
        verify(workflowSpecService, times(1)).save(any(WorkflowSpec.class));
        verify(workflowStepService, times(2)).save(any(WorkflowStep.class));
    }

    @Test
    void shouldUpdateCompiledArtifactsWhenDefinitionsAlreadyExist() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        PlatformSpaceService platformSpaceService = mock(PlatformSpaceService.class);
        InteractionSpecService interactionSpecService = mock(InteractionSpecService.class);
        ActionSpecService actionSpecService = mock(ActionSpecService.class);
        WorkflowSpecService workflowSpecService = mock(WorkflowSpecService.class);
        WorkflowStepService workflowStepService = mock(WorkflowStepService.class);
        LegacyCapabilityCompiler compiler = new LegacyCapabilityCompiler(
                jdbcTemplate,
                platformSpaceService,
                interactionSpecService,
                actionSpecService,
                workflowSpecService,
                workflowStepService,
                new ObjectMapper());

        when(jdbcTemplate.queryForList("SELECT * FROM assistant_capability_registry ORDER BY id ASC"))
                .thenReturn(List.of(flowLegacyRow()));
        PlatformSpace space = new PlatformSpace();
        space.setId(1L);
        space.setSpaceCode("default");
        when(platformSpaceService.findActiveByCode("default", "prod")).thenReturn(Optional.of(space));

        InteractionSpec existingInteraction = new InteractionSpec();
        existingInteraction.setId(11L);
        ActionSpec existingAction1 = new ActionSpec();
        existingAction1.setId(21L);
        ActionSpec existingAction2 = new ActionSpec();
        existingAction2.setId(22L);
        WorkflowSpec existingWorkflow = new WorkflowSpec();
        existingWorkflow.setId(31L);
        WorkflowStep existingStep1 = new WorkflowStep();
        existingStep1.setId(41L);
        WorkflowStep existingStep2 = new WorkflowStep();
        existingStep2.setId(42L);

        when(interactionSpecService.getOne(any(Wrapper.class), eq(false))).thenReturn(existingInteraction);
        AtomicInteger actionGetOneCount = new AtomicInteger();
        when(actionSpecService.getOne(any(Wrapper.class), eq(false))).thenAnswer(invocation ->
                actionGetOneCount.getAndIncrement() == 0 ? existingAction1 : existingAction2);
        when(workflowSpecService.getOne(any(Wrapper.class), eq(false))).thenReturn(existingWorkflow);
        AtomicInteger stepGetOneCount = new AtomicInteger();
        when(workflowStepService.getOne(any(Wrapper.class), eq(false))).thenAnswer(invocation ->
                stepGetOneCount.getAndIncrement() == 0 ? existingStep1 : existingStep2);
        when(interactionSpecService.updateById(any(InteractionSpec.class))).thenReturn(true);
        when(actionSpecService.updateById(any(ActionSpec.class))).thenReturn(true);
        when(workflowSpecService.updateById(any(WorkflowSpec.class))).thenReturn(true);
        when(workflowStepService.updateById(any(WorkflowStep.class))).thenReturn(true);

        LegacyCapabilityCompiler.CompilationResult result = compiler.compileAll("prod");

        assertEquals(1, result.scanned());
        assertEquals(0, result.inserted());
        assertEquals(1, result.updated());
        assertEquals(0, result.skipped());
        verify(interactionSpecService, times(1)).updateById(any(InteractionSpec.class));
        verify(actionSpecService, times(2)).updateById(any(ActionSpec.class));
        verify(workflowSpecService, times(1)).updateById(any(WorkflowSpec.class));
        verify(workflowStepService, times(2)).updateById(any(WorkflowStep.class));
    }

    private Map<String, Object> flowLegacyRow() {
        Map<String, Object> row = new HashMap<>();
        row.put("tenant_id", "default");
        row.put("system_code", "gougu_oa");
        row.put("capability_code", "leave_application");
        row.put("capability_name", "请假审批");
        row.put("capability_desc", "提交请假并发起审批");
        row.put("api_endpoint", "/home/leaves/add");
        row.put("http_method", "POST");
        row.put("content_type", "application/x-www-form-urlencoded");
        row.put("request_schema", "{\"type\":\"object\"}");
        row.put("response_config", "{\"next_step\":true}");
        row.put("slot_schema", "{\"slots\":[]}");
        row.put("behavior_strategy", "{\"confirm\":{\"enabled\":true}}");
        row.put("requires_auth", 1);
        row.put("risk_level", "HIGH");
        row.put("side_effect", "WRITE");
        row.put("capability_mode", "FLOW");
        row.put("version", 1);
        row.put("status", "enabled");
        row.put("flow_steps", "{\"entry\":[\"create_leave\"],\"steps\":{\"create_leave\":{\"name\":\"创建请假记录\",\"type\":\"HTTP\",\"next\":[\"submit_approval\"],\"config\":{\"method\":\"POST\",\"endpoint\":\"/home/leaves/add\",\"contentType\":\"application/x-www-form-urlencoded\",\"inputMapping\":{\"types\":\"${types}\"},\"outputMapping\":{\"leave_id\":\"$.data.return_id\"},\"successCondition\":\"$.code == 0\"}},\"submit_approval\":{\"name\":\"提交审批\",\"type\":\"HTTP\",\"config\":{\"method\":\"POST\",\"endpoint\":\"/api/check/submit_check\",\"contentType\":\"application/x-www-form-urlencoded\",\"inputMapping\":{\"action_id\":\"${create_leave.leave_id}\"},\"outputMapping\":{\"code\":\"$.code\"},\"successCondition\":\"$.code == 0\"}}},\"terminal\":[\"submit_approval\"]}");
        return row;
    }

}
