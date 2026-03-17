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
package com.alibaba.assistant.agent.runtime.agent;

import com.alibaba.cloud.ai.graph.OverAllState;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.lang.Nullable;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Resolves the current user input from runtime state while tolerating stale checkpoint fields.
 *
 * <p>When a thread resumes, the checkpoint may still carry the previous {@code input} value while
 * the latest turn has already been appended to {@code messages}. In that case the trailing user
 * message is treated as the fresher source only when the persisted {@code input} still matches the
 * previous collected input.</p>
 *
 * @author Assistant Agent Team
 * @since 1.0.0
 */
public final class ConversationUserInputResolver {

	private ConversationUserInputResolver() {
	}

	public static String resolve(@Nullable OverAllState state) {
		return resolve(null, state, null);
	}

	public static String resolve(
			@Nullable String explicitInput,
			@Nullable OverAllState state,
			@Nullable List<? extends Message> messages) {
		String currentInput = firstNonBlank(
				explicitInput,
				readStateText(state, "input"));
		String query = readStateText(state, "query");
		String lastCollectInput = readStateText(state, AssistantStateKeys.LAST_COLLECT_USER_INPUT);
		Object rawMessages = messages != null ? messages : readStateValue(state, "messages");

		String trailingUserMessage = resolveTrailingUserMessage(rawMessages);
		if (shouldPreferTrailingUserMessage(trailingUserMessage, currentInput, lastCollectInput)) {
			return trailingUserMessage;
		}

		return firstNonBlank(
				currentInput,
				query,
				resolveLatestUserMessage(rawMessages));
	}

	private static boolean shouldPreferTrailingUserMessage(
			@Nullable String trailingUserMessage,
			@Nullable String currentInput,
			@Nullable String lastCollectInput) {
		if (!StringUtils.hasText(trailingUserMessage)) {
			return false;
		}
		if (!StringUtils.hasText(currentInput)) {
			return true;
		}
		if (equalsNormalized(trailingUserMessage, currentInput)) {
			return true;
		}
		return StringUtils.hasText(lastCollectInput)
				&& equalsNormalized(currentInput, lastCollectInput)
				&& !equalsNormalized(trailingUserMessage, lastCollectInput);
	}

	private static String resolveTrailingUserMessage(@Nullable Object rawMessages) {
		if (!(rawMessages instanceof List<?> messages) || messages.isEmpty()) {
			return null;
		}
		return extractUserText(messages.get(messages.size() - 1));
	}

	private static String resolveLatestUserMessage(@Nullable Object rawMessages) {
		if (!(rawMessages instanceof List<?> messages) || messages.isEmpty()) {
			return null;
		}
		for (int index = messages.size() - 1; index >= 0; index--) {
			String text = extractUserText(messages.get(index));
			if (StringUtils.hasText(text)) {
				return text;
			}
		}
		return null;
	}

	private static String extractUserText(@Nullable Object rawMessage) {
		if (rawMessage instanceof UserMessage userMessage) {
			return textOf(userMessage.getText());
		}
		if (rawMessage instanceof Map<?, ?> rawMap) {
			String role = firstNonBlank(
					textOf(rawMap.get("messageType")),
					textOf(rawMap.get("type")),
					textOf(rawMap.get("role")),
					textOf(rawMap.get("messageRole")),
					textOf(rawMap.get("message_role")));
			if (!isUserRole(role)) {
				return null;
			}
			return firstNonBlank(
					textOf(rawMap.get("text")),
					textOf(rawMap.get("content")));
		}
		return null;
	}

	private static boolean isUserRole(@Nullable String role) {
		if (!StringUtils.hasText(role)) {
			return false;
		}
		String normalized = role.trim().toUpperCase(Locale.ROOT);
		return "USER".equals(normalized) || "HUMAN".equals(normalized);
	}

	private static boolean equalsNormalized(@Nullable String left, @Nullable String right) {
		return normalize(left).equals(normalize(right));
	}

	private static String normalize(@Nullable String text) {
		if (!StringUtils.hasText(text)) {
			return "";
		}
		return text.replaceAll("\\s+", "").trim();
	}

	private static String readStateText(@Nullable OverAllState state, String key) {
		return textOf(readStateValue(state, key));
	}

	private static Object readStateValue(@Nullable OverAllState state, String key) {
		if (state == null || !StringUtils.hasText(key)) {
			return null;
		}
		return state.value(key, Object.class).orElse(null);
	}

	private static String textOf(@Nullable Object value) {
		if (value == null) {
			return null;
		}
		String text = String.valueOf(value).trim();
		return StringUtils.hasText(text) ? text : null;
	}

	private static String firstNonBlank(String... values) {
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
}
