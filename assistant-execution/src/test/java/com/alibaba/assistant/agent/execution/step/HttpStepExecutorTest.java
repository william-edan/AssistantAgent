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
package com.alibaba.assistant.agent.execution.step;

import com.alibaba.assistant.agent.controlplane.identity.TokenBroker;
import com.alibaba.assistant.agent.execution.flow.FlowContext;
import com.alibaba.assistant.agent.execution.model.StepResult;
import com.alibaba.assistant.agent.execution.model.StepConfig;
import com.alibaba.assistant.agent.execution.step.http.RequestBodySerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class HttpStepExecutorTest {

	@Test
	void shouldFailFastWhenIdentityContextMissingAndMockFallbackDisabled() {
		HttpStepExecutor executor = new HttpStepExecutor(
				new ObjectMapper(),
				new RequestBodySerializer(),
				mock(HttpStepExecutor.SystemAccessProfilePort.class),
				mock(TokenBroker.class),
				false);
		StepConfig config = new StepConfig();
		config.setMethod("POST");
		config.setEndpoint("/api/check/submit_check");
		config.setContentType("application/json");

		StepResult result = executor.execute(config, new FlowContext(Map.of()));

		assertFalse(result.isSuccess());
		assertTrue(result.getErrorMessage().contains("Missing identity context"));
	}

	@Test
	void shouldAllowMockResponseWhenMockFallbackExplicitlyEnabled() {
		HttpStepExecutor executor = new HttpStepExecutor(
				new ObjectMapper(),
				new RequestBodySerializer(),
				mock(HttpStepExecutor.SystemAccessProfilePort.class),
				mock(TokenBroker.class),
				true);
		StepConfig config = new StepConfig();
		config.setMethod("POST");
		config.setEndpoint("/api/check/submit_check");
		config.setContentType("application/json");
		config.setSuccessCondition("$.code == 0");

		StepResult result = executor.execute(config, new FlowContext(Map.of()));

		assertTrue(result.isSuccess());
	}

}
