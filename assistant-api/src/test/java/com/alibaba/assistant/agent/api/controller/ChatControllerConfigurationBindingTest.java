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
package com.alibaba.assistant.agent.api.controller;

import com.alibaba.cloud.ai.agent.studio.loader.AgentLoader;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class ChatControllerConfigurationBindingTest {

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
			.withPropertyValues("spring.profiles.active=migration")
			.withBean(AgentLoader.class, () -> mock(AgentLoader.class))
			.withBean(ChatController.class);

	@Test
	void shouldFallbackToLegacyCurrentSystemProperty() {
		contextRunner
				.withPropertyValues(
						"assistant.auth.current-system.default-system-code=legacy-system",
						"assistant.chat.default-space-code=finance-space",
						"assistant.chat.default-space-environment=test")
				.run(context -> {
					assertThat(context).hasSingleBean(ChatController.class);
					ChatController controller = context.getBean(ChatController.class);
					assertThat(ReflectionTestUtils.getField(controller, "defaultSystemCode")).isEqualTo("legacy-system");
					assertThat(ReflectionTestUtils.getField(controller, "defaultSpaceCode")).isEqualTo("finance-space");
					assertThat(ReflectionTestUtils.getField(controller, "defaultSpaceEnvironment")).isEqualTo("test");
				});
	}

	@Test
	void shouldPreferChatDefaultSystemPropertyWhenPresent() {
		contextRunner
				.withPropertyValues(
						"assistant.chat.default-system-code=chat-system",
						"assistant.auth.current-system.default-system-code=legacy-system")
				.run(context -> {
					assertThat(context).hasSingleBean(ChatController.class);
					ChatController controller = context.getBean(ChatController.class);
					assertThat(ReflectionTestUtils.getField(controller, "defaultSystemCode")).isEqualTo("chat-system");
				});
	}

}
