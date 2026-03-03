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
package com.alibaba.assistant.agent.runtime.planner;

import com.alibaba.assistant.agent.controlplane.toolregistry.ToolMeta;
import com.alibaba.assistant.agent.controlplane.toolregistry.ToolMetaService;
import com.alibaba.assistant.agent.execution.flow.DAGFlowExecutor;
import com.alibaba.assistant.agent.execution.flow.FlowContext;
import com.alibaba.assistant.agent.execution.flow.FlowDefinitionConverter;
import com.alibaba.assistant.agent.execution.model.StepConfig;
import com.alibaba.assistant.agent.execution.model.StepResult;
import com.alibaba.assistant.agent.execution.step.HttpStepExecutor;
import com.alibaba.assistant.agent.runtime.agent.AssistantStateKeys;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.agent.tools.ToolContextConstants;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.model.ToolContext;

import java.util.Collections;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ToolExecutorTest {

	@Test
	void shouldReturnErrorWhenToolMetaNotFound() {
		ToolMetaService toolMetaService = mock(ToolMetaService.class);
		FlowDefinitionConverter flowDefinitionConverter = mock(FlowDefinitionConverter.class);
		DAGFlowExecutor dagFlowExecutor = mock(DAGFlowExecutor.class);
		HttpStepExecutor httpStepExecutor = mock(HttpStepExecutor.class);
		ObjectMapper objectMapper = new ObjectMapper();

		when(toolMetaService.findLatestEnabledByToolCode("default", "current_user")).thenReturn(Optional.empty());

		ToolExecutor executor = new ToolExecutor(
				toolMetaService,
				flowDefinitionConverter,
				dagFlowExecutor,
				httpStepExecutor,
				objectMapper,
				Collections.emptyList());

		ToolExecutor.ExecutionResult result = executor.execute("default", "current_user", Map.of(), null);

		assertFalse(result.success());
		assertTrue(result.errorMessage().contains("not found"));
	}

	@Test
	void shouldExecuteSimpleModeAndExtractOutputs() {
		ToolMetaService toolMetaService = mock(ToolMetaService.class);
		FlowDefinitionConverter flowDefinitionConverter = mock(FlowDefinitionConverter.class);
		DAGFlowExecutor dagFlowExecutor = mock(DAGFlowExecutor.class);
		HttpStepExecutor httpStepExecutor = mock(HttpStepExecutor.class);
		ObjectMapper objectMapper = new ObjectMapper();

		ToolMeta currentUserTool = new ToolMeta();
		currentUserTool.setToolCode("current_user");
		currentUserTool.setSystemCode("oa");
		currentUserTool.setApiEndpoint("/api/current_user");
		currentUserTool.setHttpMethod("GET");
		currentUserTool.setContentType("application/json");
		when(toolMetaService.findLatestEnabledByToolCode("default", "current_user"))
				.thenReturn(Optional.of(currentUserTool));

		when(httpStepExecutor.execute(any(StepConfig.class), any(FlowContext.class)))
				.thenReturn(StepResult.success(Map.of("employeeId", "E001", "name", "Alice")));

		ToolExecutor executor = new ToolExecutor(
				toolMetaService,
				flowDefinitionConverter,
				dagFlowExecutor,
				httpStepExecutor,
				objectMapper,
				Collections.emptyList());

		OverAllState state = new OverAllState();
		state.updateState(Map.of(
				AssistantStateKeys.SYSTEM_CODE, "oa",
				AssistantStateKeys.ASSISTANT_UID, "u1",
				AssistantStateKeys.THREAD_ID, "thread-1"));
		ToolContext context = new ToolContext(Map.of(ToolContextConstants.AGENT_STATE_CONTEXT_KEY, state));

		ToolExecutor.ExecutionResult result = executor.execute("default", "current_user", Map.of(), context);

		assertTrue(result.success());
		assertEquals("E001", result.outputFields().get("employeeId"));
		assertEquals("Alice", result.outputFields().get("name"));
		verify(httpStepExecutor, times(1)).execute(any(StepConfig.class), any(FlowContext.class));
	}

	@Test
	void shouldUseCamelCaseIdentityFromArgumentsWhenStateMissing() {
		ToolMetaService toolMetaService = mock(ToolMetaService.class);
		FlowDefinitionConverter flowDefinitionConverter = mock(FlowDefinitionConverter.class);
		DAGFlowExecutor dagFlowExecutor = mock(DAGFlowExecutor.class);
		HttpStepExecutor httpStepExecutor = mock(HttpStepExecutor.class);
		ObjectMapper objectMapper = new ObjectMapper();

		ToolMeta currentUserTool = new ToolMeta();
		currentUserTool.setToolCode("current_user");
		currentUserTool.setApiEndpoint("/api/current_user");
		currentUserTool.setHttpMethod("GET");
		currentUserTool.setContentType("application/json");
		when(toolMetaService.findLatestEnabledByToolCode("default", "current_user"))
				.thenReturn(Optional.of(currentUserTool));

		when(httpStepExecutor.execute(any(StepConfig.class), any(FlowContext.class)))
				.thenReturn(StepResult.success(Map.of("employeeId", "E001")));

		ToolExecutor executor = new ToolExecutor(
				toolMetaService,
				flowDefinitionConverter,
				dagFlowExecutor,
				httpStepExecutor,
				objectMapper,
				Collections.emptyList());

		Map<String, Object> args = Map.of(
				"assistantUid", "u1",
				"systemCode", "oa");
		ToolExecutor.ExecutionResult result = executor.execute("default", "current_user", args, null);

		assertTrue(result.success());
		ArgumentCaptor<FlowContext> contextCaptor = ArgumentCaptor.forClass(FlowContext.class);
		verify(httpStepExecutor, times(1)).execute(any(StepConfig.class), contextCaptor.capture());
		assertEquals("oa", contextCaptor.getValue().getSystemCode());
		assertEquals("u1", contextCaptor.getValue().getAssistantUid());
	}

}
