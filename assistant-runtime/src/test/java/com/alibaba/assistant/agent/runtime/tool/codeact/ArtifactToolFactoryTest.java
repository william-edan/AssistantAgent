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
package com.alibaba.assistant.agent.runtime.tool.codeact;

import com.alibaba.assistant.agent.common.tools.CodeactTool;
import com.alibaba.assistant.agent.execution.flow.FlowDefinition;
import com.alibaba.assistant.agent.runtime.compiler.RuntimeArtifact;
import com.alibaba.assistant.agent.runtime.execution.ArtifactRuntimeExecutor;
import com.alibaba.assistant.agent.runtime.registry.PublishedToolDescriptor;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class ArtifactToolFactoryTest {

    @Test
    void shouldCreateArtifactBackedToolsFromWorkflowAndActionArtifacts() {
        ArtifactRuntimeExecutor runtimeExecutor = mock(ArtifactRuntimeExecutor.class);
        ArtifactToolFactory factory = new ArtifactToolFactory(new ObjectMapper(), runtimeExecutor);
        RuntimeArtifact workflowArtifact = workflowArtifact("oa.leave.apply");
        RuntimeArtifact actionArtifact = actionArtifact("oa.leave.create");

        List<CodeactTool> tools = factory.createTools(List.of(
                descriptor("workflow:oa.leave.apply", "请假申请", workflowArtifact),
                descriptor("action:oa.leave.create", "创建请假单", actionArtifact)));

        assertEquals(2, tools.size());
        assertEquals("oa_leave_apply_execute", tools.get(0).getToolDefinition().name());
        assertEquals("oa_leave_create_execute", tools.get(1).getToolDefinition().name());
        assertEquals("oa_tools", tools.get(0).getCodeactMetadata().targetClassName());
        assertEquals("OA workflow tools", tools.get(0).getCodeactMetadata().targetClassDescription());
        assertTrue(tools.get(0).getToolDefinition().inputSchema().contains("reason"));
        assertTrue(tools.get(1).getToolDefinition().inputSchema().contains("reason"));

        ArtifactBackedCodeactTool workflowTool = assertInstanceOf(ArtifactBackedCodeactTool.class, tools.get(0));
        ArtifactBackedCodeactTool actionTool = assertInstanceOf(ArtifactBackedCodeactTool.class, tools.get(1));
        assertSame(workflowArtifact, workflowTool.getPublishedDescriptor().artifact());
        assertSame(actionArtifact, actionTool.getPublishedDescriptor().artifact());
    }

    @Test
    void shouldGenerateCollisionSafeToolNamesDeterministically() {
        ArtifactRuntimeExecutor runtimeExecutor = mock(ArtifactRuntimeExecutor.class);
        ArtifactToolFactory factory = new ArtifactToolFactory(new ObjectMapper(), runtimeExecutor);
        RuntimeArtifact firstArtifact = actionArtifact("oa.leave.apply");
        RuntimeArtifact secondArtifact = actionArtifact("oa_leave_apply");

        List<CodeactTool> tools = factory.createTools(List.of(
                descriptor("action:oa.leave.apply", "工具一", firstArtifact),
                descriptor("action:oa_leave_apply", "工具二", secondArtifact)));

        assertEquals("oa_leave_apply_execute", tools.get(0).getToolDefinition().name());
        assertEquals("oa_leave_apply_execute_2", tools.get(1).getToolDefinition().name());
    }

    private PublishedToolDescriptor descriptor(String publicationKey, String displayName, RuntimeArtifact artifact) {
        return new PublishedToolDescriptor(
                "artifact-catalog",
                publicationKey,
                displayName,
                "oa_tools",
                "OA workflow tools",
                false,
                "gougu_oa",
                artifact);
    }

    private RuntimeArtifact workflowArtifact(String artifactCode) {
        RuntimeArtifact.Interaction interaction = new RuntimeArtifact.Interaction(
                11L,
                artifactCode + ".interaction",
                "{\"slots\":[{\"name\":\"reason\",\"type\":\"string\",\"title\":\"原因\",\"required\":true}]}",
                "{\"collect\":{\"mode\":\"batch\"}}",
                null,
                null,
                "{\"enabled\":true}",
                null);
        RuntimeArtifact.ActionBinding action = new RuntimeArtifact.ActionBinding(
                21L,
                artifactCode + ".create",
                101L,
                "{\"method\":\"POST\",\"endpoint\":\"/home/leaves/add\",\"contentType\":\"application/json\"}",
                "[\"oa_user\"]",
                "oa_user",
                "[\"USER_MAPPED\"]",
                "{\"type\":\"object\",\"properties\":{\"reason\":{\"type\":\"string\"}}}",
                "{\"type\":\"object\"}",
                null,
                "medium",
                null,
                "write",
                null,
                1);
        RuntimeArtifact.StepBinding step = new RuntimeArtifact.StepBinding(
                "create_leave",
                "创建请假",
                "HTTP",
                101L,
                action.actionCode(),
                "[\"oa_user\"]",
                "[\"USER_MAPPED\"]",
                "{\"reason\":\"${reason}\"}",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                1,
                action);
        FlowDefinition flowDefinition = new FlowDefinition();
        flowDefinition.setVersion("2.0");
        flowDefinition.setSteps(Map.of());
        flowDefinition.setEntry(List.of("create_leave"));
        flowDefinition.setTerminal(List.of("create_leave"));
        return new RuntimeArtifact(
                1L,
                artifactCode,
                RuntimeArtifact.ArtifactType.WORKFLOW,
                artifactCode,
                1,
                "max_step_risk",
                "strictest_step_policy",
                null,
                null,
                interaction,
                flowDefinition,
                Map.of(action.actionCode(), action),
                Map.of(step.stepId(), step));
    }

    private RuntimeArtifact actionArtifact(String artifactCode) {
        RuntimeArtifact.ActionBinding action = new RuntimeArtifact.ActionBinding(
                31L,
                artifactCode,
                101L,
                "{\"method\":\"POST\",\"endpoint\":\"/home/leaves/add\",\"contentType\":\"application/json\"}",
                "[\"oa_user\"]",
                "oa_user",
                "[\"USER_MAPPED\"]",
                "{\"type\":\"object\",\"properties\":{\"reason\":{\"type\":\"string\"}}}",
                "{\"type\":\"object\"}",
                null,
                "medium",
                null,
                "write",
                null,
                1);
        FlowDefinition flowDefinition = new FlowDefinition();
        flowDefinition.setVersion("2.0");
        flowDefinition.setSteps(Map.of());
        flowDefinition.setEntry(List.of("execute"));
        flowDefinition.setTerminal(List.of("execute"));
        return new RuntimeArtifact(
                1L,
                artifactCode,
                RuntimeArtifact.ArtifactType.ACTION,
                artifactCode,
                1,
                null,
                null,
                null,
                null,
                null,
                flowDefinition,
                Map.of(action.actionCode(), action),
                Map.of());
    }

}
