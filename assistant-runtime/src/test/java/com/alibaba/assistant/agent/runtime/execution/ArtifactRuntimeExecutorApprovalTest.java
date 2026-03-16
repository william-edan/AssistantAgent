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
package com.alibaba.assistant.agent.runtime.execution;

import com.alibaba.assistant.agent.execution.flow.DAGFlowExecutor;
import com.alibaba.assistant.agent.execution.flow.FlowContext;
import com.alibaba.assistant.agent.execution.flow.FlowDefinition;
import com.alibaba.assistant.agent.execution.flow.FlowExecutionResult;
import com.alibaba.assistant.agent.execution.model.StepResult;
import com.alibaba.assistant.agent.execution.model.StepStatus;
import com.alibaba.assistant.agent.runtime.compiler.RuntimeArtifact;
import com.alibaba.assistant.agent.runtime.registry.PublishedToolDescriptor;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ArtifactRuntimeExecutorApprovalTest {

    @Test
    void shouldExposeApprovalRequestIdWhenExecutionWaitsForApproval() {
        DAGFlowExecutor dagFlowExecutor = mock(DAGFlowExecutor.class);
        ArtifactRuntimeExecutor executor = new ArtifactRuntimeExecutor(
                dagFlowExecutor,
                null,
                null,
                new ObjectMapper());
        PublishedToolDescriptor descriptor = descriptor();
        FlowExecutionResult waitingResult = new FlowExecutionResult();
        waitingResult.setSuccess(false);
        waitingResult.setLifecycleStatus("WAITING_APPROVAL");
        waitingResult.setPausedStepId("submit_approval");
        waitingResult.setStepStatuses(Map.of("submit_approval", StepStatus.WAITING_APPROVAL));
        StepResult waitingStep = new StepResult();
        waitingStep.setSuccess(false);
        waitingStep.setOutputs(Map.of("waitingApproval", true));
        waitingResult.setStepResults(Map.of("submit_approval", waitingStep));
        when(dagFlowExecutor.execute(same(descriptor.artifact().getFlowDefinition()), any(FlowContext.class)))
                .thenReturn(waitingResult);

        Map<String, Object> payload = executor.execute(descriptor, Map.of("reason", "事假"), toolContext());

        assertEquals("WAITING_APPROVAL", payload.get("lifecycleStatus"));
        assertTrue(String.valueOf(payload.get("approvalRequestId")).endsWith(":submit_approval"));
        assertEquals(payload.get("approvalRequestId"), waitingResult.getApprovalRequestId());
        assertTrue(((List<?>) payload.get("executionEvents")).stream()
                .map(ExecutionEvent.class::cast)
                .anyMatch(event -> event.eventType() == ExecutionEventType.STEP_WAITING_APPROVAL));
    }

    private PublishedToolDescriptor descriptor() {
        FlowDefinition flowDefinition = new FlowDefinition();
        flowDefinition.setVersion("2.0");
        flowDefinition.setEntry(List.of("submit_approval"));
        flowDefinition.setTerminal(List.of("submit_approval"));
        RuntimeArtifact.StepBinding stepBinding = new RuntimeArtifact.StepBinding(
                "submit_approval",
                "提交审批",
                "HTTP",
                21L,
                null,
                "[\"oa.default\"]",
                null,
                null,
                null,
                null,
                null,
                null,
                "{\"enabled\":true,\"channel\":\"controlplane\"}",
                1,
                null
        );
        RuntimeArtifact artifact = new RuntimeArtifact(
                1L,
                "oa.leave.apply",
                RuntimeArtifact.ArtifactType.WORKFLOW,
                "请假申请",
                1,
                null,
                null,
                null,
                null,
                null,
                flowDefinition,
                Map.of(),
                Map.of("submit_approval", stepBinding));
        return PublishedToolDescriptor.forArtifact(
                "tool-meta-catalog",
                "workflow:oa.leave.apply",
                "请假申请",
                null,
                null,
                false,
                "gougu_oa",
                artifact);
    }

    private ToolContext toolContext() {
        return new ToolContext(Map.of(
                "assistant_uid", "u1",
                "thread_id", "T-1"));
    }
}

