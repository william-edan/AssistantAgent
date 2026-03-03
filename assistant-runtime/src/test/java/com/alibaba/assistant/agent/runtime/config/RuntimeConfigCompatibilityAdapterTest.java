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
package com.alibaba.assistant.agent.runtime.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeConfigCompatibilityAdapterTest {

	@Test
	void shouldUseRuntimeNamespaceWhenLegacyKeyMissing() {
		MockEnvironment environment = new MockEnvironment()
				.withProperty("assistant.runtime.fast-intent.enabled", "true")
				.withProperty("assistant.runtime.prompt.dynamic-enabled", "false")
				.withProperty("assistant.runtime.prompt.max-tools-in-prompt", "12")
				.withProperty("assistant.runtime.learning.async", "false")
				.withProperty("assistant.runtime.search.enabled", "false");

		AssistantRuntimeProperties runtime = bindRuntime(environment);
		RuntimeConfigCompatibilityAdapter adapter = new RuntimeConfigCompatibilityAdapter(runtime, environment);

		assertTrue(adapter.fastIntentEnabled());
		assertFalse(adapter.promptDynamicEnabled());
		assertEquals(12, adapter.promptMaxToolsInPrompt());
		assertFalse(adapter.learningAsyncEnabled());
		assertFalse(adapter.searchEnabled());
		assertTrue(adapter.policyGuardEnabled());
		assertEquals(6, adapter.budgetMaxToolCalls());
		assertEquals(12000L, adapter.budgetMaxLatencyMs());
	}

	@Test
	void shouldPreferLegacyKeysWhenBothLegacyAndRuntimeProvided() {
		MockEnvironment environment = new MockEnvironment()
				.withProperty("assistant.runtime.fast-intent.enabled", "false")
				.withProperty("assistant.runtime.prompt.dynamic-enabled", "false")
				.withProperty("assistant.runtime.prompt.react-enabled", "true")
				.withProperty("assistant.runtime.prompt.codeact-enabled", "true")
				.withProperty("assistant.runtime.learning.async", "false")
				.withProperty("assistant.runtime.search.enabled", "false")
				.withProperty("spring.ai.alibaba.codeact.extension.experience.fast-intent-enabled", "true")
				.withProperty("spring.ai.alibaba.codeact.extension.prompt.enabled", "true")
				.withProperty("spring.ai.alibaba.codeact.extension.prompt.react.enabled", "false")
				.withProperty("spring.ai.alibaba.codeact.extension.prompt.codeact.enabled", "false")
				.withProperty("spring.ai.alibaba.codeact.extension.learning.async.enabled", "true")
				.withProperty("spring.ai.alibaba.codeact.extension.search.enabled", "true")
				.withProperty("assistant.runtime.policy-guard.enabled", "false")
				.withProperty("assistant.runtime.budget.max-tool-calls", "9")
				.withProperty("assistant.runtime.budget.max-latency-ms", "15000");

		AssistantRuntimeProperties runtime = bindRuntime(environment);
		RuntimeConfigCompatibilityAdapter adapter = new RuntimeConfigCompatibilityAdapter(runtime, environment);

		assertTrue(adapter.fastIntentEnabled());
		assertTrue(adapter.promptDynamicEnabled());
		assertFalse(adapter.promptReactEnabled());
		assertFalse(adapter.promptCodeactEnabled());
		assertTrue(adapter.learningAsyncEnabled());
		assertTrue(adapter.searchEnabled());
		assertFalse(adapter.policyGuardEnabled());
		assertEquals(9, adapter.budgetMaxToolCalls());
		assertEquals(15000L, adapter.budgetMaxLatencyMs());
	}

	@Test
	void shouldFallbackToDefaultsWhenNoKeyProvided() {
		MockEnvironment environment = new MockEnvironment();

		AssistantRuntimeProperties runtime = bindRuntime(environment);
		RuntimeConfigCompatibilityAdapter adapter = new RuntimeConfigCompatibilityAdapter(runtime, environment);

		assertFalse(adapter.fastIntentEnabled());
		assertTrue(adapter.promptDynamicEnabled());
		assertTrue(adapter.promptReactEnabled());
		assertTrue(adapter.promptCodeactEnabled());
		assertEquals(20, adapter.promptMaxToolsInPrompt());
		assertTrue(adapter.learningAsyncEnabled());
		assertTrue(adapter.searchEnabled());
		assertTrue(adapter.policyGuardEnabled());
		assertEquals(6, adapter.budgetMaxToolCalls());
		assertEquals(12000L, adapter.budgetMaxLatencyMs());
	}

	private AssistantRuntimeProperties bindRuntime(MockEnvironment environment) {
		return Binder.get(environment)
				.bind("assistant.runtime", Bindable.of(AssistantRuntimeProperties.class))
				.orElseGet(AssistantRuntimeProperties::new);
	}

}
