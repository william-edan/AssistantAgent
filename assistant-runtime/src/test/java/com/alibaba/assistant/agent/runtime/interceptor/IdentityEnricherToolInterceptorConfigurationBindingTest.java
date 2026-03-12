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

import com.alibaba.assistant.agent.controlplane.identity.TokenBroker;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class IdentityEnricherToolInterceptorConfigurationBindingTest {

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
			.withPropertyValues("spring.profiles.active=migration")
			.withBean(TokenBroker.class, () -> mock(TokenBroker.class))
			.withBean(ObjectMapper.class, ObjectMapper::new)
			.withBean(IdentityEnricherToolInterceptor.class);

	@Test
	void shouldFallbackToLegacyCurrentSystemProperty() {
		contextRunner
				.withPropertyValues("assistant.auth.current-system.default-system-code=legacy-system")
				.run(context -> {
					assertThat(context).hasSingleBean(IdentityEnricherToolInterceptor.class);
					IdentityEnricherToolInterceptor interceptor =
							context.getBean(IdentityEnricherToolInterceptor.class);
					assertThat(ReflectionTestUtils.getField(interceptor, "defaultSystemCode"))
							.isEqualTo("legacy-system");
				});
	}

	@Test
	void shouldPreferChatDefaultSystemPropertyWhenPresent() {
		contextRunner
				.withPropertyValues(
						"assistant.chat.default-system-code=chat-system",
						"assistant.auth.current-system.default-system-code=legacy-system")
				.run(context -> {
					assertThat(context).hasSingleBean(IdentityEnricherToolInterceptor.class);
					IdentityEnricherToolInterceptor interceptor =
							context.getBean(IdentityEnricherToolInterceptor.class);
					assertThat(ReflectionTestUtils.getField(interceptor, "defaultSystemCode"))
							.isEqualTo("chat-system");
				});
	}

}
