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
package com.alibaba.assistant.agent.runtime.intent;

import com.alibaba.assistant.agent.extension.experience.fastintent.FastIntentContext;
import com.alibaba.assistant.agent.extension.experience.fastintent.FastIntentService;
import com.alibaba.assistant.agent.extension.experience.model.Experience;
import com.alibaba.assistant.agent.extension.experience.model.ExperienceQuery;
import com.alibaba.assistant.agent.extension.experience.model.ExperienceQueryContext;
import com.alibaba.assistant.agent.extension.experience.model.ExperienceType;
import com.alibaba.assistant.agent.extension.experience.spi.ExperienceProvider;
import com.alibaba.assistant.agent.runtime.config.RuntimeConfigCompatibilityAdapter;
import com.alibaba.cloud.ai.graph.OverAllState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Hybrid intent router for migration runtime:
 * only fast-intent short path is decided here, otherwise fallback to main flow.
 *
 * @author Assistant Agent Team
 * @since 1.0.0
 */
@Component
@Profile("migration")
public class AssistantIntentRouter {

	private static final Logger logger = LoggerFactory.getLogger(AssistantIntentRouter.class);

	private static final int FAST_INTENT_QUERY_LIMIT = 10;

	private final ExperienceProvider experienceProvider;

	private final FastIntentService fastIntentService;

	private final RuntimeConfigCompatibilityAdapter compatibilityAdapter;

	public AssistantIntentRouter(
			ExperienceProvider experienceProvider,
			FastIntentService fastIntentService,
			RuntimeConfigCompatibilityAdapter compatibilityAdapter) {
		this.experienceProvider = experienceProvider;
		this.fastIntentService = fastIntentService;
		this.compatibilityAdapter = compatibilityAdapter;
	}

	public IntentResult route(String input, OverAllState state) {
		return route(input, state, Collections.emptyMap());
	}

	public IntentResult route(String input, OverAllState state, Map<String, Object> configMetadata) {
		if (!compatibilityAdapter.fastIntentEnabled()) {
			return IntentResult.mainFlow();
		}

		try {
			List<Message> messages = resolveMessages(state);
			String resolvedInput = resolveInput(input, state, messages);

			ExperienceQueryContext queryContext = buildQueryContext(state, configMetadata, resolvedInput);
			ExperienceQuery query = new ExperienceQuery(ExperienceType.REACT);
			query.setLimit(FAST_INTENT_QUERY_LIMIT);
			List<Experience> candidates = experienceProvider.query(query, queryContext);
			if (candidates == null || candidates.isEmpty()) {
				return IntentResult.mainFlow();
			}

			FastIntentContext fastIntentContext =
					new FastIntentContext(resolvedInput, messages, safeMetadata(configMetadata), state, null);
			Optional<Experience> matched = fastIntentService.selectBestMatch(candidates, fastIntentContext);
			if (matched.isPresent()) {
				logger.info(
						"AssistantIntentRouter#route - reason=fast-intent matched, experienceId={}",
						matched.get().getId());
				return IntentResult.fastIntent(matched.get());
			}

		}
		catch (Exception ex) {
			logger.warn("AssistantIntentRouter#route - reason=fast-intent route failed, fallback to main flow, error={}",
					ex.getMessage());
		}

		return IntentResult.mainFlow();
	}

	@SuppressWarnings("unchecked")
	private List<Message> resolveMessages(OverAllState state) {
		if (state == null) {
			return List.of();
		}
		return state.value("messages", List.class).orElse(List.of());
	}

	private String resolveInput(String input, OverAllState state, List<Message> messages) {
		if (StringUtils.hasText(input)) {
			return input;
		}
		if (state != null) {
			String stateInput = state.value("input", String.class).orElse(null);
			if (StringUtils.hasText(stateInput)) {
				return stateInput;
			}
		}
		if (messages != null) {
			for (int i = messages.size() - 1; i >= 0; i--) {
				Message message = messages.get(i);
				if (message instanceof UserMessage userMessage && StringUtils.hasText(userMessage.getText())) {
					return userMessage.getText();
				}
			}
		}
		return null;
	}

	private ExperienceQueryContext buildQueryContext(
			OverAllState state,
			Map<String, Object> configMetadata,
			String userQuery) {
		ExperienceQueryContext context = new ExperienceQueryContext();
		context.setUserQuery(userQuery);
		context.setUserId(firstNonBlank(readStateString(state, "user_id"), readStateString(state, "userId")));
		context.setProjectId(firstNonBlank(readStateString(state, "project_id"), readStateString(state, "projectId")));
		context.setRepoId(firstNonBlank(readStateString(state, "repo_id"), readStateString(state, "repoId")));
		context.setTaskType(firstNonBlank(
				readStateString(state, "task_type"),
				asText(configMetadata.get("task_type"))));
		context.setAgentName(asText(configMetadata.get("agent_name")));
		context.setAgentType(asText(configMetadata.get("agent_type")));
		context.setLanguage(firstNonBlank(
				readStateString(state, "language"),
				asText(configMetadata.get("language"))));
		return context;
	}

	private String readStateString(OverAllState state, String key) {
		if (state == null || !StringUtils.hasText(key)) {
			return null;
		}
		Object value = state.value(key).orElse(null);
		return asText(value);
	}

	private Map<String, Object> safeMetadata(Map<String, Object> metadata) {
		return metadata != null ? metadata : Collections.emptyMap();
	}

	private String asText(Object value) {
		if (value == null) {
			return null;
		}
		String text = String.valueOf(value).trim();
		return StringUtils.hasText(text) ? text : null;
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

	public record IntentResult(AssistantIntentType type, Experience experience) {

		public static IntentResult fastIntent(Experience experience) {
			return new IntentResult(AssistantIntentType.FAST_INTENT, experience);
		}

		public static IntentResult mainFlow() {
			return new IntentResult(AssistantIntentType.MAIN_FLOW, null);
		}

		public Optional<Experience> matchedExperience() {
			return Optional.ofNullable(experience);
		}

	}

}
