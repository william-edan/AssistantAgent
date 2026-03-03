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

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Runtime properties for migration orchestration capabilities.
 *
 * <p>Note: during migration, values can be overridden by legacy keys under
 * {@code spring.ai.alibaba.codeact.extension.*} through
 * {@link RuntimeConfigCompatibilityAdapter}.
 *
 * @author Assistant Agent Team
 * @since 1.0.0
 */
@Component
@Profile("migration")
@ConfigurationProperties(prefix = "assistant.runtime")
public class AssistantRuntimeProperties {

	private final FastIntent fastIntent = new FastIntent();

	private final Prompt prompt = new Prompt();

	private final Learning learning = new Learning();

	private final Search search = new Search();

	private final PolicyGuard policyGuard = new PolicyGuard();

	private final Budget budget = new Budget();

	public FastIntent getFastIntent() {
		return fastIntent;
	}

	public Prompt getPrompt() {
		return prompt;
	}

	public Learning getLearning() {
		return learning;
	}

	public Search getSearch() {
		return search;
	}

	public PolicyGuard getPolicyGuard() {
		return policyGuard;
	}

	public Budget getBudget() {
		return budget;
	}

	public static class FastIntent {

		private boolean enabled = false;

		public boolean isEnabled() {
			return enabled;
		}

		public void setEnabled(boolean enabled) {
			this.enabled = enabled;
		}

	}

	public static class Prompt {

		private boolean dynamicEnabled = true;

		private boolean reactEnabled = true;

		private boolean codeactEnabled = true;

		private int maxToolsInPrompt = 20;

		public boolean isDynamicEnabled() {
			return dynamicEnabled;
		}

		public void setDynamicEnabled(boolean dynamicEnabled) {
			this.dynamicEnabled = dynamicEnabled;
		}

		public boolean isReactEnabled() {
			return reactEnabled;
		}

		public void setReactEnabled(boolean reactEnabled) {
			this.reactEnabled = reactEnabled;
		}

		public boolean isCodeactEnabled() {
			return codeactEnabled;
		}

		public void setCodeactEnabled(boolean codeactEnabled) {
			this.codeactEnabled = codeactEnabled;
		}

		public int getMaxToolsInPrompt() {
			return maxToolsInPrompt;
		}

		public void setMaxToolsInPrompt(int maxToolsInPrompt) {
			this.maxToolsInPrompt = maxToolsInPrompt;
		}

	}

	public static class Learning {

		private boolean enabled = true;

		private boolean async = true;

		public boolean isEnabled() {
			return enabled;
		}

		public void setEnabled(boolean enabled) {
			this.enabled = enabled;
		}

		public boolean isAsync() {
			return async;
		}

		public void setAsync(boolean async) {
			this.async = async;
		}

	}

	public static class Search {

		private boolean enabled = true;

		public boolean isEnabled() {
			return enabled;
		}

		public void setEnabled(boolean enabled) {
			this.enabled = enabled;
		}

	}

	public static class PolicyGuard {

		private boolean enabled = true;

		public boolean isEnabled() {
			return enabled;
		}

		public void setEnabled(boolean enabled) {
			this.enabled = enabled;
		}

	}

	public static class Budget {

		private long maxLatencyMs = 12_000L;

		private int maxToolCalls = 6;

		public long getMaxLatencyMs() {
			return maxLatencyMs;
		}

		public void setMaxLatencyMs(long maxLatencyMs) {
			this.maxLatencyMs = maxLatencyMs;
		}

		public int getMaxToolCalls() {
			return maxToolCalls;
		}

		public void setMaxToolCalls(int maxToolCalls) {
			this.maxToolCalls = maxToolCalls;
		}

	}

}
