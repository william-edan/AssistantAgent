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
package com.alibaba.assistant.agent.runtime.e2e;

import com.alibaba.assistant.agent.controlplane.identity.TokenBroker;
import com.alibaba.assistant.agent.controlplane.identity.TokenLease;
import com.alibaba.assistant.agent.controlplane.toolregistry.ToolMeta;
import com.alibaba.assistant.agent.execution.flow.DAGFlowExecutor;
import com.alibaba.assistant.agent.execution.flow.FlowDefinitionConverter;
import com.alibaba.assistant.agent.execution.step.HttpStepExecutor;
import com.alibaba.assistant.agent.execution.step.http.RequestBodySerializer;
import com.alibaba.assistant.agent.runtime.agent.AssistantStateKeys;
import com.alibaba.assistant.agent.runtime.tool.codeact.CapabilityBridgeTool;
import com.alibaba.assistant.agent.runtime.tool.react.SlotCollectTool;
import com.alibaba.assistant.agent.runtime.tool.react.SlotConfirmTool;
import com.alibaba.assistant.agent.slot.SlotCollectorService;
import com.alibaba.assistant.agent.slot.SlotEnricherService;
import com.alibaba.assistant.agent.slot.SlotSchemaParser;
import com.alibaba.assistant.agent.slot.computed.ComputedFieldProcessor;
import com.alibaba.assistant.agent.slot.computed.ConcatFunction;
import com.alibaba.assistant.agent.slot.computed.DateDiffFunction;
import com.alibaba.assistant.agent.slot.form.FormDisplayConfigService;
import com.alibaba.assistant.agent.slot.model.EnrichedSlot;
import com.alibaba.assistant.agent.slot.model.SlotDefinition;
import com.alibaba.assistant.agent.slot.model.SlotOption;
import com.alibaba.assistant.agent.slot.model.ToolMetaSnapshot;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.agent.tools.ToolContextConstants;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LeaveScenarioEndToEndIntegrationTest {

	private static final String SYSTEM_CODE = "gougu_oa";

	private static final String ASSISTANT_UID = "assistant-1001";

	private static final String THREAD_ID = "thread-001";

	private static final Set<String> API_ENRICHED_SLOT_NAMES =
			Set.of("types", "check_flow_id", "check_uids", "check_copy_uids");

	private static final String LEAVE_SLOT_SCHEMA = """
			{
			  "slots": [
			    {
			      "name": "types",
			      "type": "integer",
			      "title": "Leave type",
			      "priority": "CORE",
			      "required": true,
			      "ask_mode": "BATCH",
			      "options": {
			        "source": "API",
			        "api": {
			          "endpoint": "/api/oa_integration/get_leave_types",
			          "method": "GET",
			          "value_field": "id",
			          "label_field": "name"
			        }
			      }
			    },
			    {
			      "name": "start_date",
			      "type": "date",
			      "title": "Start date",
			      "priority": "CORE",
			      "required": true,
			      "ask_mode": "BATCH"
			    },
			    {
			      "name": "end_date",
			      "type": "date",
			      "title": "End date",
			      "priority": "CORE",
			      "required": true,
			      "ask_mode": "BATCH"
			    },
			    {
			      "name": "reason",
			      "type": "string",
			      "title": "Reason",
			      "priority": "CORE",
			      "required": true,
			      "ask_mode": "BATCH"
			    },
			    {
			      "name": "duration",
			      "type": "number",
			      "title": "Duration",
			      "priority": "OPTIONAL",
			      "ask_mode": "AUTO",
			      "computed": {
			        "enabled": true,
			        "type": "FUNCTION",
			        "function": "date_diff",
			        "params": {
			          "start": "start_date",
			          "end": "end_date",
			          "unit": "days",
			          "include_start": "true",
			          "include_end": "true"
			        }
			      }
			    },
			    {
			      "name": "check_flow_id",
			      "type": "integer",
			      "title": "Flow",
			      "priority": "OPTIONAL",
			      "ask_mode": "AUTO",
			      "options": {
			        "source": "API",
			        "api": {
			          "endpoint": "/api/check/get_flow_nodes",
			          "method": "GET",
			          "value_field": "flow_id",
			          "label_field": "title"
			        }
			      }
			    },
			    {
			      "name": "check_uids",
			      "type": "string",
			      "title": "Approver",
			      "priority": "SUPPLEMENTARY",
			      "ask_mode": "AUTO",
			      "options": {
			        "source": "API",
			        "api": {
			          "endpoint": "/api/oa_integration/get_all_users",
			          "method": "GET",
			          "value_field": "id",
			          "label_field": "name"
			        }
			      }
			    },
			    {
			      "name": "check_copy_uids",
			      "type": "array",
			      "title": "CC",
			      "priority": "SUPPLEMENTARY",
			      "ask_mode": "FORM_ONLY",
			      "options": {
			        "source": "API",
			        "api": {
			          "endpoint": "/api/oa_integration/get_all_users",
			          "method": "GET",
			          "value_field": "id",
			          "label_field": "name"
			        }
			      }
			    }
			  ]
			}
			""";

	private MockWebServer mockWebServer;

	private ObjectMapper objectMapper;

	private SlotCollectTool slotCollectTool;

	private SlotConfirmTool slotConfirmTool;

	private CapabilityBridgeTool capabilityBridgeTool;

	private OverAllState state;

	private ToolContext toolContext;

	@BeforeEach
	void setUp() throws IOException {
		mockWebServer = new MockWebServer();
		mockWebServer.start();

		objectMapper = new ObjectMapper();
		SlotSchemaParser parser = new SlotSchemaParser(objectMapper);
		SlotCollectorService collectorService = new SlotCollectorService();
		SlotEnricherService enricherService = mock(SlotEnricherService.class);
		ComputedFieldProcessor computedFieldProcessor = new ComputedFieldProcessor(
				List.of(new DateDiffFunction(), new ConcatFunction()));
		FormDisplayConfigService displayConfigService = new FormDisplayConfigService();

		mockEnricher(enricherService);

		slotCollectTool = new SlotCollectTool(
				collectorService,
				enricherService,
				computedFieldProcessor,
				parser,
				objectMapper);
		slotConfirmTool = new SlotConfirmTool(
				parser,
				enricherService,
				computedFieldProcessor,
				displayConfigService,
				objectMapper);
		capabilityBridgeTool = buildBridgeTool();

		state = initState();
		toolContext = new ToolContext(Map.of(ToolContextConstants.AGENT_STATE_CONTEXT_KEY, state));
	}

	@AfterEach
	void tearDown() throws IOException {
		if (mockWebServer != null) {
			mockWebServer.shutdown();
		}
	}

	@Test
	void shouldPassFullThreeTurnLeaveScenario() throws Exception {
		enqueueSuccessDagResponses();

		Map<String, Object> executionPayload = runThreeTurnScenario();

		assertEquals(Boolean.TRUE, executionPayload.get("success"));
		assertEquals("FLOW", executionPayload.get("mode"));
		assertEquals("DONE", state.value(AssistantStateKeys.CONVERSATION_PHASE, String.class).orElse(null));
		assertEquals(2, mockWebServer.getRequestCount());

		RecordedRequest first = mockWebServer.takeRequest(1, TimeUnit.SECONDS);
		RecordedRequest second = mockWebServer.takeRequest(1, TimeUnit.SECONDS);
		assertNotNull(first);
		assertNotNull(second);
		assertEquals("/home/leaves/add", first.getPath());
		assertEquals("/api/check/submit_check", second.getPath());
		assertTrue(first.getBody().readUtf8().contains("_ajax=1"));
		assertTrue(second.getBody().readUtf8().contains("action_id=12345"));
	}

	@Test
	void shouldMeetPerformanceBaselineWithin15Seconds() throws Exception {
		enqueueSuccessDagResponses();
		long startNanos = System.nanoTime();

		Map<String, Object> executionPayload = runThreeTurnScenario();

		long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
		assertEquals(Boolean.TRUE, executionPayload.get("success"));
		assertTrue(elapsedMs < 15_000, "End-to-end leave scenario should complete in less than 15s");
	}

	private Map<String, Object> runThreeTurnScenario() throws Exception {
		SlotCollectTool.Request firstTurn = new SlotCollectTool.Request();
		firstTurn.extractedSlots = Map.of(
				"types", 2,
				"start_date", "2026-02-26",
				"end_date", "2026-02-27");
		SlotCollectTool.Response firstResponse = slotCollectTool.apply(firstTurn, toolContext);

		assertEquals("COLLECTING", firstResponse.phase);
		assertNotNull(firstResponse.missing);
		assertTrue(firstResponse.missing.stream().anyMatch(slot -> "reason".equals(slot.name)));

		SlotCollectTool.Request secondTurn = new SlotCollectTool.Request();
		secondTurn.extractedSlots = Map.of("reason", "personal matters");
		SlotCollectTool.Response secondResponse = slotCollectTool.apply(secondTurn, toolContext);

		assertEquals("READY_TO_CONFIRM", secondResponse.phase);
		assertEquals(2L, ((Number) secondResponse.collected.get("duration")).longValue());
		assertEquals(101, ((Number) secondResponse.collected.get("check_flow_id")).intValue());
		assertEquals("u1001", secondResponse.collected.get("check_uids"));
		assertNotNull(secondResponse.enrichedSlots);
		long apiEnrichedCount = secondResponse.enrichedSlots.stream()
				.filter(slot -> slot.getDefinition() != null
						&& API_ENRICHED_SLOT_NAMES.contains(slot.getDefinition().getName()))
				.filter(slot -> slot.isOptionsLoaded() && slot.getOptions() != null && !slot.getOptions().isEmpty())
				.count();
		assertEquals(4L, apiEnrichedCount);

		SlotConfirmTool.Response confirmResponse = slotConfirmTool.apply(new SlotConfirmTool.Request(), toolContext);
		assertEquals("CONFIRMING", confirmResponse.status);
		assertNotNull(confirmResponse.confirmForm);
		assertNotNull(confirmResponse.confirmForm.formSummary);
		assertNotNull(confirmResponse.confirmForm.collected);

		Map<String, Object> executeArgs = new HashMap<>(secondResponse.collected);
		executeArgs.put("assistant_uid", ASSISTANT_UID);
		executeArgs.put("system_code", SYSTEM_CODE);
		executeArgs.put("thread_id", THREAD_ID);

		String executionRaw = capabilityBridgeTool.call(objectMapper.writeValueAsString(executeArgs), toolContext);
		@SuppressWarnings("unchecked")
		Map<String, Object> executionPayload = objectMapper.readValue(executionRaw, Map.class);
		return executionPayload;
	}

	private void enqueueSuccessDagResponses() {
		mockWebServer.enqueue(jsonResponse("{\"code\":0,\"msg\":\"success\",\"data\":{\"return_id\":12345}}"));
		mockWebServer.enqueue(jsonResponse("{\"code\":0,\"msg\":\"approve success\"}"));
	}

	private CapabilityBridgeTool buildBridgeTool() {
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
		toolMeta.setSystemCode(SYSTEM_CODE);
		toolMeta.setExecutionPlan(twoStepFlowPlan());
		toolMeta.setParameterSchema(LEAVE_SLOT_SCHEMA);

		return new CapabilityBridgeTool(
				objectMapper,
				toolMeta,
				flowDefinitionConverter,
				dagFlowExecutor,
				httpStepExecutor,
				"leave_application_execute",
				"gougu_oa_tools");
	}

	private static void mockEnricher(SlotEnricherService enricherService) {
		when(enricherService.enrichSlots(anyList(), eq(SYSTEM_CODE), eq(ASSISTANT_UID)))
				.thenAnswer(invocation -> {
					@SuppressWarnings("unchecked")
					List<SlotDefinition> definitions = invocation.getArgument(0);
					List<EnrichedSlot> enrichedSlots = new ArrayList<>();
					for (SlotDefinition definition : definitions) {
						EnrichedSlot enrichedSlot = new EnrichedSlot(definition);
						if ("types".equals(definition.getName())) {
							enrichedSlot.setOptions(List.of(
									new SlotOption("Annual leave", 2),
									new SlotOption("Sick leave", 4)));
						}
						else if ("check_flow_id".equals(definition.getName())) {
							enrichedSlot.setOptions(List.of(
									new SlotOption("Default flow", 101),
									new SlotOption("Backup flow", 102)));
						}
						else if ("check_uids".equals(definition.getName())) {
							enrichedSlot.setOptions(List.of(
									new SlotOption("Alice Approver", "u1001"),
									new SlotOption("Bob Approver", "u1002")));
						}
						else if ("check_copy_uids".equals(definition.getName())) {
							enrichedSlot.setOptions(List.of(
									new SlotOption("Carol CC", "u2001"),
									new SlotOption("Dave CC", "u2002")));
						}
						enrichedSlots.add(enrichedSlot);
					}
					return enrichedSlots;
				});
	}

	private static OverAllState initState() {
		OverAllState state = new OverAllState();
		ToolMetaSnapshot snapshot = new ToolMetaSnapshot();
		snapshot.setToolCode("leave_application");
		snapshot.setSlotSchema(LEAVE_SLOT_SCHEMA);
		snapshot.setExecutionPlan(twoStepFlowPlan());
		snapshot.setSystemCode(SYSTEM_CODE);

		Map<String, Object> updates = new HashMap<>();
		updates.put(AssistantStateKeys.MATCHED_TOOL_META, snapshot);
		updates.put(AssistantStateKeys.SYSTEM_CODE, SYSTEM_CODE);
		updates.put(AssistantStateKeys.ASSISTANT_UID, ASSISTANT_UID);
		updates.put(AssistantStateKeys.THREAD_ID, THREAD_ID);
		state.updateState(updates);
		return state;
	}

	private static MockResponse jsonResponse(String body) {
		return new MockResponse()
				.setResponseCode(200)
				.addHeader("Content-Type", "application/json")
				.setBody(body);
	}

	private static String twoStepFlowPlan() {
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
