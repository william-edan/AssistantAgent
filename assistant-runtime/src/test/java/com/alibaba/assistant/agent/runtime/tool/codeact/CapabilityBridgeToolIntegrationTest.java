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

import com.alibaba.assistant.agent.controlplane.identity.TokenBroker;
import com.alibaba.assistant.agent.controlplane.identity.TokenLease;
import com.alibaba.assistant.agent.controlplane.toolregistry.ToolMeta;
import com.alibaba.assistant.agent.execution.flow.DAGFlowExecutor;
import com.alibaba.assistant.agent.execution.flow.FlowDefinitionConverter;
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
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class CapabilityBridgeToolIntegrationTest {

	private static final String TEST_SYSTEM_CODE = "gougu_oa";

	private static final String TEST_ASSISTANT_UID = "assistant-1001";

	private MockWebServer mockWebServer;

	private ObjectMapper objectMapper;

	private CapabilityBridgeTool bridgeTool;

	@BeforeEach
	void setUp() throws IOException {
		mockWebServer = new MockWebServer();
		mockWebServer.start();

		objectMapper = new ObjectMapper();
		RequestBodySerializer requestBodySerializer = new RequestBodySerializer();
		HttpStepExecutor.SystemAccessProfilePort profilePort = new StaticSystemAccessProfilePort(
				"http://localhost:" + mockWebServer.getPort());
		TokenBroker tokenBroker = new StaticTokenBroker();

		HttpStepExecutor httpStepExecutor = new HttpStepExecutor(
				objectMapper, requestBodySerializer, profilePort, tokenBroker);
		DAGFlowExecutor dagFlowExecutor = new DAGFlowExecutor(httpStepExecutor);
		FlowDefinitionConverter flowDefinitionConverter = new FlowDefinitionConverter(objectMapper);

		ToolMeta toolMeta = new ToolMeta();
		toolMeta.setId(1L);
		toolMeta.setToolCode("gougu_oa.leave_application");
		toolMeta.setToolName("leave_application");
		toolMeta.setSystemCode(TEST_SYSTEM_CODE);
		toolMeta.setExecutionPlan(twoStepFlowPlan());
		toolMeta.setParameterSchema("{\"type\":\"object\",\"properties\":{\"types\":{\"type\":\"integer\"}}}");

		bridgeTool = new CapabilityBridgeTool(
				objectMapper,
				toolMeta,
				flowDefinitionConverter,
				dagFlowExecutor,
				httpStepExecutor,
				"leave_application_execute",
				"gougu_oa_tools");
	}

	@AfterEach
	void tearDown() throws IOException {
		if (mockWebServer != null) {
			mockWebServer.shutdown();
		}
	}

	@Test
	void shouldExecuteTwoStepLinearDagAgainstMockServer() throws Exception {
		mockWebServer.enqueue(jsonResponse("{\"code\":0,\"msg\":\"success\",\"data\":{\"return_id\":12345}}"));
		mockWebServer.enqueue(jsonResponse("{\"code\":0,\"msg\":\"approve success\"}"));

		String raw = bridgeTool.call(objectMapper.writeValueAsString(baseArgs()));
		@SuppressWarnings("unchecked")
		Map<String, Object> payload = objectMapper.readValue(raw, Map.class);

		assertEquals(Boolean.TRUE, payload.get("success"));
		assertEquals("FLOW", payload.get("mode"));
		assertEquals(2, mockWebServer.getRequestCount());

		RecordedRequest first = mockWebServer.takeRequest(1, TimeUnit.SECONDS);
		RecordedRequest second = mockWebServer.takeRequest(1, TimeUnit.SECONDS);

		assertNotNull(first);
		assertNotNull(second);
		assertEquals("/home/leaves/add", first.getPath());
		assertEquals("/api/check/submit_check", second.getPath());
		assertEquals("Bearer test-token", first.getHeader("Authorization"));
		assertTrue(first.getBody().readUtf8().contains("_ajax=1"));
		assertTrue(second.getBody().readUtf8().contains("action_id=12345"));
	}

	@Test
	void shouldShortCircuitWhenFirstStepFails() throws Exception {
		mockWebServer.enqueue(jsonResponse("{\"code\":500,\"msg\":\"create failed\"}"));
		mockWebServer.enqueue(jsonResponse("{\"code\":0,\"msg\":\"should not execute\"}"));

		String raw = bridgeTool.call(objectMapper.writeValueAsString(baseArgs()));
		@SuppressWarnings("unchecked")
		Map<String, Object> payload = objectMapper.readValue(raw, Map.class);

		assertEquals(Boolean.FALSE, payload.get("success"));
		assertEquals("FLOW", payload.get("mode"));
		assertEquals(1, mockWebServer.getRequestCount());
	}

	private MockResponse jsonResponse(String body) {
		return new MockResponse()
				.setResponseCode(200)
				.addHeader("Content-Type", "application/json")
				.setBody(body);
	}

	private Map<String, Object> baseArgs() {
		Map<String, Object> args = new HashMap<>();
		args.put("types", 2);
		args.put("start_date", "2026-02-26");
		args.put("end_date", "2026-02-27");
		args.put("reason", "personal");
		args.put("duration", 2);
		args.put("check_flow_id", 1);
		args.put("check_uids", "3");
		args.put("assistant_uid", TEST_ASSISTANT_UID);
		args.put("system_code", TEST_SYSTEM_CODE);
		return args;
	}

	private String twoStepFlowPlan() {
		return """
				{
				  "version": "2.0",
				  "entry": ["create_leave"],
				  "terminal": ["submit_approval"],
				  "steps": {
				    "create_leave": {
				      "stepId": "create_leave",
				      "name": "Create leave",
				      "type": "HTTP",
				      "next": ["submit_approval"],
				      "config": {
				        "method": "POST",
				        "endpoint": "/home/leaves/add",
				        "contentType": "application/x-www-form-urlencoded",
				        "inputMapping": {
				          "id": "0",
				          "types": "${types}",
				          "start_date": "${start_date}",
				          "end_date": "${end_date}",
				          "reason": "${reason}",
				          "duration": "${duration}",
				          "check_flow_id": "${check_flow_id}",
				          "check_uids": "${check_uids}"
				        },
				        "outputMapping": {
				          "leave_id": "$.data.return_id"
				        },
				        "successCondition": "$.code == 0"
				      }
				    },
				    "submit_approval": {
				      "stepId": "submit_approval",
				      "name": "Submit approval",
				      "type": "HTTP",
				      "config": {
				        "method": "POST",
				        "endpoint": "/api/check/submit_check",
				        "contentType": "application/x-www-form-urlencoded",
				        "inputMapping": {
				          "check_name": "leaves",
				          "action_id": "${create_leave.leave_id}"
				        },
				        "successCondition": "$.code == 0"
				      }
				    }
				  }
				}
				""";
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
			// no-op for test
		}
	}

}
