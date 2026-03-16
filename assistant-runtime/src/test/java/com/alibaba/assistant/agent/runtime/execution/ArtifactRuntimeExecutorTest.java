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
import com.alibaba.assistant.agent.controlplane.space.PlatformSpace;
import com.alibaba.assistant.agent.controlplane.space.PlatformSpaceService;
import com.alibaba.assistant.agent.runtime.agent.AssistantStateKeys;
import com.alibaba.assistant.agent.runtime.compiler.RuntimeArtifact;
import com.alibaba.assistant.agent.runtime.context.RuntimeSpaceResolver;
import com.alibaba.assistant.agent.runtime.registry.PublishedToolDescriptor;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.agent.tools.ToolContextConstants;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.model.ToolContext;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
                null,
                null,
                new ObjectMapper());
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

    @Test
    void shouldCarrySpaceIdFromStateContextIntoFlowInputs() {
        DAGFlowExecutor dagFlowExecutor = mock(DAGFlowExecutor.class);
        ArtifactRuntimeExecutor executor = new ArtifactRuntimeExecutor(
                dagFlowExecutor,
                null,
                null,
                null,
                null,
                new ObjectMapper());
        RuntimeArtifact artifact = runtimeArtifact(null, "oa.meeting_room_booking");
        PublishedToolDescriptor descriptor = PublishedToolDescriptor.forArtifact(
                "tool-meta-catalog",
                "workflow:oa.meeting_room_booking",
                "会议室预订",
                null,
                null,
                false,
                "gougu_oa",
                artifact);
        FlowExecutionResult flowExecutionResult = new FlowExecutionResult();
        flowExecutionResult.setSuccess(true);
        flowExecutionResult.setFinalOutputs(Map.of("meetingId", "M-1"));
        when(dagFlowExecutor.execute(same(artifact.getFlowDefinition()), any(FlowContext.class)))
                .thenReturn(flowExecutionResult);

        OverAllState state = new OverAllState();
        state.updateState(Map.of(
                AssistantStateKeys.SPACE_ID, 88L,
                AssistantStateKeys.ASSISTANT_UID, "u1",
                AssistantStateKeys.THREAD_ID, "T-1"));
        ToolContext toolContext = new ToolContext(Map.of(ToolContextConstants.AGENT_STATE_CONTEXT_KEY, state));

        executor.execute(descriptor, Map.of("title", "项目评审"), toolContext);

        ArgumentCaptor<FlowContext> flowContextCaptor = ArgumentCaptor.forClass(FlowContext.class);
        verify(dagFlowExecutor).execute(same(artifact.getFlowDefinition()), flowContextCaptor.capture());
        assertEquals(88L, ((Number) flowContextCaptor.getValue().getInitialInputs().get("space_id")).longValue());
        assertEquals(88L, ((Number) flowContextCaptor.getValue().getInitialInputs().get("spaceId")).longValue());
        assertEquals(88L, ((Number) flowContextCaptor.getValue().getInitialInputs().get(AssistantStateKeys.SPACE_ID)).longValue());
    }

    @Test
    void shouldResolveSpaceIdFromSpaceCodeInToolContext() {
        DAGFlowExecutor dagFlowExecutor = mock(DAGFlowExecutor.class);
        PlatformSpaceService platformSpaceService = mock(PlatformSpaceService.class);
        RuntimeSpaceResolver runtimeSpaceResolver = new RuntimeSpaceResolver(platformSpaceService, "prod");
        PlatformSpace platformSpace = new PlatformSpace();
        platformSpace.setId(66L);
        when(platformSpaceService.findActiveByCode("finance-space", "test")).thenReturn(Optional.of(platformSpace));
        ArtifactRuntimeExecutor executor = new ArtifactRuntimeExecutor(
                dagFlowExecutor,
                null,
                null,
                null,
                null,
                runtimeSpaceResolver,
                new ObjectMapper());
        RuntimeArtifact artifact = runtimeArtifact(null, "oa.meeting_room_booking");
        PublishedToolDescriptor descriptor = PublishedToolDescriptor.forArtifact(
                "tool-meta-catalog",
                "workflow:oa.meeting_room_booking",
                "会议室预订",
                null,
                null,
                false,
                "gougu_oa",
                artifact);
        FlowExecutionResult flowExecutionResult = new FlowExecutionResult();
        flowExecutionResult.setSuccess(true);
        when(dagFlowExecutor.execute(same(artifact.getFlowDefinition()), any(FlowContext.class)))
                .thenReturn(flowExecutionResult);

        OverAllState state = new OverAllState();
        state.updateState(Map.of(
                AssistantStateKeys.SPACE_CODE, "finance-space",
                AssistantStateKeys.SPACE_ENVIRONMENT, "test",
                AssistantStateKeys.ASSISTANT_UID, "u1",
                AssistantStateKeys.THREAD_ID, "T-1"));
        ToolContext toolContext = new ToolContext(Map.of(ToolContextConstants.AGENT_STATE_CONTEXT_KEY, state));

        executor.execute(descriptor, Map.of("title", "项目评审"), toolContext);

        ArgumentCaptor<FlowContext> flowContextCaptor = ArgumentCaptor.forClass(FlowContext.class);
        verify(dagFlowExecutor).execute(same(artifact.getFlowDefinition()), flowContextCaptor.capture());
        assertEquals(66L, ((Number) flowContextCaptor.getValue().getInitialInputs().get("space_id")).longValue());
        assertEquals("finance-space", flowContextCaptor.getValue().getInitialInputs().get(AssistantStateKeys.SPACE_CODE));
        assertEquals("test", flowContextCaptor.getValue().getInitialInputs().get(AssistantStateKeys.SPACE_ENVIRONMENT));
    }

    @Test
    void shouldFallbackToConfiguredDefaultSpaceWhenToolContextMissingSpaceContext() {
        DAGFlowExecutor dagFlowExecutor = mock(DAGFlowExecutor.class);
        PlatformSpaceService platformSpaceService = mock(PlatformSpaceService.class);
        PlatformSpace platformSpace = new PlatformSpace();
        platformSpace.setId(77L);
        platformSpace.setSpaceCode("default");
        when(platformSpaceService.resolveDefaultRuntimeSpace("prod")).thenReturn(Optional.of(platformSpace));
        RuntimeSpaceResolver runtimeSpaceResolver = new RuntimeSpaceResolver(platformSpaceService, "prod");
        ArtifactRuntimeExecutor executor = new ArtifactRuntimeExecutor(
                dagFlowExecutor,
                null,
                null,
                null,
                null,
                runtimeSpaceResolver,
                new ObjectMapper());
        RuntimeArtifact artifact = runtimeArtifact(null, "oa.meeting_room_booking");
        PublishedToolDescriptor descriptor = PublishedToolDescriptor.forArtifact(
                "tool-meta-catalog",
                "workflow:oa.meeting_room_booking",
                "会议室预订",
                null,
                null,
                false,
                "gougu_oa",
                artifact);
        FlowExecutionResult flowExecutionResult = new FlowExecutionResult();
        flowExecutionResult.setSuccess(true);
        when(dagFlowExecutor.execute(same(artifact.getFlowDefinition()), any(FlowContext.class)))
                .thenReturn(flowExecutionResult);

        executor.execute(descriptor, Map.of("title", "项目评审"), toolContext());

        ArgumentCaptor<FlowContext> flowContextCaptor = ArgumentCaptor.forClass(FlowContext.class);
        verify(dagFlowExecutor).execute(same(artifact.getFlowDefinition()), flowContextCaptor.capture());
        assertEquals(77L, ((Number) flowContextCaptor.getValue().getInitialInputs().get("space_id")).longValue());
        assertEquals("default", flowContextCaptor.getValue().getInitialInputs().get(AssistantStateKeys.SPACE_CODE));
        assertEquals("prod", flowContextCaptor.getValue().getInitialInputs().get(AssistantStateKeys.SPACE_ENVIRONMENT));
    }

    @Test
    void shouldPublishExecutionEventsToThreadStreamWhileExecuting() {
        DAGFlowExecutor dagFlowExecutor = mock(DAGFlowExecutor.class);
        ExecutionEventStreamRegistry eventStreamRegistry = new ExecutionEventStreamRegistry();
        ArtifactRuntimeExecutor executor = new ArtifactRuntimeExecutor(
                dagFlowExecutor,
                null,
                null,
                eventStreamRegistry,
                null,
                new ObjectMapper());
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
        flowExecutionResult.setFinalOutputs(Map.of("leaveId", "L-1"));
        when(dagFlowExecutor.execute(same(artifact.getFlowDefinition()), any(FlowContext.class)))
                .thenReturn(flowExecutionResult);

        ExecutionEventStreamRegistry.ExecutionEventSubscription subscription = eventStreamRegistry.open("T-1");
        CopyOnWriteArrayList<ExecutionEvent> streamedEvents = new CopyOnWriteArrayList<>();
        try {
            subscription.flux().subscribe(streamedEvents::add);
            executor.execute(descriptor, Map.of("reason", "事假"), toolContext());
        }
        finally {
            subscription.close();
        }

        assertTrue(streamedEvents.stream().anyMatch(event -> event.eventType() == ExecutionEventType.RUN_STARTED));
        assertTrue(streamedEvents.stream().anyMatch(event -> event.eventType() == ExecutionEventType.RUN_COMPLETED));
    }

    private RuntimeArtifact runtimeArtifact(String artifactCode) {
        return runtimeArtifact(1L, artifactCode);
    }

    private RuntimeArtifact runtimeArtifact(Long spaceId, String artifactCode) {
        FlowDefinition flowDefinition = new FlowDefinition();
        flowDefinition.setVersion("2.0");
        flowDefinition.setEntry(List.of("execute"));
        flowDefinition.setTerminal(List.of("execute"));
        return new RuntimeArtifact(
                spaceId,
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

