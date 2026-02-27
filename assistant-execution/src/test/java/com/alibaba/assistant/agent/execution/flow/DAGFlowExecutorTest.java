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
import com.alibaba.assistant.agent.execution.model.StepStatus;
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
