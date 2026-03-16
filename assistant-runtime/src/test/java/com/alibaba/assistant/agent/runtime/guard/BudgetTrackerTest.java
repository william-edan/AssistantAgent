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

import com.alibaba.assistant.agent.runtime.config.AssistantRuntimeProperties;
import com.alibaba.assistant.agent.runtime.config.RuntimeConfigView;
import com.alibaba.cloud.ai.graph.OverAllState;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class BudgetTrackerTest {

	@Test
	void shouldInitAndRecordToolCalls() {
		MockEnvironment environment = new MockEnvironment();
		AssistantRuntimeProperties properties = new AssistantRuntimeProperties();
		RuntimeConfigView adapter = new RuntimeConfigView(properties);
		BudgetTracker tracker = new BudgetTracker(adapter);

		OverAllState state = new OverAllState();
		tracker.initIfAbsent(state);

		assertTrue(tracker.isWithinBudget(state));
		assertEquals(0, tracker.currentToolCalls(state));

		tracker.recordToolCall(state);
		assertEquals(1, tracker.currentToolCalls(state));
		assertTrue(tracker.isWithinBudget(state));
	}

	@Test
	void shouldDetectBudgetExceededByToolCalls() {
		MockEnvironment environment = new MockEnvironment()
				.withProperty("assistant.runtime.budget.max-tool-calls", "2")
				.withProperty("assistant.runtime.budget.max-latency-ms", "12000");
		AssistantRuntimeProperties properties = new AssistantRuntimeProperties();
		properties.getBudget().setMaxToolCalls(2);
		RuntimeConfigView adapter = new RuntimeConfigView(properties);
		BudgetTracker tracker = new BudgetTracker(adapter);

		OverAllState state = new OverAllState();
		state.updateState(Map.of(
				BudgetTracker.KEY_BUDGET_START_MS, System.currentTimeMillis(),
				BudgetTracker.KEY_BUDGET_TOOL_CALLS, 2));

		assertFalse(tracker.isWithinBudget(state));
	}

	@Test
	void shouldDetectBudgetExceededByLatency() {
		MockEnvironment environment = new MockEnvironment();
		AssistantRuntimeProperties properties = new AssistantRuntimeProperties();
		properties.getBudget().setMaxLatencyMs(1000);
		RuntimeConfigView adapter = new RuntimeConfigView(properties);
		BudgetTracker tracker = new BudgetTracker(adapter);

		OverAllState state = new OverAllState();
		state.updateState(Map.of(
				BudgetTracker.KEY_BUDGET_START_MS, System.currentTimeMillis() - 1500,
				BudgetTracker.KEY_BUDGET_TOOL_CALLS, 0));

		assertFalse(tracker.isWithinBudget(state));
	}

}


