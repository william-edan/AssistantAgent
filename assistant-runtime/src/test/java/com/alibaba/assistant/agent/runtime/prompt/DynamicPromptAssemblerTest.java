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

import com.alibaba.assistant.agent.prompt.PromptContribution;
import com.alibaba.assistant.agent.prompt.PromptContributorContext;
import com.alibaba.assistant.agent.prompt.PromptContributorManager;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;

import java.util.Collections;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.*;

class DynamicPromptAssemblerTest {

	@Test
	void shouldDelegateAssembleToPromptContributorManager() {
		PromptContributorManager manager = mock(PromptContributorManager.class);
		PromptContribution expected = PromptContribution.builder().build();
		when(manager.assemble(any(PromptContributorContext.class))).thenReturn(expected);

		DynamicPromptAssembler assembler = new DynamicPromptAssembler(manager);
		PromptContributorContext context = new PromptContributorContext() {
			@Override
			public java.util.List<Message> getMessages() {
				return Collections.emptyList();
			}

			@Override
			public Optional<SystemMessage> getSystemMessage() {
				return Optional.empty();
			}

			@Override
			public Map<String, Object> getAttributes() {
				return Map.of();
			}

			@Override
			public Optional<String> getPhase() {
				return Optional.of("REACT");
			}
		};

		PromptContribution actual = assembler.assemble(context);

		assertSame(expected, actual);
		verify(manager, times(1)).assemble(context);
	}

}
