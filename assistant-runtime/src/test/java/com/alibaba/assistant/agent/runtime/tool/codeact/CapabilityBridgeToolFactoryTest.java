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

import com.alibaba.assistant.agent.common.tools.CodeactTool;
import com.alibaba.assistant.agent.controlplane.toolregistry.ToolMeta;
import com.alibaba.assistant.agent.controlplane.toolregistry.ToolMetaService;
import com.alibaba.assistant.agent.execution.flow.DAGFlowExecutor;
import com.alibaba.assistant.agent.execution.flow.FlowDefinitionConverter;
import com.alibaba.assistant.agent.execution.step.HttpStepExecutor;
import com.alibaba.assistant.agent.extension.dynamic.spi.DynamicToolFactoryContext;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class CapabilityBridgeToolFactoryTest {

	@Test
	void shouldCreateCapabilityBridgeToolsFromEnabledToolMetaRecords() {
		ToolMetaService toolMetaService = mock(ToolMetaService.class);
		FlowDefinitionConverter converter = mock(FlowDefinitionConverter.class);
		DAGFlowExecutor dagExecutor = mock(DAGFlowExecutor.class);
		HttpStepExecutor httpStepExecutor = mock(HttpStepExecutor.class);
		ObjectMapper objectMapper = new ObjectMapper();

		ToolMeta first = toolMeta(1L, "gougu_oa.leave_application", "gougu_oa");
		ToolMeta second = toolMeta(2L, "gougu_oa.leave_application", "gougu_oa");
		when(toolMetaService.list(any(Wrapper.class))).thenReturn(List.of(first, second));

		CapabilityBridgeToolFactory factory = new CapabilityBridgeToolFactory(
				toolMetaService, converter, dagExecutor, httpStepExecutor, objectMapper);

		List<CodeactTool> tools = factory.createTools(DynamicToolFactoryContext.builder().build());

		assertEquals(2, tools.size());
		assertTrue(tools.get(0) instanceof CapabilityBridgeTool);
		assertTrue(tools.get(1) instanceof CapabilityBridgeTool);
		assertEquals("gougu_oa_leave_application_execute", tools.get(0).getToolDefinition().name());
		assertEquals("gougu_oa_leave_application_execute_2", tools.get(1).getToolDefinition().name());
		verify(toolMetaService, times(1)).list(any(Wrapper.class));
	}

	private static ToolMeta toolMeta(Long id, String toolCode, String systemCode) {
		ToolMeta toolMeta = new ToolMeta();
		toolMeta.setId(id);
		toolMeta.setTenantId("default");
		toolMeta.setToolCode(toolCode);
		toolMeta.setToolName("leave");
		toolMeta.setSystemCode(systemCode);
		toolMeta.setStatus("enabled");
		toolMeta.setParameterSchema("{\"type\":\"object\",\"properties\":{\"reason\":{\"type\":\"string\"}}}");
		return toolMeta;
	}
}
