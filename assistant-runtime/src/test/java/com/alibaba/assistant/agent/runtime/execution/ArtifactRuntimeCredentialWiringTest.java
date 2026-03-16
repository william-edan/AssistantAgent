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
import com.alibaba.assistant.agent.execution.model.StepDefinition;
import com.alibaba.assistant.agent.execution.model.StepType;
import com.alibaba.assistant.agent.runtime.compiler.RuntimeArtifact;
import com.alibaba.assistant.agent.runtime.registry.PublishedToolDescriptor;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ArtifactRuntimeCredentialWiringTest {

    @Test
    void shouldResolveStepCredentialLeaseBeforeExecutingFlow() {
        DAGFlowExecutor dagFlowExecutor = mock(DAGFlowExecutor.class);
        CredentialBroker credentialBroker = mock(CredentialBroker.class);
        ArtifactRuntimeExecutor executor = new ArtifactRuntimeExecutor(dagFlowExecutor, credentialBroker, new ObjectMapper());
        RuntimeArtifact artifact = runtimeArtifact("oa.leave.apply");
        PublishedToolDescriptor descriptor = PublishedToolDescriptor.forArtifact(
                "tool-meta-catalog",
                "workflow:oa.leave.apply",
                "请假申请",
                null,
                null,
                false,
                "gougu_oa",
                artifact);

        FlowExecutionResult flowExecutionResult = new FlowExecutionResult();
        flowExecutionResult.setSuccess(true);
        when(dagFlowExecutor.execute(same(artifact.getFlowDefinition()), any(FlowContext.class))).thenReturn(flowExecutionResult);
        when(credentialBroker.resolve(any())).thenReturn(new ResolvedCredentialLease(
                "lease-1",
                "oa-user",
                21L,
                10L,
                "BEARER",
                Map.of("Authorization", "Bearer token-123"),
                Instant.now().plusSeconds(600), "http://oa.internal"));

        executor.execute(descriptor, Map.of("reason", "事假", "assistant_uid", "1001"), null);

        ArgumentCaptor<CredentialResolutionRequest> requestCaptor = ArgumentCaptor.forClass(CredentialResolutionRequest.class);
        verify(credentialBroker).resolve(requestCaptor.capture());
        assertEquals(1L, requestCaptor.getValue().spaceId());
        assertEquals(10L, requestCaptor.getValue().connectorId());
        assertEquals(List.of("oa-user"), requestCaptor.getValue().candidateAuthProfileCodes());

        ArgumentCaptor<FlowContext> flowContextCaptor = ArgumentCaptor.forClass(FlowContext.class);
        verify(dagFlowExecutor).execute(same(artifact.getFlowDefinition()), flowContextCaptor.capture());
        assertEquals("gougu_oa", flowContextCaptor.getValue().getSystemCode());
        assertEquals("http://oa.internal", flowContextCaptor.getValue().getStepBaseUrl("submit_approval"));
        assertEquals("Bearer token-123",
                flowContextCaptor.getValue().getStepRequestHeaders("submit_approval").get("Authorization"));
        assertNotNull(flowContextCaptor.getValue().getRunId());
    }

    private RuntimeArtifact runtimeArtifact(String artifactCode) {
        FlowDefinition flowDefinition = new FlowDefinition();
        flowDefinition.setVersion("2.0");
        flowDefinition.setEntry(List.of("submit_approval"));
        flowDefinition.setTerminal(List.of("submit_approval"));
        StepDefinition stepDefinition = new StepDefinition();
        stepDefinition.setStepId("submit_approval");
        stepDefinition.setName("提交审批");
        stepDefinition.setType(StepType.HTTP);
        flowDefinition.setSteps(Map.of("submit_approval", stepDefinition));

        RuntimeArtifact.ActionBinding actionBinding = new RuntimeArtifact.ActionBinding(
                11L,
                "oa.approval.submit",
                10L,
                null,
                "[\"oa-user\"]",
                "oa-user",
                null,
                null,
                null,
                "HIGH",
                null,
                "WRITE",
                1
        );
        RuntimeArtifact.StepBinding stepBinding = new RuntimeArtifact.StepBinding(
                "submit_approval",
                "提交审批",
                "HTTP",
                10L,
                "oa.approval.submit",
                "[\"oa-user\"]",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                1,
                actionBinding
        );
        return new RuntimeArtifact(
                1L,
                artifactCode,
                RuntimeArtifact.ArtifactType.WORKFLOW,
                "请假申请",
                1,
                null,
                null,
                null,
                null,
                null,
                flowDefinition,
                new LinkedHashMap<>(Map.of("oa.approval.submit", actionBinding)),
                new LinkedHashMap<>(Map.of("submit_approval", stepBinding)));
    }
}



