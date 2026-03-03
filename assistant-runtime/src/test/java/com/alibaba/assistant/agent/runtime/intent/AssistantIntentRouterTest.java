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
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AssistantIntentRouterTest {

	@Test
	void shouldReturnMainFlowWhenFastIntentDisabled() {
		ExperienceProvider experienceProvider = mock(ExperienceProvider.class);
		FastIntentService fastIntentService = mock(FastIntentService.class);
		RuntimeConfigCompatibilityAdapter adapter = mock(RuntimeConfigCompatibilityAdapter.class);
		when(adapter.fastIntentEnabled()).thenReturn(false);

		AssistantIntentRouter router = new AssistantIntentRouter(experienceProvider, fastIntentService, adapter);

		AssistantIntentRouter.IntentResult result = router.route("发起请假", new OverAllState(), Map.of());

		assertEquals(AssistantIntentType.MAIN_FLOW, result.type());
		assertTrue(result.matchedExperience().isEmpty());
		verifyNoInteractions(experienceProvider, fastIntentService);
	}

	@Test
	void shouldReturnFastIntentWhenMatchedExperienceExists() {
		ExperienceProvider experienceProvider = mock(ExperienceProvider.class);
		FastIntentService fastIntentService = mock(FastIntentService.class);
		RuntimeConfigCompatibilityAdapter adapter = mock(RuntimeConfigCompatibilityAdapter.class);
		when(adapter.fastIntentEnabled()).thenReturn(true);

		Experience matched = new Experience();
		matched.setId("exp-1");
		matched.setType(ExperienceType.REACT);

		when(experienceProvider.query(any(ExperienceQuery.class), any(ExperienceQueryContext.class)))
				.thenReturn(List.of(matched));
		when(fastIntentService.selectBestMatch(anyList(), any(FastIntentContext.class)))
				.thenReturn(Optional.of(matched));

		AssistantIntentRouter router = new AssistantIntentRouter(experienceProvider, fastIntentService, adapter);
		OverAllState state = new OverAllState();
		state.updateState(Map.of(
				"input", "请帮我发起请假",
				"user_id", "u-1",
				"project_id", "proj-1",
				"messages", List.of(new UserMessage("请帮我发起请假"))));

		AssistantIntentRouter.IntentResult result = router.route(null, state, Map.of("channel", "chat"));

		assertEquals(AssistantIntentType.FAST_INTENT, result.type());
		assertEquals("exp-1", result.matchedExperience().map(Experience::getId).orElse(null));

		ArgumentCaptor<ExperienceQuery> queryCaptor = ArgumentCaptor.forClass(ExperienceQuery.class);
		ArgumentCaptor<ExperienceQueryContext> ctxCaptor = ArgumentCaptor.forClass(ExperienceQueryContext.class);
		verify(experienceProvider, times(1)).query(queryCaptor.capture(), ctxCaptor.capture());
		assertEquals(ExperienceType.REACT, queryCaptor.getValue().getType());
		assertEquals("请帮我发起请假", ctxCaptor.getValue().getUserQuery());
		assertEquals("u-1", ctxCaptor.getValue().getUserId());
		assertEquals("proj-1", ctxCaptor.getValue().getProjectId());
	}

	@Test
	void shouldUseStateInputWhenRouteInputIsBlank() {
		ExperienceProvider experienceProvider = mock(ExperienceProvider.class);
		FastIntentService fastIntentService = mock(FastIntentService.class);
		RuntimeConfigCompatibilityAdapter adapter = mock(RuntimeConfigCompatibilityAdapter.class);
		when(adapter.fastIntentEnabled()).thenReturn(true);

		Experience matched = new Experience();
		when(experienceProvider.query(any(ExperienceQuery.class), any(ExperienceQueryContext.class)))
				.thenReturn(List.of(matched));
		when(fastIntentService.selectBestMatch(anyList(), any(FastIntentContext.class)))
				.thenReturn(Optional.of(matched));

		AssistantIntentRouter router = new AssistantIntentRouter(experienceProvider, fastIntentService, adapter);
		OverAllState state = new OverAllState();
		state.updateState(Map.of("input", "查询当前登录用户"));

		router.route("   ", state, Map.of());

		ArgumentCaptor<FastIntentContext> fastIntentCaptor = ArgumentCaptor.forClass(FastIntentContext.class);
		verify(fastIntentService, times(1)).selectBestMatch(anyList(), fastIntentCaptor.capture());
		assertEquals("查询当前登录用户", fastIntentCaptor.getValue().getInput());
	}

	@Test
	void shouldFallbackToMainFlowWhenNoMatchedExperience() {
		ExperienceProvider experienceProvider = mock(ExperienceProvider.class);
		FastIntentService fastIntentService = mock(FastIntentService.class);
		RuntimeConfigCompatibilityAdapter adapter = mock(RuntimeConfigCompatibilityAdapter.class);
		when(adapter.fastIntentEnabled()).thenReturn(true);
		when(experienceProvider.query(any(ExperienceQuery.class), any(ExperienceQueryContext.class)))
				.thenReturn(List.of(new Experience()));
		when(fastIntentService.selectBestMatch(anyList(), any(FastIntentContext.class)))
				.thenReturn(Optional.empty());

		AssistantIntentRouter router = new AssistantIntentRouter(experienceProvider, fastIntentService, adapter);

		AssistantIntentRouter.IntentResult result = router.route("我要请假", new OverAllState(), Map.of());

		assertEquals(AssistantIntentType.MAIN_FLOW, result.type());
		assertTrue(result.matchedExperience().isEmpty());
	}

}
