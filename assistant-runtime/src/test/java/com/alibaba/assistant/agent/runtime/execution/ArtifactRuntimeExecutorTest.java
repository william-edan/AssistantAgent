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
import com.alibaba.assistant.agent.runtime.compiler.RuntimeArtifact;
import com.alibaba.assistant.agent.runtime.registry.PublishedToolDescriptor;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.model.ToolContext;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ArtifactRuntimeExecutorTest {

    @Test
    void shouldExecuteRuntimeArtifactFlowWithPublishedSystemCode() {
        DAGFlowExecutor dagFlowExecutor = mock(DAGFlowExecutor.class);
        ExecutionRuntimePersistenceRecorder persistenceRecorder = mock(ExecutionRuntimePersistenceRecorder.class);
        ArtifactRuntimeExecutor executor = new ArtifactRuntimeExecutor(
                dagFlowExecutor,
                null,
                persistenceRecorder,
                new ObjectMapper());
        RuntimeArtifact artifact = runtimeArtifact("oa.leave.apply");
        PublishedToolDescriptor descriptor = PublishedToolDescriptor.forArtifact(
                "artifact-catalog",
                "workflow:oa.leave.apply",
                "请假申请",
                null,
                null,
                false,
                "gougu_oa",
                artifact);

        FlowExecutionResult flowExecutionResult = new FlowExecutionResult();
        flowExecutionResult.setSuccess(true);
        flowExecutionResult.setFinalOutputs(Map.of("leaveId", "L-1"));
        when(dagFlowExecutor.execute(same(artifact.getFlowDefinition()), any(FlowContext.class)))
                .thenReturn(flowExecutionResult);

        Map<String, Object> payload = executor.execute(descriptor, Map.of("reason", "事假"), toolContext());

        ArgumentCaptor<FlowContext> flowContextCaptor = ArgumentCaptor.forClass(FlowContext.class);
        verify(dagFlowExecutor).execute(same(artifact.getFlowDefinition()), flowContextCaptor.capture());
        verify(persistenceRecorder).record(same(descriptor), any(FlowContext.class), same(flowExecutionResult), anyList());
        assertEquals("gougu_oa", flowContextCaptor.getValue().getSystemCode());
        assertEquals("u1", flowContextCaptor.getValue().getAssistantUid());
        assertEquals("T-1", flowContextCaptor.getValue().getThreadId());
        assertEquals("oa.leave.apply", payload.get("artifactCode"));
        assertEquals(Map.of("leaveId", "L-1"), payload.get("finalOutputs"));
    }

    private RuntimeArtifact runtimeArtifact(String artifactCode) {
        FlowDefinition flowDefinition = new FlowDefinition();
        flowDefinition.setVersion("2.0");
        flowDefinition.setEntry(List.of("execute"));
        flowDefinition.setTerminal(List.of("execute"));
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
                Map.of(),
                Map.of());
    }

    private ToolContext toolContext() {
        return new ToolContext(Map.of(
                "assistant_uid", "u1",
                "thread_id", "T-1"));
    }
}
