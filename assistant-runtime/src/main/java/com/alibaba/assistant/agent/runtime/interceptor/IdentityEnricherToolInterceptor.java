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
import com.alibaba.assistant.agent.controlplane.identity.TokenBroker;
import com.alibaba.assistant.agent.controlplane.identity.TokenLease;
import com.alibaba.assistant.agent.runtime.agent.AssistantStateKeys;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallHandler;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallRequest;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallResponse;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolInterceptor;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Injects identity/token lease info into tool call context and state before tool execution.
 *
 * @author Assistant Agent Team
 * @since 1.0.0
 */
@Component
@Profile("migration")
public class IdentityEnricherToolInterceptor extends ToolInterceptor {

	private static final Logger logger = LoggerFactory.getLogger(IdentityEnricherToolInterceptor.class);

	private final TokenBroker tokenBroker;

	private final ObjectMapper objectMapper;

	private final String defaultAssistantUid;

	private final String defaultSystemCode;

	public IdentityEnricherToolInterceptor(TokenBroker tokenBroker, ObjectMapper objectMapper) {
		this(tokenBroker, objectMapper, null, null);
	}

	@Autowired
	public IdentityEnricherToolInterceptor(TokenBroker tokenBroker, ObjectMapper objectMapper,
			@Value("${assistant.chat.default-assistant-uid:}") String defaultAssistantUid,
			@Value("${assistant.chat.default-system-code:}") String defaultSystemCode) {
		this.tokenBroker = tokenBroker;
		this.objectMapper = objectMapper;
		this.defaultAssistantUid = defaultAssistantUid;
		this.defaultSystemCode = defaultSystemCode;
	}

	@Override
	public ToolCallResponse interceptToolCall(ToolCallRequest request, ToolCallHandler handler) {
		OverAllState state = request.getExecutionContext().map(context -> context.state()).orElse(null);
		Map<String, Object> parsedArgs = parseArgs(request.getArguments());
		Map<String, Object> effectiveArgs = new LinkedHashMap<>(parsedArgs);
		Map<String, Object> requestContext = new HashMap<>();
		if (request.getContext() != null) {
			requestContext.putAll(request.getContext());
		}

		// Priority: state > request args > defaults.
		// request args may come from trusted gateway context (for example user_id), and should
		// override static defaults like "test_user".
		String assistantUid = firstNonBlank(
				readStateValue(state, AssistantStateKeys.ASSISTANT_UID),
				readStateValue(state, "user_id"),
				readStateValue(state, "userId"),
				readString(effectiveArgs, "assistant_uid", "assistantUid", "user_id", "userId"),
				defaultAssistantUid);
		String systemCode = firstNonBlank(
				readStateValue(state, AssistantStateKeys.SYSTEM_CODE),
				readString(effectiveArgs, "system_code", "systemCode"),
				defaultSystemCode);

		if (StringUtils.hasText(assistantUid)) {
			putIfMissingIdentityKey(effectiveArgs, "assistant_uid", assistantUid);
		}
		if (StringUtils.hasText(systemCode)) {
			putIfMissingIdentityKey(effectiveArgs, "system_code", systemCode);
		}

		// Write resolved identity back to state so downstream tools can read it
		if (state != null && StringUtils.hasText(assistantUid) && StringUtils.hasText(systemCode)) {
			Map<String, Object> stateUpdate = new HashMap<>();
			stateUpdate.put(AssistantStateKeys.ASSISTANT_UID, assistantUid);
			stateUpdate.put(AssistantStateKeys.SYSTEM_CODE, systemCode);
			state.updateState(stateUpdate);
		}

		ToolCallRequest.Builder requestBuilder = ToolCallRequest.builder(request)
				.arguments(writeArgs(effectiveArgs, request.getArguments()))
				.context(requestContext);

		if (!StringUtils.hasText(assistantUid) || !StringUtils.hasText(systemCode)) {
			logger.debug("IdentityEnricherToolInterceptor#interceptToolCall - skip due to missing identity, toolName={}",
					request.getToolName());
			return handler.call(requestBuilder.build());
		}

		Optional<TokenLease> leaseOpt = tokenBroker.acquire(assistantUid, systemCode);
		if (leaseOpt.isEmpty()) {
			logger.warn(
					"IdentityEnricherToolInterceptor#interceptToolCall - no token lease, toolName={}, assistantUid={}, systemCode={}",
					request.getToolName(), assistantUid, systemCode);
			return handler.call(requestBuilder.build());
		}

		TokenLease lease = leaseOpt.get();
		Map<String, Object> identityContext = buildIdentityContext(lease);
		if (state != null) {
			state.updateState(Map.of(AssistantStateKeys.IDENTITY_CONTEXT, identityContext));
		}
		requestContext.put(AssistantStateKeys.IDENTITY_CONTEXT, identityContext);

		ToolCallRequest enrichedRequest = requestBuilder.context(requestContext).build();

		logger.debug(
				"IdentityEnricherToolInterceptor#interceptToolCall - identity injected, "
						+ "toolName={}, assistantUid={}, systemCode={}",
				request.getToolName(), assistantUid, systemCode);
		return handler.call(enrichedRequest);
	}

	@Override
	public String getName() {
		return "IdentityEnricherToolInterceptor";
	}

	private Map<String, Object> buildIdentityContext(TokenLease lease) {
		Map<String, Object> context = new LinkedHashMap<>();
		context.put("assistant_uid", lease.assistantUid());
		context.put("system_code", lease.systemCode());
		context.put("lease_id", lease.leaseId());
		context.put("access_token", lease.accessToken());
		context.put("expires_at", lease.expiresAt() != null ? lease.expiresAt().toString() : null);
		return context;
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> parseArgs(String arguments) {
		if (!StringUtils.hasText(arguments)) {
			return Collections.emptyMap();
		}
		try {
			Map<String, Object> parsed = objectMapper.readValue(arguments, Map.class);
			return parsed != null ? parsed : Collections.emptyMap();
		}
		catch (Exception e) {
			logger.warn("IdentityEnricherToolInterceptor#parseArgs - failed, argumentsLength={}, error={}",
					arguments.length(), e.getMessage());
			return Collections.emptyMap();
		}
	}

	private String readStateValue(OverAllState state, String key) {
		if (state == null || !StringUtils.hasText(key)) {
			return null;
		}
		return state.value(key, String.class).orElse(null);
	}

	private String readString(Map<String, Object> map, String... keys) {
		if (map == null || keys == null) {
			return null;
		}
		for (String key : keys) {
			if (!StringUtils.hasText(key)) {
				continue;
			}
			Object value = map.get(key);
			if (value == null) {
				for (Map.Entry<String, Object> entry : map.entrySet()) {
					if (entry.getKey() != null && key.equalsIgnoreCase(entry.getKey())) {
						value = entry.getValue();
						break;
					}
				}
			}
			if (value != null) {
				String text = String.valueOf(value).trim();
				if (StringUtils.hasText(text)) {
					return text;
				}
			}
		}
		return null;
	}

	private String firstNonBlank(String... values) {
		if (values == null) {
			return null;
		}
		for (String value : values) {
			if (StringUtils.hasText(value)) {
				return value;
			}
		}
		return null;
	}

	private void putIfMissingIdentityKey(Map<String, Object> args, String key, String value) {
		if (args == null || !StringUtils.hasText(key) || !StringUtils.hasText(value)) {
			return;
		}
		if (containsKeyIgnoreCase(args, key)) {
			return;
		}
		args.put(key, value);
	}

	private boolean containsKeyIgnoreCase(Map<String, Object> map, String key) {
		if (map == null || !StringUtils.hasText(key)) {
			return false;
		}
		for (String existingKey : map.keySet()) {
			if (existingKey != null && key.equalsIgnoreCase(existingKey)) {
				return true;
			}
		}
		return false;
	}

	private String writeArgs(Map<String, Object> args, String fallbackRawArguments) {
		if (args == null) {
			return fallbackRawArguments;
		}
		try {
			return objectMapper.writeValueAsString(args);
		}
		catch (Exception e) {
			logger.warn("IdentityEnricherToolInterceptor#writeArgs - failed, error={}", e.getMessage());
			return fallbackRawArguments;
		}
	}

}
