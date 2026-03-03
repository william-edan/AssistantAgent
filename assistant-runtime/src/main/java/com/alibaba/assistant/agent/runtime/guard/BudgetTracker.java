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
package com.alibaba.assistant.agent.runtime.guard;

import com.alibaba.assistant.agent.runtime.config.RuntimeConfigCompatibilityAdapter;
import com.alibaba.cloud.ai.graph.OverAllState;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Tracks per-request budget usage in OverAllState.
 *
 * @author Assistant Agent Team
 * @since 1.0.0
 */
@Component
@Profile("migration")
public class BudgetTracker {

	static final String KEY_BUDGET_START_MS = "_budget_start_ms";

	static final String KEY_BUDGET_TOOL_CALLS = "_budget_tool_calls";

	private final RuntimeConfigCompatibilityAdapter compatibilityAdapter;

	public BudgetTracker(RuntimeConfigCompatibilityAdapter compatibilityAdapter) {
		this.compatibilityAdapter = compatibilityAdapter;
	}

	/**
	 * Initialize budget keys if absent.
	 */
	public void initIfAbsent(OverAllState state) {
		if (state == null) {
			return;
		}
		if (state.value(KEY_BUDGET_START_MS, Long.class).isPresent()
				&& state.value(KEY_BUDGET_TOOL_CALLS, Integer.class).isPresent()) {
			return;
		}
		Map<String, Object> updates = new LinkedHashMap<>();
		updates.put(KEY_BUDGET_START_MS, state.value(KEY_BUDGET_START_MS, Long.class).orElse(System.currentTimeMillis()));
		updates.put(KEY_BUDGET_TOOL_CALLS, state.value(KEY_BUDGET_TOOL_CALLS, Integer.class).orElse(0));
		state.updateState(updates);
	}

	/**
	 * Checks whether current request is still within budget.
	 */
	public boolean isWithinBudget(OverAllState state) {
		if (state == null) {
			return true;
		}
		long startMs = state.value(KEY_BUDGET_START_MS, Long.class).orElse(System.currentTimeMillis());
		int toolCalls = state.value(KEY_BUDGET_TOOL_CALLS, Integer.class).orElse(0);
		long elapsedMs = Math.max(0L, System.currentTimeMillis() - startMs);
		return toolCalls < maxToolCalls() && elapsedMs < maxLatencyMs();
	}

	/**
	 * Records one tool call usage.
	 */
	public void recordToolCall(OverAllState state) {
		if (state == null) {
			return;
		}
		int next = state.value(KEY_BUDGET_TOOL_CALLS, Integer.class).orElse(0) + 1;
		state.updateState(Map.of(KEY_BUDGET_TOOL_CALLS, next));
	}

	public int currentToolCalls(OverAllState state) {
		if (state == null) {
			return 0;
		}
		return state.value(KEY_BUDGET_TOOL_CALLS, Integer.class).orElse(0);
	}

	public long elapsedMs(OverAllState state) {
		if (state == null) {
			return 0L;
		}
		long startMs = state.value(KEY_BUDGET_START_MS, Long.class).orElse(System.currentTimeMillis());
		return Math.max(0L, System.currentTimeMillis() - startMs);
	}

	public int maxToolCalls() {
		return Math.max(1, compatibilityAdapter.budgetMaxToolCalls());
	}

	public long maxLatencyMs() {
		return Math.max(1L, compatibilityAdapter.budgetMaxLatencyMs());
	}

}
