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
package com.alibaba.assistant.agent.runtime.interceptor;

import com.alibaba.assistant.agent.common.constant.CodeactStateKeys;
import com.alibaba.assistant.agent.runtime.agent.AssistantStateKeys;
import com.alibaba.assistant.agent.runtime.agent.ConversationUserInputResolver;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelCallHandler;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelInterceptor;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelRequest;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelResponse;
import com.alibaba.cloud.ai.graph.agent.hook.JumpTo;
import com.alibaba.cloud.ai.graph.agent.tools.ToolContextConstants;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.Locale;

/**
 * Policy guard for model tool-call decisions.
 * Enforces allowlist from state key {@link CodeactStateKeys#AVAILABLE_TOOL_NAMES}.
 *
 * @author Assistant Agent Team
 * @since 1.0.0
 */
@Component
@Profile("migration")
public class PolicyCheckModelInterceptor extends ModelInterceptor {

	private static final Logger logger = LoggerFactory.getLogger(PolicyCheckModelInterceptor.class);
	private static final String WAITING_FOR_USER_INPUT_MESSAGE = "我已记录当前信息，请先补充缺失的必填内容，我收到你的输入后继续处理。";
	private static final String WAITING_FOR_CONFIRM_INPUT_MESSAGE = "请先明确回复“确认提交”或“取消”，我再继续执行。";

    private final ObjectMapper objectMapper;

    public PolicyCheckModelInterceptor() {
        this(new ObjectMapper());
    }

    @Autowired
    public PolicyCheckModelInterceptor(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

	@Override
	public ModelResponse interceptModel(ModelRequest request, ModelCallHandler handler) {
		OverAllState state = resolveState(request);
		AllowlistPolicy allowlistPolicy = resolveAllowlistPolicy(state);
		Set<String> allowlist = allowlistPolicy.allowlist();
		ExecutionGate executionGate = resolveExecutionGate(state);
		StaleInputMode staleInputMode = resolveStaleInputMode(state);
		boolean formFlowExtractionPending = isFormFlowExtractionPending(state);
		if (staleInputMode != StaleInputMode.NONE) {
			clearStaleToolJump(state);
		}
		ModelRequest sanitizedRequest = sanitizeRequest(
				request,
				allowlistPolicy,
				executionGate,
				staleInputMode,
				formFlowExtractionPending);
		ModelResponse response = handler.call(sanitizedRequest);
		if (formFlowExtractionPending) {
			response = rewriteFormFlowExtractionResponse(response, state);
		}
		if (staleInputMode != StaleInputMode.NONE) {
			response = sanitizeStaleInputResponse(response, staleInputMode);
		}
		if (allowlist.isEmpty() && !allowlistPolicy.explicitlyConfigured() && executionGate.isUnrestricted()) {
			return response;
		}
		validateToolCalls(response, allowlistPolicy, executionGate);
		return response;
	}

	@Override
	public String getName() {
		return "PolicyCheckModelInterceptor";
	}

	private boolean isFormFlowExtractionPending(OverAllState state) {
		return Boolean.TRUE.equals(readStateBoolean(state, AssistantStateKeys.FORM_FLOW_EXTRACTION_PENDING));
	}

	private ModelResponse rewriteFormFlowExtractionResponse(ModelResponse response, OverAllState state) {
		clearFormFlowExtractionPending(state);
		String matchedToolCode = resolveMatchedToolCode(state);
		if (!StringUtils.hasText(matchedToolCode)) {
			return response;
		}
		switchJumpToTool(state);
		FormFlowExtractionPayload payload = parseFormFlowExtractionPayload(response);
		Map<String, Object> args = new LinkedHashMap<>();
		args.put("toolCode", matchedToolCode);
		args.put("extractedSlots", payload.extractedSlots());
		if (StringUtils.hasText(payload.displayMessage())) {
			args.put("displayMessage", payload.displayMessage());
		}
		AssistantMessage assistantMessage = AssistantMessage.builder()
				.content("")
				.toolCalls(List.of(new AssistantMessage.ToolCall(
						"form_flow_extract_" + UUID.randomUUID().toString().substring(0, 8),
						"function",
						"slot_collect",
						toJson(args))))
				.build();
		return ModelResponse.of(assistantMessage);
	}

	private void clearFormFlowExtractionPending(OverAllState state) {
		if (state == null) {
			return;
		}
		state.updateState(Map.of(AssistantStateKeys.FORM_FLOW_EXTRACTION_PENDING, Boolean.FALSE));
	}

	private void switchJumpToTool(OverAllState state) {
		if (state == null) {
			return;
		}
		state.updateState(Map.of("jump_to", JumpTo.tool));
	}

	private FormFlowExtractionPayload parseFormFlowExtractionPayload(ModelResponse response) {
		if (response == null || !(response.getMessage() instanceof AssistantMessage assistantMessage)) {
			return new FormFlowExtractionPayload(Collections.emptyMap(), null);
		}
		String rawText = asText(assistantMessage.getText());
		if (!StringUtils.hasText(rawText)) {
			return new FormFlowExtractionPayload(Collections.emptyMap(), null);
		}
		String jsonText = extractJsonPayloadText(rawText);
		try {
			@SuppressWarnings("unchecked")
			Map<String, Object> payload = objectMapper.readValue(jsonText, Map.class);
			return new FormFlowExtractionPayload(
					normalizeObjectMap(payload.get("extractedSlots")),
					firstNonBlank(asText(payload.get("displayMessage")), asText(payload.get("display_message"))));
		}
		catch (Exception ex) {
			logger.warn(
					"PolicyCheckModelInterceptor#rewriteFormFlowExtractionResponse - invalid extraction payload, error={}",
					ex.getMessage());
			return new FormFlowExtractionPayload(Collections.emptyMap(), rawText);
		}
	}

	private String extractJsonPayloadText(String rawText) {
		if (!StringUtils.hasText(rawText)) {
			return rawText;
		}
		String trimmed = rawText.trim();
		if (trimmed.startsWith("```")) {
			int firstLineBreak = trimmed.indexOf('\n');
			int lastFence = trimmed.lastIndexOf("```");
			if (firstLineBreak >= 0 && lastFence > firstLineBreak) {
				trimmed = trimmed.substring(firstLineBreak + 1, lastFence).trim();
			}
		}
		int firstBrace = trimmed.indexOf('{');
		int lastBrace = trimmed.lastIndexOf('}');
		if (firstBrace >= 0 && lastBrace > firstBrace) {
			return trimmed.substring(firstBrace, lastBrace + 1);
		}
		return trimmed;
	}

	private Map<String, Object> normalizeObjectMap(Object raw) {
		if (!(raw instanceof Map<?, ?> rawMap) || rawMap.isEmpty()) {
			return Collections.emptyMap();
		}
		Map<String, Object> normalized = new LinkedHashMap<>();
		rawMap.forEach((key, value) -> {
			if (key != null) {
				normalized.put(String.valueOf(key), value);
			}
		});
		return normalized;
	}

	private String resolveMatchedToolCode(OverAllState state) {
		Object raw = state != null ? state.value(AssistantStateKeys.MATCHED_TOOL_META, Object.class).orElse(null) : null;
		if (raw instanceof Map<?, ?> rawMap) {
			String toolCode = asText(rawMap.get("toolCode"));
			if (!StringUtils.hasText(toolCode)) {
				toolCode = asText(rawMap.get("tool_code"));
			}
			return toolCode;
		}
		if (raw != null) {
			try {
				@SuppressWarnings("unchecked")
				Map<String, Object> converted = objectMapper.convertValue(raw, Map.class);
				String toolCode = asText(converted.get("toolCode"));
				if (!StringUtils.hasText(toolCode)) {
					toolCode = asText(converted.get("tool_code"));
				}
				return toolCode;
			}
			catch (IllegalArgumentException ignored) {
				// Ignore and fallback.
			}
		}
		return null;
	}

	private String toJson(Map<String, Object> value) {
		try {
			return objectMapper.writeValueAsString(value != null ? value : Collections.emptyMap());
		}
		catch (Exception ex) {
			logger.warn("PolicyCheckModelInterceptor#toJson - fallback to empty json, error={}", ex.getMessage());
			return "{}";
		}
	}

	private OverAllState resolveState(ModelRequest request) {
		Map<String, Object> context = request != null ? request.getContext() : null;
		if (CollectionUtils.isEmpty(context)) {
			return null;
		}
		Object stateObject = context.get(ToolContextConstants.AGENT_STATE_CONTEXT_KEY);
		if (stateObject instanceof OverAllState state) {
			return state;
		}
		return null;
	}

	private AllowlistPolicy resolveAllowlistPolicy(OverAllState state) {
		if (state == null) {
			return AllowlistPolicy.notConfigured();
		}

		Object raw = state.value(CodeactStateKeys.AVAILABLE_TOOL_NAMES).orElse(null);
		if (!(raw instanceof List<?> rawList)) {
			return AllowlistPolicy.notConfigured();
		}

		Set<String> allowlist = new LinkedHashSet<>();
		for (Object item : rawList) {
			String toolName = item != null ? String.valueOf(item).trim() : null;
			if (StringUtils.hasText(toolName)) {
				allowlist.add(toolName);
			}
		}
		return AllowlistPolicy.explicit(allowlist);
	}

	private ExecutionGate resolveExecutionGate(OverAllState state) {
		// Execute tool confirmation is handled by HumanInTheLoopHook at the framework level.
		// No need to filter execute tools from the model context.
		return ExecutionGate.unrestricted();
	}

	private ModelRequest sanitizeRequest(
			ModelRequest request,
			AllowlistPolicy allowlistPolicy,
			ExecutionGate executionGate,
			StaleInputMode staleInputMode,
			boolean formFlowExtractionPending) {
		List<Message> sanitizedMessages = sanitizeMessages(request.getMessages());
		boolean blockTools = staleInputMode != StaleInputMode.NONE || formFlowExtractionPending;
		Map<String, String> filteredToolDescriptions = blockTools
				? Collections.emptyMap()
				: filterToolDescriptions(
						request.getToolDescriptions(),
						allowlistPolicy,
						executionGate);
		List<String> filteredTools = blockTools
				? Collections.emptyList()
				: filterToolNames(
						request.getTools(),
						allowlistPolicy,
						executionGate);
		List<ToolCallback> filteredDynamicTools = blockTools
				? Collections.emptyList()
				: filterDynamicToolCallbacks(
						request.getDynamicToolCallbacks(),
						allowlistPolicy,
						executionGate);

		return ModelRequest.builder(request)
				.messages(sanitizedMessages)
				.toolDescriptions(filteredToolDescriptions)
				.tools(filteredTools)
				.dynamicToolCallbacks(filteredDynamicTools)
				.build();
	}

	private List<Message> sanitizeMessages(List<Message> source) {
		if (source == null) {
			return null;
		}
		boolean changed = false;
		int droppedAssistantMessages = 0;
		int droppedToolResponseMessages = 0;
		List<Message> sanitized = new ArrayList<>(source.size());
		for (int i = 0; i < source.size();) {
			Message message = source.get(i);
			if (message instanceof AssistantMessage assistantMessage && assistantMessage.hasToolCalls()) {
				List<ToolResponseMessage> contiguousToolResponses = new ArrayList<>();
				int nextIndex = i + 1;
				while (nextIndex < source.size() && source.get(nextIndex) instanceof ToolResponseMessage toolResponseMessage) {
					contiguousToolResponses.add(toolResponseMessage);
					nextIndex++;
				}
				AssistantToolPairSanitizeResult pairResult = sanitizeAssistantToolPair(
						assistantMessage, contiguousToolResponses);
				if (pairResult.assistantMessage() != null) {
					sanitized.add(pairResult.assistantMessage());
				}
				sanitized.addAll(pairResult.toolResponseMessages());
				changed = changed || pairResult.changed();
				droppedAssistantMessages += pairResult.droppedAssistantMessages();
				droppedToolResponseMessages += pairResult.droppedToolResponseMessages();
				i = nextIndex;
				continue;
			}
			if (message instanceof ToolResponseMessage) {
				changed = true;
				droppedToolResponseMessages++;
				i++;
				continue;
			}
			sanitized.add(message);
			i++;
		}
		if (changed) {
			logger.warn(
					"PolicyCheckModelInterceptor#sanitizeMessages - repaired invalid tool messages, "
							+ "droppedAssistantMessages={}, droppedToolResponseMessages={}",
					droppedAssistantMessages, droppedToolResponseMessages);
			return sanitized;
		}
		return source;
	}

	private AssistantToolPairSanitizeResult sanitizeAssistantToolPair(
			AssistantMessage assistantMessage,
			List<ToolResponseMessage> contiguousToolResponses) {
		List<AssistantMessage.ToolCall> originalToolCalls = assistantMessage.getToolCalls();
		Set<String> contiguousResponseIds = new LinkedHashSet<>();
		for (ToolResponseMessage toolResponseMessage : contiguousToolResponses) {
			if (toolResponseMessage == null || CollectionUtils.isEmpty(toolResponseMessage.getResponses())) {
				continue;
			}
			for (ToolResponseMessage.ToolResponse toolResponse : toolResponseMessage.getResponses()) {
				if (toolResponse != null && StringUtils.hasText(toolResponse.id())) {
					contiguousResponseIds.add(toolResponse.id());
				}
			}
		}

		List<AssistantMessage.ToolCall> keptToolCalls = new ArrayList<>();
		for (AssistantMessage.ToolCall toolCall : originalToolCalls) {
			if (toolCall != null
					&& StringUtils.hasText(toolCall.id())
					&& contiguousResponseIds.contains(toolCall.id())) {
				keptToolCalls.add(toolCall);
			}
		}
		Set<String> keptToolCallIds = new LinkedHashSet<>();
		for (AssistantMessage.ToolCall toolCall : keptToolCalls) {
			if (toolCall != null && StringUtils.hasText(toolCall.id())) {
				keptToolCallIds.add(toolCall.id());
			}
		}

		boolean changed = keptToolCalls.size() != originalToolCalls.size();
		int droppedToolResponseMessages = 0;
		List<Message> sanitizedToolResponses = new ArrayList<>();
		for (ToolResponseMessage toolResponseMessage : contiguousToolResponses) {
			List<ToolResponseMessage.ToolResponse> originalResponses = toolResponseMessage.getResponses();
			if (CollectionUtils.isEmpty(originalResponses)) {
				droppedToolResponseMessages++;
				changed = true;
				continue;
			}
			List<ToolResponseMessage.ToolResponse> filteredResponses = new ArrayList<>();
			for (ToolResponseMessage.ToolResponse response : originalResponses) {
				if (response != null
						&& StringUtils.hasText(response.id())
						&& keptToolCallIds.contains(response.id())) {
					filteredResponses.add(response);
				}
			}
			if (filteredResponses.isEmpty()) {
				droppedToolResponseMessages++;
				changed = true;
				continue;
			}
			if (filteredResponses.size() == originalResponses.size()) {
				sanitizedToolResponses.add(toolResponseMessage);
				continue;
			}
			changed = true;
			Map<String, Object> metadata = toolResponseMessage.getMetadata() != null
					? new LinkedHashMap<>(toolResponseMessage.getMetadata())
					: Collections.emptyMap();
			sanitizedToolResponses.add(ToolResponseMessage.builder()
					.responses(filteredResponses)
					.metadata(metadata)
					.build());
		}

		if (keptToolCalls.isEmpty()) {
			Message textOnlyAssistant = StringUtils.hasText(assistantMessage.getText())
					? AssistantMessage.builder().content(assistantMessage.getText()).build()
					: null;
			int droppedAssistantMessages = textOnlyAssistant == null ? 1 : 0;
			if (!contiguousToolResponses.isEmpty()) {
				changed = true;
			}
			return new AssistantToolPairSanitizeResult(
					textOnlyAssistant,
					Collections.emptyList(),
					changed,
					droppedAssistantMessages,
					droppedToolResponseMessages);
		}

		Message sanitizedAssistant = AssistantMessage.builder()
				.content(assistantMessage.getText())
				.toolCalls(keptToolCalls)
				.build();
		return new AssistantToolPairSanitizeResult(
				sanitizedAssistant,
				sanitizedToolResponses,
				changed,
				0,
				droppedToolResponseMessages);
	}

	private Map<String, String> filterToolDescriptions(
			Map<String, String> toolDescriptions,
			AllowlistPolicy allowlistPolicy,
			ExecutionGate executionGate) {
		if (CollectionUtils.isEmpty(toolDescriptions)) {
			return Collections.emptyMap();
		}
		Map<String, String> filtered = new LinkedHashMap<>();
		for (Map.Entry<String, String> entry : toolDescriptions.entrySet()) {
			String toolName = entry.getKey();
			if (isAllowed(toolName, allowlistPolicy, executionGate)) {
				filtered.put(entry.getKey(), entry.getValue());
			}
		}
		return filtered;
	}

	private List<String> filterToolNames(
			List<String> source,
			AllowlistPolicy allowlistPolicy,
			ExecutionGate executionGate) {
		if (source == null) {
			return null;
		}
		List<String> filtered = new ArrayList<>();
		for (String toolName : source) {
			if (!StringUtils.hasText(toolName) || isAllowed(toolName, allowlistPolicy, executionGate)) {
				filtered.add(toolName);
			}
		}
		return filtered;
	}

	private List<ToolCallback> filterDynamicToolCallbacks(
			List<ToolCallback> source,
			AllowlistPolicy allowlistPolicy,
			ExecutionGate executionGate) {
		if (source == null) {
			return null;
		}
		List<ToolCallback> filtered = new ArrayList<>();
		for (ToolCallback callback : source) {
				String name = callback != null && callback.getToolDefinition() != null
						? callback.getToolDefinition().name()
						: null;
				if (!StringUtils.hasText(name) || isAllowed(name, allowlistPolicy, executionGate)) {
					filtered.add(callback);
				}
		}
		return filtered;
	}

	private boolean isAllowed(String toolName, AllowlistPolicy allowlistPolicy, ExecutionGate executionGate) {
		if (!StringUtils.hasText(toolName)) {
			return true;
		}
		if (allowlistPolicy.explicitlyConfigured() && !allowlistPolicy.allowlist().contains(toolName)) {
			return false;
		}
		return executionGate.isAllowed(toolName);
	}

	private void validateToolCalls(ModelResponse response, AllowlistPolicy allowlistPolicy, ExecutionGate executionGate) {
		if (response == null || response.getMessage() == null) {
			return;
		}

		Object messageObject = response.getMessage();
		if (!(messageObject instanceof AssistantMessage assistantMessage) || !assistantMessage.hasToolCalls()) {
			return;
		}

		for (AssistantMessage.ToolCall toolCall : assistantMessage.getToolCalls()) {
			if (!isAllowed(toolCall.name(), allowlistPolicy, executionGate)) {
				logger.warn(
						"PolicyCheckModelInterceptor#validateToolCalls - blocked unauthorized tool, toolName={}, allowlist={}",
						toolCall.name(), allowlistPolicy.allowlist());
				throw new IllegalStateException("Unauthorized tool call blocked: " + toolCall.name());
			}
		}
	}

	private ModelResponse sanitizeStaleInputResponse(ModelResponse response, StaleInputMode staleInputMode) {
		if (response == null || response.getMessage() == null) {
			return response;
		}
		Object messageObject = response.getMessage();
		if (!(messageObject instanceof AssistantMessage assistantMessage) || !assistantMessage.hasToolCalls()) {
			return response;
		}
		String content = StringUtils.hasText(assistantMessage.getText())
				? assistantMessage.getText()
				: staleInputMode.defaultMessage();
		logger.info(
				"PolicyCheckModelInterceptor#sanitizeStaleInputResponse - blocked tool calls, mode={}, droppedToolCalls={}",
				staleInputMode.name(),
				assistantMessage.getToolCalls().size());
		return ModelResponse.of(AssistantMessage.builder()
				.content(content)
				.build());
	}

	private StaleInputMode resolveStaleInputMode(OverAllState state) {
		if (!hasSameInputAsLastCollect(state)) {
			return StaleInputMode.NONE;
		}
		if (isBlockingCollectingPhase(state)) {
			return StaleInputMode.COLLECTING_OR_BLOCKED;
		}
		if (isBlockingConfirmingPhase(state)) {
			return StaleInputMode.CONFIRMING;
		}
		return StaleInputMode.NONE;
	}

	private boolean hasSameInputAsLastCollect(OverAllState state) {
		String lastCollectInput = readStateString(state, AssistantStateKeys.LAST_COLLECT_USER_INPUT);
		String currentInput = resolveCurrentUserInput(state);
		if (!StringUtils.hasText(lastCollectInput) || !StringUtils.hasText(currentInput)) {
			return false;
		}
		return normalizeForComparison(lastCollectInput).equals(normalizeForComparison(currentInput));
	}

	private boolean isBlockingCollectingPhase(OverAllState state) {
		String phase = readStateString(state, AssistantStateKeys.CONVERSATION_PHASE);
		if (!StringUtils.hasText(phase)) {
			return false;
		}
		String normalized = phase.trim().toUpperCase(Locale.ROOT);
		return "COLLECTING".equals(normalized) || "BLOCKED".equals(normalized);
	}

	private boolean isBlockingConfirmingPhase(OverAllState state) {
		String phase = readStateString(state, AssistantStateKeys.CONVERSATION_PHASE);
		if (!StringUtils.hasText(phase)) {
			return false;
		}
		String normalized = phase.trim().toUpperCase(Locale.ROOT);
		return "CONFIRMING".equals(normalized);
	}

	private String resolveCurrentUserInput(OverAllState state) {
		return ConversationUserInputResolver.resolve(state);
	}

	private String firstNonBlank(String... values) {
		if (values == null || values.length == 0) {
			return null;
		}
		for (String value : values) {
			if (StringUtils.hasText(value)) {
				return value;
			}
		}
		return null;
	}

	private String asText(Object value) {
		if (value == null) {
			return null;
		}
		String text = String.valueOf(value).trim();
		return StringUtils.hasText(text) ? text : null;
	}

	private String normalizeForComparison(String text) {
		if (!StringUtils.hasText(text)) {
			return "";
		}
		return text.replaceAll("\\s+", "").trim().toLowerCase(Locale.ROOT);
	}

	private void clearStaleToolJump(OverAllState state) {
		if (state == null || !hasToolJump(state)) {
			return;
		}
		Map<String, Object> updates = new LinkedHashMap<>();
		updates.put("jump_to", null);
		state.updateState(updates);
	}

	private boolean hasToolJump(OverAllState state) {
		if (state == null) {
			return false;
		}
		Object jumpToRaw = state.value("jump_to", Object.class).orElse(null);
		if (jumpToRaw == null) {
			return false;
		}
		if (jumpToRaw instanceof Enum<?> enumValue) {
			return "tool".equalsIgnoreCase(enumValue.name());
		}
		String text = asText(jumpToRaw);
		return StringUtils.hasText(text) && "tool".equalsIgnoreCase(text);
	}

	private boolean isInConfirmingPhase(OverAllState state) {
		String phase = readStateString(state, AssistantStateKeys.CONVERSATION_PHASE);
		if (!StringUtils.hasText(phase)) {
			return false;
		}
		String normalized = phase.trim().toUpperCase(Locale.ROOT);
		return "CONFIRMING".equals(normalized) || "READY_TO_CONFIRM".equals(normalized);
	}

	private static boolean isExecuteToolName(String toolName) {
		return StringUtils.hasText(toolName)
				&& "artifact_execute".equalsIgnoreCase(toolName.trim());
	}

	private String readStateString(OverAllState state, String key) {
		if (state == null || !StringUtils.hasText(key)) {
			return null;
		}
		Object value = state.value(key, Object.class).orElse(null);
		if (value == null) {
			return null;
		}
		String text = String.valueOf(value).trim();
		return StringUtils.hasText(text) ? text : null;
	}

	private Boolean readStateBoolean(OverAllState state, String key) {
		if (state == null || !StringUtils.hasText(key)) {
			return null;
		}
		Object value = state.value(key, Object.class).orElse(null);
		if (value == null) {
			return null;
		}
		if (value instanceof Boolean bool) {
			return bool;
		}
		if (value instanceof Number number) {
			return number.intValue() != 0;
		}
		String text = String.valueOf(value).trim().toLowerCase(Locale.ROOT);
		if ("1".equals(text) || "true".equals(text) || "yes".equals(text) || "y".equals(text)) {
			return true;
		}
		if ("0".equals(text) || "false".equals(text) || "no".equals(text) || "n".equals(text)) {
			return false;
		}
		return null;
	}

	private record FormFlowExtractionPayload(Map<String, Object> extractedSlots, String displayMessage) {
	}

	private record AssistantToolPairSanitizeResult(
			Message assistantMessage,
			List<Message> toolResponseMessages,
			boolean changed,
			int droppedAssistantMessages,
			int droppedToolResponseMessages) {
	}

	private record AllowlistPolicy(Set<String> allowlist, boolean explicitlyConfigured) {

		private static AllowlistPolicy explicit(Set<String> allowlist) {
			return new AllowlistPolicy(allowlist != null ? allowlist : Collections.emptySet(), true);
		}

		private static AllowlistPolicy notConfigured() {
			return new AllowlistPolicy(Collections.emptySet(), false);
		}

	}

	private record ExecutionGate(Mode mode, String grantedExecuteToolName) {

		private static ExecutionGate unrestricted() {
			return new ExecutionGate(Mode.UNRESTRICTED, null);
		}

		private static ExecutionGate blockAllExecute() {
			return new ExecutionGate(Mode.BLOCK_ALL_EXECUTE, null);
		}

		private static ExecutionGate onlyExecuteTool(String toolName) {
			return new ExecutionGate(Mode.ONLY_GRANTED_EXECUTE, toolName);
		}

		private boolean isUnrestricted() {
			return mode == Mode.UNRESTRICTED;
		}

		private boolean isAllowed(String toolName) {
			if (!StringUtils.hasText(toolName) || !isExecuteToolName(toolName)) {
				return true;
			}
			if (mode == Mode.BLOCK_ALL_EXECUTE) {
				return false;
			}
			if (mode == Mode.ONLY_GRANTED_EXECUTE) {
				return StringUtils.hasText(grantedExecuteToolName)
						&& toolName.equalsIgnoreCase(grantedExecuteToolName);
			}
			return true;
		}

	}

	private enum Mode {
		UNRESTRICTED,
		BLOCK_ALL_EXECUTE,
		ONLY_GRANTED_EXECUTE
	}

	private enum StaleInputMode {
		NONE(""),
		COLLECTING_OR_BLOCKED(WAITING_FOR_USER_INPUT_MESSAGE),
		CONFIRMING(WAITING_FOR_CONFIRM_INPUT_MESSAGE);

		private final String defaultMessage;

		StaleInputMode(String defaultMessage) {
			this.defaultMessage = defaultMessage;
		}

		private String defaultMessage() {
			return defaultMessage;
		}
	}

}
