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
package com.alibaba.assistant.agent.runtime.tool.codeact;

import com.alibaba.assistant.agent.controlplane.toolregistry.ToolMeta;
import com.alibaba.assistant.agent.execution.flow.DAGFlowExecutor;
import com.alibaba.assistant.agent.execution.flow.FlowContext;
import com.alibaba.assistant.agent.execution.flow.FlowDefinition;
import com.alibaba.assistant.agent.execution.flow.FlowDefinitionConverter;
import com.alibaba.assistant.agent.execution.flow.FlowExecutionResult;
import com.alibaba.assistant.agent.execution.model.StepConfig;
import com.alibaba.assistant.agent.execution.model.StepResult;
import com.alibaba.assistant.agent.execution.step.HttpStepExecutor;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class CapabilityBridgeToolTest {

	@Test
	void shouldExecuteFlowModeWhenExecutionPlanPresent() throws Exception {
		ToolMeta meta = new ToolMeta();
		meta.setId(1L);
		meta.setToolCode("gougu_oa.leave_application");
		meta.setToolName("leave_application");
		meta.setSystemCode("gougu_oa");
		meta.setExecutionPlan("{\"version\":\"2.0\"}");
		meta.setParameterSchema("{\"type\":\"object\",\"properties\":{\"reason\":{\"type\":\"string\"}}}");

		FlowDefinitionConverter converter = mock(FlowDefinitionConverter.class);
		DAGFlowExecutor dagExecutor = mock(DAGFlowExecutor.class);
		HttpStepExecutor httpStepExecutor = mock(HttpStepExecutor.class);
		ObjectMapper objectMapper = new ObjectMapper();

		FlowDefinition flow = new FlowDefinition();
		when(converter.parseFromExecutionPlan(eq(meta.getExecutionPlan()), eq(meta))).thenReturn(flow);
		when(converter.validate(flow)).thenReturn(true);

		FlowExecutionResult executionResult = new FlowExecutionResult();
		executionResult.setSuccess(true);
		executionResult.setFinalOutputs(Map.of("leave_id", 12345));
		executionResult.setDurationMs(88L);
		when(dagExecutor.execute(eq(flow), any(FlowContext.class))).thenReturn(executionResult);

		CapabilityBridgeTool tool = new CapabilityBridgeTool(
				objectMapper,
				meta,
				converter,
				dagExecutor,
				httpStepExecutor,
				"leave_application_execute",
				"gougu_oa_tools");

		String raw = tool.call("{\"reason\":\"personal\"}");
		@SuppressWarnings("unchecked")
		Map<String, Object> payload = objectMapper.readValue(raw, Map.class);

		assertEquals("FLOW", payload.get("mode"));
		assertEquals(Boolean.TRUE, payload.get("success"));
		assertEquals("gougu_oa.leave_application", payload.get("toolCode"));
		verify(dagExecutor, times(1)).execute(eq(flow), any(FlowContext.class));
		verify(httpStepExecutor, never()).execute(any(), any());
	}

	@Test
	void shouldExecuteSimpleModeWhenExecutionPlanAbsent() throws Exception {
		ToolMeta meta = new ToolMeta();
		meta.setId(2L);
		meta.setToolCode("gougu_oa.leave_simple");
		meta.setToolName("leave_simple");
		meta.setSystemCode("gougu_oa");
		meta.setApiEndpoint("/home/leaves/add");
		meta.setHttpMethod("POST");
		meta.setContentType("application/x-www-form-urlencoded");
		meta.setParameterSchema("{\"type\":\"object\",\"properties\":{\"reason\":{\"type\":\"string\"}}}");

		FlowDefinitionConverter converter = mock(FlowDefinitionConverter.class);
		DAGFlowExecutor dagExecutor = mock(DAGFlowExecutor.class);
		HttpStepExecutor httpStepExecutor = mock(HttpStepExecutor.class);
		ObjectMapper objectMapper = new ObjectMapper();

		when(httpStepExecutor.execute(any(StepConfig.class), any(FlowContext.class)))
				.thenReturn(StepResult.success(Map.of("code", 0, "msg", "ok")));

		CapabilityBridgeTool tool = new CapabilityBridgeTool(
				objectMapper,
				meta,
				converter,
				dagExecutor,
				httpStepExecutor,
				"leave_simple_execute",
				"gougu_oa_tools");

		String raw = tool.call("{\"reason\":\"personal\"}");
		@SuppressWarnings("unchecked")
		Map<String, Object> payload = objectMapper.readValue(raw, Map.class);

		assertEquals("SIMPLE", payload.get("mode"));
		assertEquals(Boolean.TRUE, payload.get("success"));
		ArgumentCaptor<StepConfig> configCaptor = ArgumentCaptor.forClass(StepConfig.class);
		verify(httpStepExecutor).execute(configCaptor.capture(), any(FlowContext.class));
		assertTrue(configCaptor.getValue().getInputMapping().containsKey("reason"));
		assertEquals("${reason}", configCaptor.getValue().getInputMapping().get("reason"));
		verify(dagExecutor, never()).execute(any(FlowDefinition.class), any(FlowContext.class));
	}

	@Test
	void shouldUseCamelCaseIdentityFromArgumentsInSimpleMode() throws Exception {
		ToolMeta meta = new ToolMeta();
		meta.setId(3L);
		meta.setToolCode("gougu_oa.leave_simple");
		meta.setToolName("leave_simple");
		meta.setApiEndpoint("/home/leaves/add");
		meta.setHttpMethod("POST");
		meta.setContentType("application/x-www-form-urlencoded");
		meta.setParameterSchema("{\"type\":\"object\",\"properties\":{\"reason\":{\"type\":\"string\"}}}");

		FlowDefinitionConverter converter = mock(FlowDefinitionConverter.class);
		DAGFlowExecutor dagExecutor = mock(DAGFlowExecutor.class);
		HttpStepExecutor httpStepExecutor = mock(HttpStepExecutor.class);
		ObjectMapper objectMapper = new ObjectMapper();

		when(httpStepExecutor.execute(any(StepConfig.class), any(FlowContext.class)))
				.thenReturn(StepResult.success(Map.of("code", 0, "msg", "ok")));

		CapabilityBridgeTool tool = new CapabilityBridgeTool(
				objectMapper,
				meta,
				converter,
				dagExecutor,
				httpStepExecutor,
				"leave_simple_execute",
				"gougu_oa_tools");

		String raw = tool.call("{\"reason\":\"personal\",\"assistantUid\":\"u1\",\"systemCode\":\"oa\"}");
		@SuppressWarnings("unchecked")
		Map<String, Object> payload = objectMapper.readValue(raw, Map.class);

		assertEquals("SIMPLE", payload.get("mode"));
		assertEquals(Boolean.TRUE, payload.get("success"));
		ArgumentCaptor<FlowContext> contextCaptor = ArgumentCaptor.forClass(FlowContext.class);
		verify(httpStepExecutor).execute(any(StepConfig.class), contextCaptor.capture());
		assertEquals("oa", contextCaptor.getValue().getSystemCode());
		assertEquals("u1", contextCaptor.getValue().getAssistantUid());
	}
}
