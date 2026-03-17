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
package com.alibaba.assistant.agent.execution.step;

import com.alibaba.assistant.agent.controlplane.identity.TokenBroker;
import com.alibaba.assistant.agent.execution.flow.FlowContext;
import com.alibaba.assistant.agent.execution.model.StepConfig;
import com.alibaba.assistant.agent.execution.model.StepResult;
import com.alibaba.assistant.agent.execution.step.http.RequestBodySerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class HttpStepExecutorCredentialWiringTest {

    private MockWebServer mockWebServer;

    @BeforeEach
    void setUp() throws IOException {
        mockWebServer = new MockWebServer();
        mockWebServer.start();
    }

    @AfterEach
    void tearDown() throws IOException {
        if (mockWebServer != null) {
            mockWebServer.shutdown();
        }
    }

    @Test
    void shouldPreferResolvedStepHeadersAndBaseUrlOverLegacyIdentityLookup() throws Exception {
        HttpStepExecutor.SystemAccessProfilePort profilePort = new HttpStepExecutor.SystemAccessProfilePort() {
            @Override
            public String getBaseUrl(String systemCode) {
                throw new AssertionError("legacy baseUrl lookup should not be used");
            }

            @Override
            public String getTokenHeaderName(String systemCode) {
                throw new AssertionError("legacy token header lookup should not be used");
            }

            @Override
            public String getTokenHeaderPrefix(String systemCode) {
                throw new AssertionError("legacy token prefix lookup should not be used");
            }
        };
        TokenBroker tokenBroker = mock(TokenBroker.class);
        HttpStepExecutor executor = new HttpStepExecutor(
                new ObjectMapper(),
                new RequestBodySerializer(),
                profilePort,
                tokenBroker,
                false);
        StepConfig config = new StepConfig();
        config.setMethod("POST");
        config.setEndpoint("/api/check/submit_check");
        config.setContentType("application/json");
        config.setSuccessCondition("$.code == 0");

        FlowContext context = new FlowContext(Map.of("reason", "事假"));
        context.setRunId("RUN-1");
        context.setCurrentStepId("submit_approval");
        context.putStepBaseUrl("submit_approval", mockWebServer.url("/").toString().replaceAll("/$", ""));
        context.putStepRequestHeaders("submit_approval", Map.of("Authorization", "Bearer step-token"));

        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "application/json")
                .setBody("{\"code\":0,\"msg\":\"ok\"}"));

        StepResult result = executor.execute(config, context);

        assertTrue(result.isSuccess());
        RecordedRequest recordedRequest = mockWebServer.takeRequest(1, TimeUnit.SECONDS);
        assertNotNull(recordedRequest);
        assertEquals("Bearer step-token", recordedRequest.getHeader("Authorization"));
    }

    @Test
    void shouldRenderPathQueryHeaderAndBodyGroupsFromInputMapping() throws Exception {
        HttpStepExecutor executor = new HttpStepExecutor(
                new ObjectMapper(),
                new RequestBodySerializer(),
                mock(HttpStepExecutor.SystemAccessProfilePort.class),
                mock(TokenBroker.class),
                false);
        StepConfig config = new StepConfig();
        config.setMethod("POST");
        config.setEndpoint("/api/users/{userId}");
        config.setContentType("application/json");
        config.setInputMapping(Map.of(
                "path", "${path}",
                "query", "${query}",
                "headers", "${headers}",
                "body", "${body}"));
        config.setOutputMapping(Map.of("response", "$"));

        FlowContext context = new FlowContext(Map.of(
                "path", Map.of("userId", "u-1"),
                "query", Map.of("expand", "roles"),
                "headers", Map.of("X-Trace-Id", "trace-1"),
                "body", Map.of("reason", "vacation")));
        context.setCurrentStepId("invoke");
        context.putStepBaseUrl("invoke", mockWebServer.url("/").toString().replaceAll("/$", ""));
        context.putStepRequestHeaders("invoke", Map.of("Authorization", "Bearer step-token"));

        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "application/json")
                .setBody("{\"ok\":true}"));

        StepResult result = executor.execute(config, context);

        assertTrue(result.isSuccess());
        RecordedRequest recordedRequest = mockWebServer.takeRequest(1, TimeUnit.SECONDS);
        assertNotNull(recordedRequest);
        assertEquals("/api/users/u-1?expand=roles", recordedRequest.getPath());
        assertEquals("Bearer step-token", recordedRequest.getHeader("Authorization"));
        assertEquals("trace-1", recordedRequest.getHeader("X-Trace-Id"));
        assertEquals("{\"reason\":\"vacation\"}", recordedRequest.getBody().readUtf8());
        assertEquals(Boolean.TRUE, ((Map<?, ?>) result.getOutputs().get("response")).get("ok"));
    }
}
