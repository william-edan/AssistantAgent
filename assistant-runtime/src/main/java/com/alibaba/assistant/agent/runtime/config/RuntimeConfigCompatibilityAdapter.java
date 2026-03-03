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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Compatibility adapter for migration-era config namespaces.
 *
 * <p>Resolution rule:
 * 1. If legacy key exists under {@code spring.ai.alibaba.codeact.extension.*}, use legacy value.
 * 2. Otherwise, use value from {@code assistant.runtime.*}.
 *
 * <p>Legacy-key-first keeps current migration deployments stable while new namespace is introduced.
 *
 * @author Assistant Agent Team
 * @since 1.0.0
 */
@Component
@Profile("migration")
public class RuntimeConfigCompatibilityAdapter {

	private static final Logger logger = LoggerFactory.getLogger(RuntimeConfigCompatibilityAdapter.class);

	private static final String LEGACY_FAST_INTENT_ENABLED =
			"spring.ai.alibaba.codeact.extension.experience.fast-intent-enabled";
	private static final String LEGACY_PROMPT_ENABLED =
			"spring.ai.alibaba.codeact.extension.prompt.enabled";
	private static final String LEGACY_PROMPT_REACT_ENABLED =
			"spring.ai.alibaba.codeact.extension.prompt.react.enabled";
	private static final String LEGACY_PROMPT_CODEACT_ENABLED =
			"spring.ai.alibaba.codeact.extension.prompt.codeact.enabled";
	private static final String LEGACY_LEARNING_ASYNC_ENABLED =
			"spring.ai.alibaba.codeact.extension.learning.async.enabled";
	private static final String LEGACY_SEARCH_ENABLED =
			"spring.ai.alibaba.codeact.extension.search.enabled";

	private final AssistantRuntimeProperties runtimeProperties;

	private final Environment environment;

	public RuntimeConfigCompatibilityAdapter(AssistantRuntimeProperties runtimeProperties, Environment environment) {
		this.runtimeProperties = runtimeProperties;
		this.environment = environment;
	}

	/**
	 * Effective fast-intent switch.
	 */
	public boolean fastIntentEnabled() {
		return resolveBoolean(
				LEGACY_FAST_INTENT_ENABLED,
				runtimeProperties.getFastIntent().isEnabled(),
				"assistant.runtime.fast-intent.enabled");
	}

	/**
	 * Effective dynamic prompt switch.
	 */
	public boolean promptDynamicEnabled() {
		return resolveBoolean(
				LEGACY_PROMPT_ENABLED,
				runtimeProperties.getPrompt().isDynamicEnabled(),
				"assistant.runtime.prompt.dynamic-enabled");
	}

	/**
	 * Effective react phase prompt hook switch.
	 */
	public boolean promptReactEnabled() {
		return resolveBoolean(
				LEGACY_PROMPT_REACT_ENABLED,
				runtimeProperties.getPrompt().isReactEnabled(),
				"assistant.runtime.prompt.react-enabled");
	}

	/**
	 * Effective codeact phase prompt hook switch.
	 */
	public boolean promptCodeactEnabled() {
		return resolveBoolean(
				LEGACY_PROMPT_CODEACT_ENABLED,
				runtimeProperties.getPrompt().isCodeactEnabled(),
				"assistant.runtime.prompt.codeact-enabled");
	}

	/**
	 * Effective max tools in prompt.
	 */
	public int promptMaxToolsInPrompt() {
		return runtimeProperties.getPrompt().getMaxToolsInPrompt();
	}

	/**
	 * Effective async-learning switch.
	 */
	public boolean learningAsyncEnabled() {
		return resolveBoolean(
				LEGACY_LEARNING_ASYNC_ENABLED,
				runtimeProperties.getLearning().isAsync(),
				"assistant.runtime.learning.async");
	}

	/**
	 * Effective search switch.
	 */
	public boolean searchEnabled() {
		return resolveBoolean(
				LEGACY_SEARCH_ENABLED,
				runtimeProperties.getSearch().isEnabled(),
				"assistant.runtime.search.enabled");
	}

	/**
	 * Effective policy-guard switch.
	 */
	public boolean policyGuardEnabled() {
		return runtimeProperties.getPolicyGuard().isEnabled();
	}

	/**
	 * Effective max latency budget for one request.
	 */
	public long budgetMaxLatencyMs() {
		return runtimeProperties.getBudget().getMaxLatencyMs();
	}

	/**
	 * Effective max tool call budget for one request.
	 */
	public int budgetMaxToolCalls() {
		return runtimeProperties.getBudget().getMaxToolCalls();
	}

	private boolean resolveBoolean(String legacyKey, boolean runtimeValue, String runtimeKey) {
		if (environment.containsProperty(legacyKey)) {
			boolean value = environment.getProperty(legacyKey, Boolean.class, runtimeValue);
			logger.debug(
					"RuntimeConfigCompatibilityAdapter#resolveBoolean - source=legacy, key={}, value={}, fallbackKey={}",
					legacyKey, value, runtimeKey);
			return value;
		}
		logger.debug(
				"RuntimeConfigCompatibilityAdapter#resolveBoolean - source=runtime, key={}, value={}",
				runtimeKey, runtimeValue);
		return runtimeValue;
	}

}
