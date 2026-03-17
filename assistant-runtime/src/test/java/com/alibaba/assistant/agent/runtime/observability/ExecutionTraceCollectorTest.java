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
package com.alibaba.assistant.agent.runtime.observability;

import com.alibaba.assistant.agent.execution.flow.FlowContext;
import com.alibaba.assistant.agent.execution.flow.FlowDefinition;
import com.alibaba.assistant.agent.execution.flow.FlowExecutionResult;
import com.alibaba.assistant.agent.execution.model.StepResult;
import com.alibaba.assistant.agent.execution.model.StepStatus;
import com.alibaba.assistant.agent.execution.persistence.ExecutionSpan;
import com.alibaba.assistant.agent.execution.persistence.ExecutionSpanService;
import com.alibaba.assistant.agent.execution.persistence.ExecutionTrace;
import com.alibaba.assistant.agent.execution.persistence.ExecutionTraceService;
import com.alibaba.assistant.agent.runtime.agent.AssistantStateKeys;
import com.alibaba.assistant.agent.runtime.compiler.RuntimeArtifact;
import com.alibaba.assistant.agent.runtime.execution.ExecutionEvent;
import com.alibaba.assistant.agent.runtime.execution.ExecutionEventType;
import com.alibaba.assistant.agent.runtime.execution.ExecutionLifecycleStatus;
import com.alibaba.assistant.agent.runtime.registry.PublishedToolDescriptor;
import io.micrometer.common.KeyValue;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ExecutionTraceCollectorTest {

    @Test
    void shouldPersistTraceAndSpanOutsideAuditTable() {
        ExecutionTraceService executionTraceService = mock(ExecutionTraceService.class);
        ExecutionSpanService executionSpanService = mock(ExecutionSpanService.class);
        ObservationRegistry observationRegistry = ObservationRegistry.create();
        ExecutionTraceCollector collector = new ExecutionTraceCollector(
                executionTraceService,
                executionSpanService,
                observationRegistry,
                new CostAccountant());
        when(executionTraceService.findLatestByRunId("RUN-1")).thenReturn(Optional.empty());

        collector.collect(descriptor(), flowContext(), successfulResult(), successfulEvents());

        ArgumentCaptor<ExecutionTrace> traceCaptor = ArgumentCaptor.forClass(ExecutionTrace.class);
        verify(executionTraceService).save(traceCaptor.capture());
        ExecutionTrace trace = traceCaptor.getValue();
        assertEquals("RUN-1", trace.getRunId());
        assertEquals("admin-agent", trace.getAgentAppCode());
        assertEquals("digital-admin", trace.getRolePackageCode());
        assertEquals("meeting_coordination", trace.getScenarioCode());
        assertEquals("oa.leave.apply", trace.getArtifactCode());
        assertEquals("COMPLETED", trace.getStatus());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<ExecutionSpan>> spanCaptor = ArgumentCaptor.forClass((Class<Collection<ExecutionSpan>>) (Class<?>) Collection.class);
        verify(executionSpanService).saveBatch(spanCaptor.capture());
        List<ExecutionSpan> spans = List.copyOf(spanCaptor.getValue());
        assertEquals(2, spans.size());
        assertEquals("create_leave", spans.get(0).getStepId());
        assertEquals("submit_approval", spans.get(1).getStepId());
        assertEquals("COMPLETED", spans.get(1).getStatus());
    }

    @Test
    void shouldPublishObservationWithRoleScenarioTags() {
        ExecutionTraceService executionTraceService = mock(ExecutionTraceService.class);
        ExecutionSpanService executionSpanService = mock(ExecutionSpanService.class);
        ObservationRegistry observationRegistry = ObservationRegistry.create();
        CapturingObservationHandler handler = new CapturingObservationHandler();
        observationRegistry.observationConfig().observationHandler(handler);
        ExecutionTraceCollector collector = new ExecutionTraceCollector(
                executionTraceService,
                executionSpanService,
                observationRegistry,
                new CostAccountant());
        when(executionTraceService.findLatestByRunId("RUN-1")).thenReturn(Optional.empty());

        collector.collect(descriptor(), flowContext(), successfulResult(), successfulEvents());

        assertNotNull(handler.lastContext);
        assertEquals("assistant.execution.trace", handler.lastContext.getName());
        assertTrue(handler.lowCardinalityKeyValues.contains(KeyValue.of("role.package.code", "digital-admin")));
        assertTrue(handler.lowCardinalityKeyValues.contains(KeyValue.of("role.scenario.code", "meeting_coordination")));
        assertTrue(handler.lowCardinalityKeyValues.contains(KeyValue.of("artifact.code", "oa.leave.apply")));
        assertTrue(handler.highCardinalityKeyValues.contains(KeyValue.of("run.id", "RUN-1")));
    }

    private PublishedToolDescriptor descriptor() {
        RuntimeArtifact.ActionBinding action = new RuntimeArtifact.ActionBinding(
                1L,
                "oa.leave.create",
                21L,
                null,
                "[\"oa.default\"]",
                "oa.default",
                null,
                null,
                null,
                "medium",
                null,
                "write",
                1);
        RuntimeArtifact.StepBinding createLeave = new RuntimeArtifact.StepBinding(
                "create_leave",
                "创建请假记录",
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
                null,
                1,
                action);
        RuntimeArtifact.StepBinding submitApproval = new RuntimeArtifact.StepBinding(
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
                null,
                2,
                action);
        FlowDefinition flowDefinition = new FlowDefinition();
        flowDefinition.setEntry(List.of("create_leave"));
        flowDefinition.setTerminal(List.of("submit_approval"));
        RuntimeArtifact artifact = new RuntimeArtifact(
                11L,
                "oa.leave.apply",
                RuntimeArtifact.ArtifactType.WORKFLOW,
                "OA请假",
                1,
                null,
                null,
                null,
                null,
                null,
                flowDefinition,
                Map.of("oa.leave.create", action),
                Map.of(
                        "create_leave", createLeave,
                        "submit_approval", submitApproval));
        return PublishedToolDescriptor.forArtifact(
                "tool-meta-catalog",
                "oa.leave.apply",
                "OA请假",
                "LeaveApplyArtifact",
                "leave artifact",
                false,
                "gougu_oa",
                artifact);
    }

    private FlowContext flowContext() {
        Map<String, Object> initialInputs = new LinkedHashMap<>();
        initialInputs.put(AssistantStateKeys.SPACE_ID, 11L);
        initialInputs.put(AssistantStateKeys.AGENT_APP_CODE, "admin-agent");
        initialInputs.put(AssistantStateKeys.ROLE_PACKAGE_CODE, "digital-admin");
        initialInputs.put(AssistantStateKeys.ROLE_PACKAGE_VERSION, "v1");
        initialInputs.put(AssistantStateKeys.ROLE_SCENARIO_CODE, "meeting_coordination");
        initialInputs.put(AssistantStateKeys.PLATFORM_PRINCIPAL_ID, "u1001");
        FlowContext context = new FlowContext(initialInputs);
        context.setRunId("RUN-1");
        context.setThreadId("THREAD-1");
        context.setAssistantUid("u1001");
        context.setSystemCode("gougu_oa");
        return context;
    }

    private FlowExecutionResult successfulResult() {
        FlowExecutionResult result = new FlowExecutionResult();
        result.setSuccess(true);
        result.setLifecycleStatus("COMPLETED");
        result.setDurationMs(5000);
        result.setStepStatuses(new LinkedHashMap<>(Map.of(
                "create_leave", StepStatus.COMPLETED,
                "submit_approval", StepStatus.COMPLETED)));
        result.setStepResults(new LinkedHashMap<>(Map.of(
                "create_leave", StepResult.success(Map.of("leaveId", "L-1")),
                "submit_approval", StepResult.success(Map.of("approvalId", "A-1")))));
        result.setFinalOutputs(Map.of("approvalId", "A-1"));
        return result;
    }

    private List<ExecutionEvent> successfulEvents() {
        Instant base = Instant.parse("2026-03-17T10:00:00Z");
        return List.of(
                new ExecutionEvent("RUN-1", "oa.leave.apply", "WORKFLOW", null, 1L,
                        ExecutionEventType.RUN_STARTED, ExecutionLifecycleStatus.RUNNING, base, Map.of()),
                new ExecutionEvent("RUN-1", "oa.leave.apply", "WORKFLOW", "create_leave", 2L,
                        ExecutionEventType.STEP_STARTED, ExecutionLifecycleStatus.RUNNING, base.plusSeconds(1),
                        Map.of("stepName", "创建请假记录")),
                new ExecutionEvent("RUN-1", "oa.leave.apply", "WORKFLOW", "create_leave", 3L,
                        ExecutionEventType.STEP_COMPLETED, ExecutionLifecycleStatus.COMPLETED, base.plusSeconds(2),
                        Map.of("stepName", "创建请假记录")),
                new ExecutionEvent("RUN-1", "oa.leave.apply", "WORKFLOW", "submit_approval", 4L,
                        ExecutionEventType.STEP_STARTED, ExecutionLifecycleStatus.RUNNING, base.plusSeconds(3),
                        Map.of("stepName", "提交审批")),
                new ExecutionEvent("RUN-1", "oa.leave.apply", "WORKFLOW", "submit_approval", 5L,
                        ExecutionEventType.STEP_COMPLETED, ExecutionLifecycleStatus.COMPLETED, base.plusSeconds(4),
                        Map.of("stepName", "提交审批")),
                new ExecutionEvent("RUN-1", "oa.leave.apply", "WORKFLOW", null, 6L,
                        ExecutionEventType.RUN_COMPLETED, ExecutionLifecycleStatus.COMPLETED, base.plusSeconds(5),
                        Map.of("finalOutputs", Map.of("approvalId", "A-1"))));
    }

    private static final class CapturingObservationHandler implements ObservationHandler<Observation.Context> {

        private Observation.Context lastContext;

        private List<KeyValue> lowCardinalityKeyValues = List.of();

        private List<KeyValue> highCardinalityKeyValues = List.of();

        @Override
        public void onStop(Observation.Context context) {
            this.lastContext = context;
            this.lowCardinalityKeyValues = context.getLowCardinalityKeyValues().stream().toList();
            this.highCardinalityKeyValues = context.getHighCardinalityKeyValues().stream().toList();
        }

        @Override
        public boolean supportsContext(Observation.Context context) {
            return true;
        }
    }
}
