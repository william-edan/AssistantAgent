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
import com.alibaba.assistant.agent.prompt.PromptContributorContext;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ExperienceContributorTest {

	@Test
	void shouldSkipWhenDynamicPromptDisabled() {
		ExperienceProvider experienceProvider = mock(ExperienceProvider.class);
		RuntimeConfigCompatibilityAdapter adapter = mock(RuntimeConfigCompatibilityAdapter.class);
		when(adapter.promptDynamicEnabled()).thenReturn(false);

		ExperienceContributor contributor = new ExperienceContributor(experienceProvider, adapter);
		boolean shouldContribute = contributor.shouldContribute(context(Map.of(), List.of()));

		assertFalse(shouldContribute);
	}

	@Test
	void shouldReturnEmptyContributionWhenNoExperienceFound() {
		ExperienceProvider experienceProvider = mock(ExperienceProvider.class);
		RuntimeConfigCompatibilityAdapter adapter = mock(RuntimeConfigCompatibilityAdapter.class);
		when(adapter.promptDynamicEnabled()).thenReturn(true);
		when(experienceProvider.query(any(ExperienceQuery.class), any(ExperienceQueryContext.class)))
				.thenReturn(List.of());

		ExperienceContributor contributor = new ExperienceContributor(experienceProvider, adapter);
		PromptContribution contribution = contributor.contribute(context(
				Map.of("input", "我要请假"),
				List.of(new UserMessage("我要请假"))));

		assertTrue(contribution.isEmpty());
	}

	@Test
	void shouldInjectSanitizedHighQualityExperienceOnly() {
		ExperienceProvider experienceProvider = mock(ExperienceProvider.class);
		RuntimeConfigCompatibilityAdapter adapter = mock(RuntimeConfigCompatibilityAdapter.class);
		when(adapter.promptDynamicEnabled()).thenReturn(true);

		Experience highQuality = experience("请假经验",
				"联系人 test@example.com, password=abc123",
				0.92d);
		Experience lowQuality = experience("低质量经验",
				"这个经验不应该被注入",
				0.20d);
		when(experienceProvider.query(any(ExperienceQuery.class), any(ExperienceQueryContext.class)))
				.thenReturn(List.of(lowQuality, highQuality));

		ExperienceContributor contributor = new ExperienceContributor(experienceProvider, adapter);
		PromptContribution contribution = contributor.contribute(context(
				Map.of("input", "请帮我发起请假", "user_id", "u1"),
				List.of(new UserMessage("请帮我发起请假"))));

		assertEquals(1, contribution.messagesToAppend().size());
		String text = contribution.messagesToAppend().get(0).getText();
		assertTrue(text.contains("请假经验"));
		assertFalse(text.contains("低质量经验"));
		assertFalse(text.contains("test@example.com"));
		assertFalse(text.contains("abc123"));
		assertTrue(text.contains("[REDACTED]"));
	}

	@Test
	void shouldQueryReactExperienceWithContextFromState() {
		ExperienceProvider experienceProvider = mock(ExperienceProvider.class);
		RuntimeConfigCompatibilityAdapter adapter = mock(RuntimeConfigCompatibilityAdapter.class);
		when(adapter.promptDynamicEnabled()).thenReturn(true);
		when(experienceProvider.query(any(ExperienceQuery.class), any(ExperienceQueryContext.class)))
				.thenReturn(List.of(experience("经验", "内容", null)));

		ExperienceContributor contributor = new ExperienceContributor(experienceProvider, adapter);
		contributor.contribute(context(
				Map.of("input", "查询当前登录用户", "user_id", "user-1", "project_id", "proj-1"),
				List.of(new UserMessage("查询当前登录用户"))));

		ArgumentCaptor<ExperienceQuery> queryCaptor = ArgumentCaptor.forClass(ExperienceQuery.class);
		ArgumentCaptor<ExperienceQueryContext> contextCaptor = ArgumentCaptor.forClass(ExperienceQueryContext.class);
		verify(experienceProvider, times(1)).query(queryCaptor.capture(), contextCaptor.capture());
		assertEquals(ExperienceType.REACT, queryCaptor.getValue().getType());
		assertEquals("查询当前登录用户", contextCaptor.getValue().getUserQuery());
		assertEquals("user-1", contextCaptor.getValue().getUserId());
		assertEquals("proj-1", contextCaptor.getValue().getProjectId());
	}

	private Experience experience(String title, String content, Double confidence) {
		Experience exp = new Experience();
		exp.setType(ExperienceType.REACT);
		exp.setTitle(title);
		exp.setContent(content);
		if (confidence != null) {
			ExperienceMetadata metadata = new ExperienceMetadata();
			metadata.setConfidence(confidence);
			exp.setMetadata(metadata);
		}
		return exp;
	}

	private PromptContributorContext context(Map<String, Object> attrs, List<Message> messages) {
		return new PromptContributorContext() {
			@Override
			public List<Message> getMessages() {
				return messages != null ? messages : Collections.emptyList();
			}

			@Override
			public Optional<SystemMessage> getSystemMessage() {
				return Optional.empty();
			}

			@Override
			public Map<String, Object> getAttributes() {
				return attrs;
			}

			@Override
			public Optional<String> getPhase() {
				return Optional.of("REACT");
			}
		};
	}

}
