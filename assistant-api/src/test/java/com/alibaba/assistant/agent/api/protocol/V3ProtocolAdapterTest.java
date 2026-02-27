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
package com.alibaba.assistant.agent.api.protocol;

import com.alibaba.assistant.agent.api.protocol.V3ProtocolAdapter.AssistantEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class V3ProtocolAdapterTest {

	@Test
	void shouldAdaptSlotCollectToSlotProgressEvent() {
		V3ProtocolAdapter adapter = new V3ProtocolAdapter(new ObjectMapper());
		List<AssistantEvent> events = adapter.adapt(
				"slot_collect",
				"{\"status\":\"COLLECTING\",\"missing\":[{\"name\":\"reason\"}],\"collected\":{\"types\":2}}",
				(Map<String, Object>) null);

		assertEquals(1, events.size());
		assertEquals("SLOT_PROGRESS", events.get(0).getType());
		assertNotNull(events.get(0).getPayload());
		assertEquals("COLLECTING", events.get(0).getPayload().get("status"));
	}

	@Test
	void shouldAdaptSlotConfirmToUiConfirmFormEvent() {
		V3ProtocolAdapter adapter = new V3ProtocolAdapter(new ObjectMapper());
		List<AssistantEvent> events = adapter.adapt(
				"slot_confirm",
				"{\"confirmForm\":{\"toolCode\":\"leave_application\",\"collected\":{\"types\":2}}}",
				(Map<String, Object>) null);

		assertEquals(1, events.size());
		assertEquals("UI", events.get(0).getType());
		assertEquals("ConfirmForm", events.get(0).getComponent());
		assertNotNull(events.get(0).getPayload());
	}

	@Test
	void shouldAdaptExecuteCodeToProgressAndDoneEvents() {
		V3ProtocolAdapter adapter = new V3ProtocolAdapter(new ObjectMapper());
		List<AssistantEvent> events = adapter.adapt(
				"execute_code",
				"{\"success\":true,\"result\":{\"leave_id\":12345}}",
				(Map<String, Object>) null);

		assertEquals(2, events.size());
		assertEquals("EXECUTION_PROGRESS", events.get(0).getType());
		assertEquals("DONE", events.get(1).getType());
		assertEquals(Boolean.TRUE, events.get(1).getPayload().get("success"));
	}

	@Test
	void shouldAdaptReplyToMessageEvent() {
		V3ProtocolAdapter adapter = new V3ProtocolAdapter(new ObjectMapper());
		List<AssistantEvent> events = adapter.adapt(
				"reply",
				"{\"message\":\"请假已提交\"}",
				(Map<String, Object>) null);

		assertEquals(1, events.size());
		assertEquals("MESSAGE", events.get(0).getType());
		assertEquals("请假已提交", events.get(0).getPayload().get("text"));
	}
}
