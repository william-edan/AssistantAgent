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
package com.alibaba.assistant.agent.execution.flow;

import com.alibaba.assistant.agent.controlplane.identity.TokenBroker;
import com.alibaba.assistant.agent.controlplane.identity.TokenLease;
import com.alibaba.assistant.agent.controlplane.toolregistry.ToolMeta;
import com.alibaba.assistant.agent.execution.model.JoinType;
import com.alibaba.assistant.agent.execution.model.StepConfig;
import com.alibaba.assistant.agent.execution.model.StepDefinition;
import com.alibaba.assistant.agent.execution.model.StepResult;
import com.alibaba.assistant.agent.execution.model.StepStatus;
import com.alibaba.assistant.agent.execution.model.StepType;
import com.alibaba.assistant.agent.execution.step.HttpStepExecutor;
import com.alibaba.assistant.agent.execution.step.http.RequestBodySerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class DAGFlowExecutorTest {

    private static final String TEST_SYSTEM_CODE = "gougu_oa";
    private static final String TEST_ASSISTANT_UID = "assistant-1001";

    private MockWebServer mockWebServer;
    private DAGFlowExecutor dagFlowExecutor;
    private FlowDefinitionConverter flowDefinitionConverter;

    @BeforeEach
    void setUp() throws IOException {
        mockWebServer = new MockWebServer();
        mockWebServer.start();

        ObjectMapper objectMapper = new ObjectMapper();
        RequestBodySerializer requestBodySerializer = new RequestBodySerializer();
        HttpStepExecutor.SystemAccessProfilePort profilePort =
                new StaticSystemAccessProfilePort("http://localhost:" + mockWebServer.getPort());
        TokenBroker tokenBroker = new StaticTokenBroker();

        HttpStepExecutor httpStepExecutor =
                new HttpStepExecutor(objectMapper, requestBodySerializer, profilePort, tokenBroker);
        dagFlowExecutor = new DAGFlowExecutor(httpStepExecutor);
        flowDefinitionConverter = new FlowDefinitionConverter(objectMapper);
    }

    @AfterEach
    void tearDown() throws IOException {
        if (mockWebServer != null) {
            mockWebServer.shutdown();
        }
    }

    @Test
    void shouldExecuteLinearDagSuccessPathWithRealFlowStepsJson() {
        mockWebServer.enqueue(jsonResponse("{\"code\":0,\"msg\":\"success\",\"data\":{\"return_id\":12345}}"));
        mockWebServer.enqueue(jsonResponse("{\"code\":0,\"msg\":\"审批提交成功\"}"));

        FlowExecutionResult result = dagFlowExecutor.execute(loadFlowDefinition(), createFlowContext(baseInputs()));

        assertTrue(result.isSuccess());
        assertEquals(StepStatus.COMPLETED, result.getStepStatuses().get("create_leave"));
        assertEquals(StepStatus.COMPLETED, result.getStepStatuses().get("submit_approval"));
        assertEquals(2, mockWebServer.getRequestCount());
    }

    @Test
    void shouldStopAtFirstFailedStepAndSkipSecondStep() {
        mockWebServer.enqueue(jsonResponse("{\"code\":500,\"msg\":\"create failed\"}"));
        mockWebServer.enqueue(jsonResponse("{\"code\":0,\"msg\":\"should not be called\"}"));

        FlowExecutionResult result = dagFlowExecutor.execute(loadFlowDefinition(), createFlowContext(baseInputs()));

        assertFalse(result.isSuccess());
        assertEquals(StepStatus.FAILED, result.getStepStatuses().get("create_leave"));
        assertFalse(result.getStepStatuses().containsKey("submit_approval"));
        assertEquals(1, mockWebServer.getRequestCount());
    }

    @Test
    void shouldReplaceCrossStepVariableInSecondRequestBody() throws Exception {
        mockWebServer.enqueue(jsonResponse("{\"code\":0,\"msg\":\"success\",\"data\":{\"return_id\":67890}}"));
        mockWebServer.enqueue(jsonResponse("{\"code\":0,\"msg\":\"审批提交成功\"}"));

        FlowExecutionResult result = dagFlowExecutor.execute(loadFlowDefinition(), createFlowContext(baseInputs()));
        assertTrue(result.isSuccess());

        RecordedRequest first = mockWebServer.takeRequest(1, TimeUnit.SECONDS);
        RecordedRequest second = mockWebServer.takeRequest(1, TimeUnit.SECONDS);

        assertNotNull(first);
        assertNotNull(second);
        assertEquals("/home/leaves/add", first.getPath());
        assertEquals("/api/check/submit_check", second.getPath());
        assertTrue(second.getBody().readUtf8().contains("action_id=67890"));
    }

    @Test
    void shouldAppendAjaxFlagForFormUrlEncodedRequests() throws Exception {
        mockWebServer.enqueue(jsonResponse("{\"code\":0,\"msg\":\"success\",\"data\":{\"return_id\":12345}}"));
        mockWebServer.enqueue(jsonResponse("{\"code\":0,\"msg\":\"审批提交成功\"}"));

        FlowExecutionResult result = dagFlowExecutor.execute(loadFlowDefinition(), createFlowContext(baseInputs()));
        assertTrue(result.isSuccess());

        RecordedRequest first = mockWebServer.takeRequest(1, TimeUnit.SECONDS);
        assertNotNull(first);
        assertEquals("POST", first.getMethod());
        assertTrue(first.getBody().readUtf8().contains("_ajax=1"));
    }

    @Test
    void shouldNotifyExecutionListenersWithOrderedStepLifecycle() {
        mockWebServer.enqueue(jsonResponse("{\"code\":0,\"msg\":\"success\",\"data\":{\"return_id\":12345}}"));
        mockWebServer.enqueue(jsonResponse("{\"code\":0,\"msg\":\"审批提交成功\"}"));

        FlowContext context = createFlowContext(baseInputs());
        List<String> lifecycleEvents = new ArrayList<>();
        context.addExecutionListener(new FlowExecutionListener() {
            @Override
            public void onStepStarted(com.alibaba.assistant.agent.execution.model.StepDefinition step, FlowContext flowContext) {
                lifecycleEvents.add("start:" + step.getStepId());
            }

            @Override
            public void onStepCompleted(
                    com.alibaba.assistant.agent.execution.model.StepDefinition step,
                    StepResult result,
                    FlowContext flowContext) {
                lifecycleEvents.add("complete:" + step.getStepId());
            }
        });

        FlowExecutionResult result = dagFlowExecutor.execute(loadFlowDefinition(), context);

        assertTrue(result.isSuccess());
        assertEquals(List.of(
                "start:create_leave",
                "complete:create_leave",
                "start:submit_approval",
                "complete:submit_approval"), lifecycleEvents);
    }

    @Test
    void shouldRespectDependsOnOrderEvenWhenEntryOrderDiffers() throws Exception {
        mockWebServer.enqueue(jsonResponse("{\"code\":0,\"msg\":\"create\",\"data\":{\"id\":1}}"));
        mockWebServer.enqueue(jsonResponse("{\"code\":0,\"msg\":\"review\",\"data\":{}}"));
        mockWebServer.enqueue(jsonResponse("{\"code\":0,\"msg\":\"notify\",\"data\":{}}"));

        FlowExecutionResult result = dagFlowExecutor.execute(buildDependencyFlow(), createFlowContext(baseInputs()));

        assertTrue(result.isSuccess());
        assertEquals(StepStatus.COMPLETED, result.getStepStatuses().get("create"));
        assertEquals(StepStatus.COMPLETED, result.getStepStatuses().get("review"));
        assertEquals(StepStatus.COMPLETED, result.getStepStatuses().get("notify"));

        RecordedRequest first = mockWebServer.takeRequest(1, TimeUnit.SECONDS);
        RecordedRequest second = mockWebServer.takeRequest(1, TimeUnit.SECONDS);
        RecordedRequest third = mockWebServer.takeRequest(1, TimeUnit.SECONDS);
        assertNotNull(first);
        assertNotNull(second);
        assertNotNull(third);
        assertEquals("/create", first.getPath());
        assertEquals("/review", second.getPath());
        assertEquals("/notify", third.getPath());
    }

    @Test
    void shouldSkipConditionFalseStepAndStillRunAnyJoinStep() throws Exception {
        mockWebServer.enqueue(jsonResponse("{\"code\":0,\"msg\":\"primary\",\"data\":{\"id\":1}}"));
        mockWebServer.enqueue(jsonResponse("{\"code\":0,\"msg\":\"merge\",\"data\":{}}"));

        FlowExecutionResult result = dagFlowExecutor.execute(buildAnyJoinFlow(), createFlowContext(baseInputs()));

        assertTrue(result.isSuccess());
        assertEquals(StepStatus.COMPLETED, result.getStepStatuses().get("primary"));
        assertEquals(StepStatus.SKIPPED, result.getStepStatuses().get("optional"));
        assertEquals(StepStatus.COMPLETED, result.getStepStatuses().get("merge"));
        assertEquals(2, mockWebServer.getRequestCount());

        RecordedRequest first = mockWebServer.takeRequest(1, TimeUnit.SECONDS);
        RecordedRequest second = mockWebServer.takeRequest(1, TimeUnit.SECONDS);
        assertNotNull(first);
        assertNotNull(second);
        assertEquals("/primary", first.getPath());
        assertEquals("/merge", second.getPath());
    }

    @Test
    void shouldPauseBeforeApprovalGatedStepExecutes() {
        FlowExecutionResult result = dagFlowExecutor.execute(buildApprovalFlow(), createFlowContext(baseInputs()));

        assertFalse(result.isSuccess());
        assertEquals("WAITING_APPROVAL", result.getLifecycleStatus());
        assertEquals("submit", result.getPausedStepId());
        assertEquals(StepStatus.WAITING_APPROVAL, result.getStepStatuses().get("submit"));
        assertEquals(0, mockWebServer.getRequestCount());
    }

    @Test
    void shouldSkipStepWhenSlotConditionDoesNotMatchContext() throws Exception {
        mockWebServer.enqueue(jsonResponse("{\"code\":0,\"msg\":\"primary\",\"data\":{\"id\":1}}"));
        mockWebServer.enqueue(jsonResponse("{\"code\":0,\"msg\":\"conditional\",\"data\":{}}"));
        mockWebServer.enqueue(jsonResponse("{\"code\":0,\"msg\":\"merge\",\"data\":{}}"));

        FlowExecutionResult result = dagFlowExecutor.execute(buildSlotConditionalFlow(), createFlowContext(baseInputs()));

        assertTrue(result.isSuccess());
        assertEquals(StepStatus.COMPLETED, result.getStepStatuses().get("primary"));
        assertEquals(StepStatus.SKIPPED, result.getStepStatuses().get("conditional"));
        assertEquals(StepStatus.COMPLETED, result.getStepStatuses().get("merge"));
        assertEquals(2, mockWebServer.getRequestCount());

        RecordedRequest first = mockWebServer.takeRequest(1, TimeUnit.SECONDS);
        RecordedRequest second = mockWebServer.takeRequest(1, TimeUnit.SECONDS);
        assertNotNull(first);
        assertNotNull(second);
        assertEquals("/primary", first.getPath());
        assertEquals("/merge", second.getPath());
    }

    @Test
    void shouldSkipStepWhenPreviousOutputConditionDoesNotMatchContext() throws Exception {
        mockWebServer.enqueue(jsonResponse("{\"code\":0,\"msg\":\"created\",\"data\":{\"id\":1}}"));
        mockWebServer.enqueue(jsonResponse("{\"code\":0,\"msg\":\"submitted\",\"data\":{}}"));
        mockWebServer.enqueue(jsonResponse("{\"code\":0,\"msg\":\"merge\",\"data\":{}}"));

        FlowExecutionResult result = dagFlowExecutor.execute(buildPreviousOutputConditionalFlow(), createFlowContext(baseInputs()));

        assertTrue(result.isSuccess());
        assertEquals(StepStatus.COMPLETED, result.getStepStatuses().get("create"));
        assertEquals(StepStatus.SKIPPED, result.getStepStatuses().get("submit"));
        assertEquals(StepStatus.COMPLETED, result.getStepStatuses().get("merge"));
        assertEquals(2, mockWebServer.getRequestCount());

        RecordedRequest first = mockWebServer.takeRequest(1, TimeUnit.SECONDS);
        RecordedRequest second = mockWebServer.takeRequest(1, TimeUnit.SECONDS);
        assertNotNull(first);
        assertNotNull(second);
        assertEquals("/create", first.getPath());
        assertEquals("/merge", second.getPath());
    }

    @Test
    void shouldIgnoreOrphanStepThatIsNotReachableFromEntry() throws Exception {
        mockWebServer.enqueue(jsonResponse("{\"code\":0,\"msg\":\"create\",\"data\":{}}"));
        mockWebServer.enqueue(jsonResponse("{\"code\":0,\"msg\":\"orphan\",\"data\":{}}"));

        FlowExecutionResult result = dagFlowExecutor.execute(buildFlowWithOrphan(), createFlowContext(baseInputs()));

        assertTrue(result.isSuccess());
        assertEquals(StepStatus.COMPLETED, result.getStepStatuses().get("create"));
        assertFalse(result.getStepStatuses().containsKey("orphan"));
        assertEquals(1, mockWebServer.getRequestCount());

        RecordedRequest first = mockWebServer.takeRequest(1, TimeUnit.SECONDS);
        assertNotNull(first);
        assertEquals("/create", first.getPath());
    }

    @Test
    void shouldExecuteDependencyChainWhenEntryPointsAtTerminalStep() throws Exception {
        mockWebServer.enqueue(jsonResponse("{\"code\":0,\"msg\":\"create\",\"data\":{}}"));
        mockWebServer.enqueue(jsonResponse("{\"code\":0,\"msg\":\"review\",\"data\":{}}"));
        mockWebServer.enqueue(jsonResponse("{\"code\":0,\"msg\":\"notify\",\"data\":{}}"));

        FlowExecutionResult result = dagFlowExecutor.execute(buildTerminalEntryDependencyFlow(), createFlowContext(baseInputs()));

        assertTrue(result.isSuccess());
        assertEquals(StepStatus.COMPLETED, result.getStepStatuses().get("create"));
        assertEquals(StepStatus.COMPLETED, result.getStepStatuses().get("review"));
        assertEquals(StepStatus.COMPLETED, result.getStepStatuses().get("notify"));
        assertEquals(3, mockWebServer.getRequestCount());
    }

    @Test
    void shouldNotExecuteSiblingDependentBranchWhenEntryTargetsTerminalPath() throws Exception {
        mockWebServer.enqueue(jsonResponse("{\"code\":0,\"msg\":\"create\",\"data\":{}}"));
        mockWebServer.enqueue(jsonResponse("{\"code\":0,\"msg\":\"review\",\"data\":{}}"));
        mockWebServer.enqueue(jsonResponse("{\"code\":0,\"msg\":\"notify\",\"data\":{}}"));
        mockWebServer.enqueue(jsonResponse("{\"code\":0,\"msg\":\"audit\",\"data\":{}}"));

        FlowExecutionResult result = dagFlowExecutor.execute(buildTerminalEntryWithSiblingDependentFlow(), createFlowContext(baseInputs()));

        assertTrue(result.isSuccess());
        assertEquals(StepStatus.COMPLETED, result.getStepStatuses().get("create"));
        assertEquals(StepStatus.COMPLETED, result.getStepStatuses().get("review"));
        assertEquals(StepStatus.COMPLETED, result.getStepStatuses().get("notify"));
        assertFalse(result.getStepStatuses().containsKey("audit"));
        assertEquals(3, mockWebServer.getRequestCount());
    }

    private FlowDefinition buildTerminalEntryDependencyFlow() {
        StepDefinition create = httpStep("create", "/create");
        StepDefinition review = httpStep("review", "/review");
        review.setDependsOn(List.of("create"));
        StepDefinition notify = httpStep("notify", "/notify");
        notify.setDependsOn(List.of("review"));

        FlowDefinition flow = new FlowDefinition();
        flow.setVersion("2.0");
        flow.setEntry(List.of("notify"));
        flow.setTerminal(List.of("notify"));
        flow.setSteps(Map.of(
                "notify", notify,
                "review", review,
                "create", create));
        return flow;
    }

    private FlowDefinition buildFlowWithOrphan() {
        StepDefinition create = httpStep("create", "/create");
        StepDefinition orphan = httpStep("orphan", "/orphan");

        FlowDefinition flow = new FlowDefinition();
        flow.setVersion("2.0");
        flow.setEntry(List.of("create"));
        flow.setTerminal(List.of("create"));
        flow.setSteps(Map.of(
                "create", create,
                "orphan", orphan));
        return flow;
    }

    private FlowDefinition buildTerminalEntryWithSiblingDependentFlow() {
        StepDefinition create = httpStep("create", "/create");
        StepDefinition review = httpStep("review", "/review");
        review.setDependsOn(List.of("create"));
        StepDefinition notify = httpStep("notify", "/notify");
        notify.setDependsOn(List.of("review"));
        StepDefinition audit = httpStep("audit", "/audit");
        audit.setDependsOn(List.of("create"));

        FlowDefinition flow = new FlowDefinition();
        flow.setVersion("2.0");
        flow.setEntry(List.of("notify"));
        flow.setTerminal(List.of("notify"));
        flow.setSteps(Map.of(
                "notify", notify,
                "review", review,
                "create", create,
                "audit", audit));
        return flow;
    }

    private FlowDefinition buildDependencyFlow() {
        StepDefinition create = httpStep("create", "/create");
        StepDefinition review = httpStep("review", "/review");
        review.setDependsOn(List.of("create"));
        StepDefinition notify = httpStep("notify", "/notify");
        notify.setDependsOn(List.of("review"));

        FlowDefinition flow = new FlowDefinition();
        flow.setVersion("2.0");
        flow.setEntry(List.of("notify", "review", "create"));
        flow.setTerminal(List.of("notify"));
        flow.setSteps(Map.of(
                "notify", notify,
                "review", review,
                "create", create));
        return flow;
    }

    private FlowDefinition buildAnyJoinFlow() {
        StepDefinition primary = httpStep("primary", "/primary");
        StepDefinition optional = httpStep("optional", "/optional");
        StepConfig optionalConfig = optional.getConfig();
        optionalConfig.setConditions(Map.of("enabled", true, "expression", false));
        StepDefinition merge = httpStep("merge", "/merge");
        merge.setDependsOn(List.of("primary", "optional"));
        merge.setJoinType(JoinType.ANY);

        FlowDefinition flow = new FlowDefinition();
        flow.setVersion("2.0");
        flow.setEntry(List.of("merge", "primary", "optional"));
        flow.setTerminal(List.of("merge"));
        flow.setSteps(Map.of(
                "merge", merge,
                "optional", optional,
                "primary", primary));
        return flow;
    }

    private FlowDefinition buildSlotConditionalFlow() {
        StepDefinition primary = httpStep("primary", "/primary");
        StepDefinition conditional = httpStep("conditional", "/conditional");
        conditional.getConfig().setConditions(Map.of(
                "enabled", true,
                "expression", Map.of(
                        "left", "${types}",
                        "operator", "eq",
                        "right", 9)));
        StepDefinition merge = httpStep("merge", "/merge");
        merge.setDependsOn(List.of("primary", "conditional"));
        merge.setJoinType(JoinType.ANY);

        FlowDefinition flow = new FlowDefinition();
        flow.setVersion("2.0");
        flow.setEntry(List.of("merge", "primary", "conditional"));
        flow.setTerminal(List.of("merge"));
        flow.setSteps(Map.of(
                "merge", merge,
                "conditional", conditional,
                "primary", primary));
        return flow;
    }

    private FlowDefinition buildPreviousOutputConditionalFlow() {
        StepDefinition create = httpStep("create", "/create");
        StepDefinition submit = httpStep("submit", "/submit");
        submit.setDependsOn(List.of("create"));
        submit.getConfig().setConditions(Map.of(
                "enabled", true,
                "expression", Map.of(
                        "left", "${create.message}",
                        "operator", "eq",
                        "right", "approved")));
        StepDefinition merge = httpStep("merge", "/merge");
        merge.setDependsOn(List.of("create", "submit"));
        merge.setJoinType(JoinType.ANY);

        FlowDefinition flow = new FlowDefinition();
        flow.setVersion("2.0");
        flow.setEntry(List.of("merge", "submit", "create"));
        flow.setTerminal(List.of("merge"));
        flow.setSteps(Map.of(
                "merge", merge,
                "submit", submit,
                "create", create));
        return flow;
    }

    private FlowDefinition buildApprovalFlow() {
        StepDefinition submit = httpStep("submit", "/submit");
        submit.getConfig().setApprovalGate(Map.of("enabled", true, "channel", "controlplane"));

        FlowDefinition flow = new FlowDefinition();
        flow.setVersion("2.0");
        flow.setEntry(List.of("submit"));
        flow.setTerminal(List.of("submit"));
        flow.setSteps(Map.of("submit", submit));
        return flow;
    }

    private StepDefinition httpStep(String stepId, String endpoint) {
        StepDefinition step = new StepDefinition();
        step.setStepId(stepId);
        step.setName(stepId);
        step.setType(StepType.HTTP);
        step.setJoinType(JoinType.ALL);
        StepConfig config = new StepConfig();
        config.setMethod("POST");
        config.setEndpoint(endpoint);
        config.setContentType("application/json");
        config.setInputMapping(Map.of("reason", ""));
        config.setOutputMapping(Map.of("message", "$.msg"));
        config.setSuccessCondition("$.code == 0");
        step.setConfig(config);
        return step;
    }
    private MockResponse jsonResponse(String body) {
        return new MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "application/json")
                .setBody(body);
    }

    private FlowDefinition loadFlowDefinition() {
        ToolMeta toolMeta = new ToolMeta();
        toolMeta.setToolCode("gougu_oa.leave_application");
        toolMeta.setToolName("leave_application");
        toolMeta.setExecutionPlan(readResource("flow_steps/leave_flow_steps.json"));
        return flowDefinitionConverter.parseFromToolMeta(toolMeta);
    }

    private FlowContext createFlowContext(Map<String, Object> inputs) {
        FlowContext context = new FlowContext(inputs);
        context.setSystemCode(TEST_SYSTEM_CODE);
        context.setAssistantUid(TEST_ASSISTANT_UID);
        return context;
    }

    private Map<String, Object> baseInputs() {
        Map<String, Object> inputs = new HashMap<>();
        inputs.put("types", 2);
        inputs.put("start_date", "2026-02-26");
        inputs.put("end_date", "2026-02-27");
        inputs.put("reason", "个人事务");
        inputs.put("duration", 2);
        inputs.put("check_flow_id", 1);
        inputs.put("check_uids", "3");
        inputs.put("notify_uids", List.of("8", "9"));
        return inputs;
    }

    private String readResource(String resourcePath) {
        try (InputStream inputStream = getClass().getClassLoader().getResourceAsStream(resourcePath)) {
            if (inputStream == null) {
                throw new IllegalStateException("Resource not found: " + resourcePath);
            }
            return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        }
        catch (IOException e) {
            throw new IllegalStateException("Failed to read resource: " + resourcePath, e);
        }
    }

    private static final class StaticSystemAccessProfilePort implements HttpStepExecutor.SystemAccessProfilePort {

        private final String baseUrl;

        private StaticSystemAccessProfilePort(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        @Override
        public String getBaseUrl(String systemCode) {
            return baseUrl;
        }

        @Override
        public String getTokenHeaderName(String systemCode) {
            return "Authorization";
        }

        @Override
        public String getTokenHeaderPrefix(String systemCode) {
            return "Bearer ";
        }
    }

    private static final class StaticTokenBroker implements TokenBroker {

        @Override
        public Optional<TokenLease> acquire(String assistantUid, String systemCode) {
            return Optional.of(new TokenLease(
                    "lease-1",
                    "test-token",
                    systemCode,
                    assistantUid,
                    LocalDateTime.now().plusMinutes(5)));
        }

        @Override
        public void revoke(String leaseId) {
            // no-op for tests
        }
    }
}
