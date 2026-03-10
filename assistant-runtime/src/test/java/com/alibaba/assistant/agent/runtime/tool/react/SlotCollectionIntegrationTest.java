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
package com.alibaba.assistant.agent.runtime.tool.react;

import com.alibaba.assistant.agent.runtime.agent.AssistantStateKeys;
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
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SlotCollectionIntegrationTest {

	private static final String LEAVE_SLOT_SCHEMA = """
			{
			  "slots": [
			    {
			      "name": "types",
			      "type": "integer",
			      "title": "请假类型",
			      "priority": "CORE",
			      "required": true,
			      "ask_mode": "BATCH",
			      "options": {
			        "source": "ENUM",
			        "enum_mapping": {
			          "年假": 2,
			          "病假": 4
			        }
			      },
			      "displayConfig": {
			        "showInSummary": true,
			        "summaryOrder": 1,
			        "summaryGroup": "CORE"
			      }
			    },
			    {
			      "name": "start_date",
			      "type": "date",
			      "title": "开始日期",
			      "priority": "CORE",
			      "required": true,
			      "ask_mode": "BATCH",
			      "displayConfig": {
			        "showInSummary": true,
			        "summaryOrder": 2,
			        "summaryGroup": "CORE"
			      }
			    },
			    {
			      "name": "end_date",
			      "type": "date",
			      "title": "结束日期",
			      "priority": "CORE",
			      "required": true,
			      "ask_mode": "BATCH",
			      "displayConfig": {
			        "showInSummary": true,
			        "summaryOrder": 3,
			        "summaryGroup": "CORE"
			      }
			    },
			    {
			      "name": "reason",
			      "type": "string",
			      "title": "请假原因",
			      "priority": "CORE",
			      "required": true,
			      "ask_mode": "BATCH",
			      "displayConfig": {
			        "showInSummary": true,
			        "summaryOrder": 4,
			        "summaryGroup": "CORE"
			      }
			    },
			    {
			      "name": "duration",
			      "type": "number",
			      "title": "请假时长",
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
			      },
			      "displayConfig": {
			        "showInSummary": true,
			        "summaryOrder": 5,
			        "summaryGroup": "CORE",
			        "displaySuffix": "天"
			      }
			    },
			    {
			      "name": "check_flow_id",
			      "type": "integer",
			      "title": "审批流",
			      "priority": "OPTIONAL",
			      "ask_mode": "AUTO",
			      "displayConfig": {
			        "showInSummary": true,
			        "summaryOrder": 6,
			        "summaryGroup": "SECONDARY"
			      }
			    },
			    {
			      "name": "check_uids",
			      "type": "string",
			      "title": "审批人",
			      "priority": "SUPPLEMENTARY",
			      "ask_mode": "AUTO",
			      "displayConfig": {
			        "showInSummary": true,
			        "summaryOrder": 7,
			        "summaryGroup": "SECONDARY"
			      }
			    }
			  ]
			}
			""";

	@Test
	void shouldCompleteTwoTurnCollectionAndGenerateConfirmForm() {
		ObjectMapper objectMapper = new ObjectMapper();
		SlotSchemaParser parser = new SlotSchemaParser(objectMapper);
		SlotCollectorService collectorService = new SlotCollectorService();
		SlotEnricherService enricherService = mock(SlotEnricherService.class);
		ComputedFieldProcessor computedFieldProcessor = new ComputedFieldProcessor(
				List.of(new DateDiffFunction(), new ConcatFunction()));
		FormDisplayConfigService displayConfigService = new FormDisplayConfigService();

		mockEnricher(enricherService);

		SlotCollectTool slotCollectTool = new SlotCollectTool(
				collectorService,
				enricherService,
				computedFieldProcessor,
				parser,
				objectMapper);
		SlotConfirmTool slotConfirmTool = new SlotConfirmTool(
				parser,
				enricherService,
				computedFieldProcessor,
				displayConfigService,
				objectMapper);

		OverAllState state = initState();
		ToolContext toolContext = toolContext(state);

		SlotCollectTool.Request firstTurn = new SlotCollectTool.Request();
		firstTurn.extractedSlots = Map.of(
				"types", 2,
				"start_date", "2026-02-26",
				"end_date", "2026-02-27");
		SlotCollectTool.Response firstResponse = slotCollectTool.apply(firstTurn, toolContext);

		assertEquals("COLLECTING", firstResponse.phase);
		assertNotNull(firstResponse.missing);
		assertTrue(firstResponse.missing.stream().map(item -> item.name).collect(Collectors.toSet()).contains("reason"));

		SlotCollectTool.Request secondTurn = new SlotCollectTool.Request();
		secondTurn.extractedSlots = Map.of("reason", "个人事务");
		SlotCollectTool.Response secondResponse = slotCollectTool.apply(secondTurn, toolContext);

		assertEquals("READY_TO_CONFIRM", secondResponse.phase);
		assertEquals(2L, ((Number) secondResponse.collected.get("duration")).longValue());
		assertEquals(101, ((Number) secondResponse.collected.get("check_flow_id")).intValue());
		assertEquals("u1001", secondResponse.collected.get("check_uids"));

		SlotConfirmTool.Response confirmResponse = slotConfirmTool.apply(new SlotConfirmTool.Request(), toolContext);
		assertEquals("CONFIRMING", confirmResponse.status);
		assertNotNull(confirmResponse.confirmForm);
		assertNotNull(confirmResponse.confirmForm.formSummary);
		assertTrue(confirmResponse.confirmForm.formSummary.getSummaryItems()
				.stream()
				.anyMatch(item -> "年假".equals(item.getValue())));

		assertEquals("CONFIRMING",
				state.value(AssistantStateKeys.CONVERSATION_PHASE, String.class).orElse(null));
	}

	@Test
	void shouldRecalculateDurationWhenUserModifiesDates() {
		ObjectMapper objectMapper = new ObjectMapper();
		SlotSchemaParser parser = new SlotSchemaParser(objectMapper);
		SlotCollectorService collectorService = new SlotCollectorService();
		SlotEnricherService enricherService = mock(SlotEnricherService.class);
		ComputedFieldProcessor computedFieldProcessor = new ComputedFieldProcessor(
				List.of(new DateDiffFunction(), new ConcatFunction()));

		mockEnricher(enricherService);

		SlotCollectTool slotCollectTool = new SlotCollectTool(
				collectorService,
				enricherService,
				computedFieldProcessor,
				parser,
				objectMapper);

		OverAllState state = initState();
		ToolContext toolContext = toolContext(state);

		SlotCollectTool.Request initial = new SlotCollectTool.Request();
		initial.extractedSlots = Map.of(
				"types", 2,
				"start_date", "2026-03-10",
				"end_date", "2026-03-11",
				"reason", "家中有事");
		SlotCollectTool.Response initialResponse = slotCollectTool.apply(initial, toolContext);
		assertEquals("READY_TO_CONFIRM", initialResponse.phase);
		assertEquals(2L, ((Number) initialResponse.collected.get("duration")).longValue());

		SlotCollectTool.Request updateDates = new SlotCollectTool.Request();
		updateDates.extractedSlots = Map.of(
				"start_date", "2026-03-17",
				"end_date", "2026-03-17");
		SlotCollectTool.Response updatedResponse = slotCollectTool.apply(updateDates, toolContext);

		assertEquals("READY_TO_CONFIRM", updatedResponse.phase);
		assertEquals("2026-03-17", updatedResponse.collected.get("start_date"));
		assertEquals("2026-03-17", updatedResponse.collected.get("end_date"));
		assertEquals(1L, ((Number) updatedResponse.collected.get("duration")).longValue());
		assertEquals("家中有事", updatedResponse.collected.get("reason"));
	}

	@Test
	void shouldKeepCollectingWhenNoNewUserInputAfterMissingRequiredEndDate() {
		ObjectMapper objectMapper = new ObjectMapper();
		SlotSchemaParser parser = new SlotSchemaParser(objectMapper);
		SlotCollectorService collectorService = new SlotCollectorService();
		SlotEnricherService enricherService = mock(SlotEnricherService.class);
		ComputedFieldProcessor computedFieldProcessor = new ComputedFieldProcessor(
				List.of(new DateDiffFunction(), new ConcatFunction()));
		FormDisplayConfigService displayConfigService = new FormDisplayConfigService();

		mockEnricher(enricherService);

		SlotCollectTool slotCollectTool = new SlotCollectTool(
				collectorService,
				enricherService,
				computedFieldProcessor,
				parser,
				objectMapper);
		SlotConfirmTool slotConfirmTool = new SlotConfirmTool(
				parser,
				enricherService,
				computedFieldProcessor,
				displayConfigService,
				objectMapper);

		OverAllState state = initState();
		state.updateState(Map.of(
				"input", "明天有点事情的时候需要请假",
				"messages", List.of(new UserMessage("明天有点事情的时候需要请假"))));
		ToolContext toolContext = toolContext(state);

		SlotCollectTool.Request firstTurn = new SlotCollectTool.Request();
		firstTurn.extractedSlots = Map.of(
				"types", 2,
				"start_date", "2026-03-03",
				"reason", "个人事务");
		SlotCollectTool.Response firstResponse = slotCollectTool.apply(firstTurn, toolContext);
		assertEquals("COLLECTING", firstResponse.phase);
		assertTrue(firstResponse.missing.stream().map(item -> item.name).collect(Collectors.toSet()).contains("end_date"));

		// Simulate model self-filling end_date in the same user turn without any new user input.
		SlotCollectTool.Request secondTurn = new SlotCollectTool.Request();
		secondTurn.extractedSlots = Map.of(
				"types", 2,
				"start_date", "2026-03-03",
				"end_date", "2026-03-05",
				"reason", "个人事务");
		SlotCollectTool.Response secondResponse = slotCollectTool.apply(secondTurn, toolContext);

		assertEquals("COLLECTING", secondResponse.phase);
		assertTrue(secondResponse.missing.stream().map(item -> item.name).collect(Collectors.toSet()).contains("end_date"));
		assertEquals("2026-03-03", secondResponse.collected.get("start_date"));
		assertNull(secondResponse.collected.get("end_date"));

		// slot_confirm must keep the flow in collecting mode until the missing required slot is provided.
		SlotConfirmTool.Response confirmResponse = slotConfirmTool.apply(new SlotConfirmTool.Request(), toolContext);
		assertEquals("COLLECTING", confirmResponse.status);
		assertTrue(confirmResponse.message.contains("end_date"));
	}

	private static void mockEnricher(SlotEnricherService enricherService) {
		when(enricherService.enrichSlots(anyList(), eq("oa"), eq("u1")))
				.thenAnswer(invocation -> {
					@SuppressWarnings("unchecked")
					List<SlotDefinition> definitions = invocation.getArgument(0);
					List<EnrichedSlot> enrichedSlots = new ArrayList<>();
					for (SlotDefinition definition : definitions) {
						EnrichedSlot enrichedSlot = new EnrichedSlot(definition);
						if ("check_flow_id".equals(definition.getName())) {
							enrichedSlot.setOptions(List.of(
									new SlotOption("默认审批流", 101),
									new SlotOption("备用审批流", 102)));
						}
						else if ("check_uids".equals(definition.getName())) {
							enrichedSlot.setOptions(List.of(
									new SlotOption("张三", "u1001"),
									new SlotOption("李四", "u1002")));
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
		snapshot.setSystemCode("oa");

		Map<String, Object> updates = new HashMap<>();
		updates.put(AssistantStateKeys.MATCHED_TOOL_META, snapshot);
		updates.put(AssistantStateKeys.SYSTEM_CODE, "oa");
		updates.put(AssistantStateKeys.ASSISTANT_UID, "u1");
		state.updateState(updates);
		return state;
	}

	private static ToolContext toolContext(OverAllState state) {
		return new ToolContext(Map.of(ToolContextConstants.AGENT_STATE_CONTEXT_KEY, state));
	}
}

