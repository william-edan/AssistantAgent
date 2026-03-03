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
package com.alibaba.assistant.agent.extension.prompt;

import com.alibaba.assistant.agent.common.hook.AgentPhase;
import com.alibaba.assistant.agent.common.hook.HookPhases;
import com.alibaba.assistant.agent.prompt.PromptContribution;
import com.alibaba.assistant.agent.prompt.PromptContributorManager;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PromptContributorModelHookTest {

	@Test
	void shouldInjectContributionAsPlainContextMessage() {
		PromptContributorManager manager = mock(PromptContributorManager.class);
		when(manager.assemble(any())).thenReturn(PromptContribution.builder()
				.append(new UserMessage("tool catalog A"))
				.append(new UserMessage("policy B"))
				.build());
		PromptContributorModelHook hook = new TestReactPromptContributorModelHook(manager);

		Map<String, Object> updates = hook.beforeModel(new OverAllState(), RunnableConfig.builder().build()).join();
		assertNotNull(updates);
		assertTrue(updates.containsKey("messages"));

		@SuppressWarnings("unchecked")
		List<Message> messages = (List<Message>) updates.get("messages");
		assertEquals(1, messages.size());
		assertInstanceOf(UserMessage.class, messages.get(0));
		assertTrue(messages.get(0).getText().contains("【系统补充上下文】"));
		assertTrue(messages.get(0).getText().contains("tool catalog A"));
		assertTrue(messages.get(0).getText().contains("policy B"));
		assertFalse(messages.get(0).getText().contains("__prompt_contribution__"));
		assertFalse(messages.get(0).getText().contains("prompt_contribution"));
	}

	@Test
	void shouldReturnEmptyUpdateWhenContributionEmpty() {
		PromptContributorManager manager = mock(PromptContributorManager.class);
		when(manager.assemble(any())).thenReturn(PromptContribution.empty());
		PromptContributorModelHook hook = new TestReactPromptContributorModelHook(manager);

		Map<String, Object> updates = hook.beforeModel(new OverAllState(), RunnableConfig.builder().build()).join();
		assertNotNull(updates);
		assertTrue(updates.isEmpty());
	}

	@HookPhases(AgentPhase.REACT)
	private static final class TestReactPromptContributorModelHook extends PromptContributorModelHook {

		private TestReactPromptContributorModelHook(PromptContributorManager contributorManager) {
			super(contributorManager);
		}
	}

}
