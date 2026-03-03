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
package com.alibaba.assistant.agent.runtime.prompt;

import com.alibaba.assistant.agent.extension.experience.model.Experience;
import com.alibaba.assistant.agent.extension.experience.model.ExperienceMetadata;
import com.alibaba.assistant.agent.extension.experience.model.ExperienceQuery;
import com.alibaba.assistant.agent.extension.experience.model.ExperienceQueryContext;
import com.alibaba.assistant.agent.extension.experience.model.ExperienceType;
import com.alibaba.assistant.agent.extension.experience.spi.ExperienceProvider;
import com.alibaba.assistant.agent.runtime.config.RuntimeConfigCompatibilityAdapter;
import com.alibaba.assistant.agent.prompt.PromptContribution;
import com.alibaba.assistant.agent.prompt.PromptContributor;
import com.alibaba.assistant.agent.prompt.PromptContributorContext;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.context.annotation.Profile;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Injects high-quality sanitized experience snippets into dynamic prompt context.
 *
 * @author Assistant Agent Team
 * @since 1.0.0
 */
@Component
@Profile("migration")
public class ExperienceContributor implements PromptContributor {

	private static final int QUERY_LIMIT = 10;

	private static final int MAX_INJECTED_EXPERIENCES = 3;

	private static final int MAX_EXPERIENCE_CONTENT = 600;

	private static final double MIN_CONFIDENCE = 0.60d;

	private static final Pattern EMAIL_PATTERN =
			Pattern.compile("(?i)\\b[\\w.%+-]+@[\\w.-]+\\.[a-z]{2,}\\b");

	private static final Pattern PHONE_PATTERN =
			Pattern.compile("(?<!\\d)(?:1\\d{10}|\\d{3}-\\d{3}-\\d{4})(?!\\d)");

	private static final Pattern SECRET_PATTERN =
			Pattern.compile("(?i)(access[_-]?token|api[_-]?key|secret|password)\\s*[:=]\\s*([^\\s,;]+)");

	private final ExperienceProvider experienceProvider;

	private final RuntimeConfigCompatibilityAdapter compatibilityAdapter;

	public ExperienceContributor(
			@Nullable ExperienceProvider experienceProvider,
			RuntimeConfigCompatibilityAdapter compatibilityAdapter) {
		this.experienceProvider = experienceProvider;
		this.compatibilityAdapter = compatibilityAdapter;
	}

	@Override
	public String getName() {
		return "experience";
	}

	@Override
	public int getPriority() {
		return 300;
	}

	@Override
	public boolean shouldContribute(PromptContributorContext context) {
		return experienceProvider != null && compatibilityAdapter.promptDynamicEnabled();
	}

	@Override
	public PromptContribution contribute(PromptContributorContext context) {
		if (!shouldContribute(context)) {
			return PromptContribution.empty();
		}

		ExperienceQuery query = new ExperienceQuery(ExperienceType.REACT);
		query.setLimit(QUERY_LIMIT);
		ExperienceQueryContext queryContext = buildQueryContext(context);
		List<Experience> source = experienceProvider.query(query, queryContext);
		if (source == null || source.isEmpty()) {
			return PromptContribution.empty();
		}

		List<Experience> qualified = source.stream()
				.filter(this::isQualified)
				.sorted(Comparator
						.comparingDouble(this::confidenceOrDefault)
						.reversed())
				.limit(MAX_INJECTED_EXPERIENCES)
				.toList();
		if (qualified.isEmpty()) {
			return PromptContribution.empty();
		}

		String content = renderExperienceContent(qualified);
		if (!StringUtils.hasText(content)) {
			return PromptContribution.empty();
		}
		return PromptContribution.builder()
				.append(new UserMessage(content))
				.build();
	}

	private ExperienceQueryContext buildQueryContext(PromptContributorContext context) {
		Map<String, Object> attrs = context.getAttributes();
		ExperienceQueryContext queryContext = new ExperienceQueryContext();
		queryContext.setUserQuery(resolveUserQuery(attrs, context.getMessages()));
		queryContext.setUserId(firstNonBlank(asText(attrs.get("user_id")), asText(attrs.get("userId"))));
		queryContext.setProjectId(firstNonBlank(asText(attrs.get("project_id")), asText(attrs.get("projectId"))));
		queryContext.setRepoId(firstNonBlank(asText(attrs.get("repo_id")), asText(attrs.get("repoId"))));
		queryContext.setTaskType(firstNonBlank(asText(attrs.get("task_type")), asText(attrs.get("taskType"))));
		queryContext.setLanguage(asText(attrs.get("language")));
		return queryContext;
	}

	private String resolveUserQuery(Map<String, Object> attrs, List<Message> messages) {
		String fromAttr = firstNonBlank(asText(attrs.get("input")), asText(attrs.get("query")));
		if (StringUtils.hasText(fromAttr)) {
			return fromAttr;
		}
		if (messages == null || messages.isEmpty()) {
			return null;
		}
		for (int i = messages.size() - 1; i >= 0; i--) {
			Message message = messages.get(i);
			if (message instanceof UserMessage userMessage && StringUtils.hasText(userMessage.getText())) {
				return userMessage.getText();
			}
		}
		return null;
	}

	private boolean isQualified(Experience experience) {
		if (experience == null) {
			return false;
		}
		ExperienceMetadata metadata = experience.getMetadata();
		if (metadata == null || metadata.getConfidence() == null) {
			return true;
		}
		return metadata.getConfidence() >= MIN_CONFIDENCE;
	}

	private double confidenceOrDefault(Experience experience) {
		if (experience == null || experience.getMetadata() == null || experience.getMetadata().getConfidence() == null) {
			return MIN_CONFIDENCE;
		}
		return experience.getMetadata().getConfidence();
	}

	private String renderExperienceContent(List<Experience> experiences) {
		List<String> lines = new ArrayList<>();
		lines.add("【可复用经验】");
		for (Experience exp : experiences) {
			String title = asText(exp.getTitle());
			String content = truncate(sanitize(safeExperienceContent(exp)), MAX_EXPERIENCE_CONTENT);
			if (!StringUtils.hasText(content)) {
				continue;
			}
			if (StringUtils.hasText(title)) {
				lines.add("- " + title + ": " + content);
			}
			else {
				lines.add("- " + content);
			}
		}
		if (lines.size() <= 1) {
			return null;
		}
		return String.join("\n", lines);
	}

	private String safeExperienceContent(Experience experience) {
		if (experience == null) {
			return null;
		}
		String content = experience.getEffectiveContent();
		if (StringUtils.hasText(content)) {
			return content;
		}
		return experience.getContent();
	}

	private String sanitize(String content) {
		if (!StringUtils.hasText(content)) {
			return null;
		}
		String sanitized = EMAIL_PATTERN.matcher(content).replaceAll("[REDACTED]");
		sanitized = PHONE_PATTERN.matcher(sanitized).replaceAll("[REDACTED]");
		sanitized = SECRET_PATTERN.matcher(sanitized).replaceAll("$1=[REDACTED]");
		return sanitized;
	}

	private String truncate(String text, int maxLen) {
		if (!StringUtils.hasText(text) || maxLen <= 0 || text.length() <= maxLen) {
			return text;
		}
		return text.substring(0, maxLen) + "...";
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

}
