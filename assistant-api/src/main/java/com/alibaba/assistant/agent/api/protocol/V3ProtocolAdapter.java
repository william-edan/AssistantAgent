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

import com.alibaba.cloud.ai.graph.OverAllState;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * V3 protocol adapter.
 * Converts internal tool outputs to v3-style assistant events consumed by frontend.
 *
 * @author Assistant Agent Team
 * @since 1.0.0
 */
@Component
@Profile("migration")
public class V3ProtocolAdapter {

	private final ObjectMapper objectMapper;

	public V3ProtocolAdapter(ObjectMapper objectMapper) {
		this.objectMapper = objectMapper;
	}

	/**
	 * Adapt an incoming V3 request to the internal format.
	 */
	public Object adaptRequest(Object v3Request) {
		return v3Request;
	}

	/**
	 * Adapt internal tool output to v3 events.
	 *
	 * @param toolName tool name
	 * @param toolOutput tool output string (JSON/plain text)
	 * @param state optional OverAllState
	 * @return v3 events list
	 */
	public List<AssistantEvent> adapt(String toolName, String toolOutput, OverAllState state) {
		return adapt(toolName, toolOutput, (Map<String, Object>) null);
	}

	/**
	 * Adapt internal tool output to v3 events.
	 *
	 * @param toolName tool name
	 * @param toolOutput tool output string (JSON/plain text)
	 * @param state optional state map
	 * @return v3 events list
	 */
	public List<AssistantEvent> adapt(String toolName, String toolOutput, Map<String, Object> state) {
		try {
			return switch (toolName) {
				case "slot_collect" -> adaptSlotProgress(toolOutput);
				case "slot_confirm" -> adaptConfirmUi(toolOutput);
				case "execute_code" -> adaptExecutionProgress(toolOutput);
				case "reply" -> adaptMessage(toolOutput);
				default -> Collections.singletonList(AssistantEvent.stage(
						toolName != null ? toolName : "UNKNOWN",
						parsePayload(toolOutput)));
			};
		}
		catch (Exception e) {
			Map<String, Object> payload = new LinkedHashMap<>();
			payload.put("toolName", toolName);
			payload.put("rawOutput", toolOutput);
			payload.put("error", e.getMessage());
			return Collections.singletonList(AssistantEvent.error("protocol_adapter_error", payload));
		}
	}

	/**
	 * Serialize events as JSON string.
	 */
	public String toJson(List<AssistantEvent> events) {
		try {
			return objectMapper.writeValueAsString(events != null ? events : Collections.emptyList());
		}
		catch (Exception e) {
			return "[]";
		}
	}

	/**
	 * Adapt an internal response object to v3 format.
	 */
	public Object adaptResponse(Object internalResponse) {
		return internalResponse;
	}

	private List<AssistantEvent> adaptSlotProgress(String toolOutput) {
		Map<String, Object> payload = parsePayload(toolOutput);
		payload.putIfAbsent("eventType", "SLOT_PROGRESS");
		return Collections.singletonList(AssistantEvent.slotProgress(payload));
	}

	private List<AssistantEvent> adaptConfirmUi(String toolOutput) {
		Map<String, Object> payload = parsePayload(toolOutput);
		Object formPayload = payload.getOrDefault("confirmForm", payload);
		Map<String, Object> uiPayload = new LinkedHashMap<>();
		uiPayload.put("component", "ConfirmForm");
		uiPayload.put("data", formPayload);
		return Collections.singletonList(AssistantEvent.ui("ConfirmForm", uiPayload));
	}

	private List<AssistantEvent> adaptExecutionProgress(String toolOutput) {
		Map<String, Object> payload = parsePayload(toolOutput);
		List<AssistantEvent> events = new ArrayList<>(2);
		events.add(AssistantEvent.executionProgress(payload));

		Map<String, Object> donePayload = new LinkedHashMap<>();
		donePayload.put("success", payload.getOrDefault("success", Boolean.TRUE));
		donePayload.put("result", payload.get("result"));
		donePayload.put("error", payload.get("error"));
		events.add(AssistantEvent.done(donePayload));
		return events;
	}

	private List<AssistantEvent> adaptMessage(String toolOutput) {
		Map<String, Object> payload = parsePayload(toolOutput);
		Object textObj = payload.get("message");
		if (textObj == null) {
			textObj = payload.get("content");
		}
		if (textObj == null && StringUtils.hasText(toolOutput)) {
			textObj = toolOutput;
		}
		Map<String, Object> messagePayload = new LinkedHashMap<>();
		messagePayload.put("text", textObj != null ? textObj : "");
		return Collections.singletonList(AssistantEvent.message(messagePayload));
	}

	private Map<String, Object> parsePayload(String toolOutput) {
		if (!StringUtils.hasText(toolOutput)) {
			return new LinkedHashMap<>();
		}
		try {
			return objectMapper.readValue(toolOutput, new TypeReference<Map<String, Object>>() {
			});
		}
		catch (Exception e) {
			Map<String, Object> payload = new LinkedHashMap<>();
			payload.put("raw", toolOutput);
			return payload;
		}
	}

	/**
	 * V3 assistant event.
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public static class AssistantEvent {

		private String type;

		private String component;

		private String stage;

		private Map<String, Object> payload;

		public static AssistantEvent slotProgress(Map<String, Object> payload) {
			AssistantEvent event = new AssistantEvent();
			event.type = "SLOT_PROGRESS";
			event.payload = payload;
			return event;
		}

		public static AssistantEvent ui(String component, Map<String, Object> payload) {
			AssistantEvent event = new AssistantEvent();
			event.type = "UI";
			event.component = component;
			event.payload = payload;
			return event;
		}

		public static AssistantEvent executionProgress(Map<String, Object> payload) {
			AssistantEvent event = new AssistantEvent();
			event.type = "EXECUTION_PROGRESS";
			event.payload = payload;
			return event;
		}

		public static AssistantEvent done(Map<String, Object> payload) {
			AssistantEvent event = new AssistantEvent();
			event.type = "DONE";
			event.payload = payload;
			return event;
		}

		public static AssistantEvent message(Map<String, Object> payload) {
			AssistantEvent event = new AssistantEvent();
			event.type = "MESSAGE";
			event.payload = payload;
			return event;
		}

		public static AssistantEvent stage(String stage, Map<String, Object> payload) {
			AssistantEvent event = new AssistantEvent();
			event.type = "STAGE";
			event.stage = stage;
			event.payload = payload;
			return event;
		}

		public static AssistantEvent error(String stage, Map<String, Object> payload) {
			AssistantEvent event = new AssistantEvent();
			event.type = "ERROR";
			event.stage = stage;
			event.payload = payload;
			return event;
		}

		public String getType() {
			return type;
		}

		public String getComponent() {
			return component;
		}

		public String getStage() {
			return stage;
		}

		public Map<String, Object> getPayload() {
			return payload;
		}
	}
}
