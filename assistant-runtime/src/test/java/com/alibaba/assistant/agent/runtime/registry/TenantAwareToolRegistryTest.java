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
package com.alibaba.assistant.agent.runtime.registry;

import com.alibaba.assistant.agent.common.enums.Language;
import com.alibaba.assistant.agent.common.tools.CodeExample;
import com.alibaba.assistant.agent.common.tools.CodeactTool;
import com.alibaba.assistant.agent.common.tools.CodeactToolMetadata;
import com.alibaba.assistant.agent.common.tools.DefaultCodeactToolMetadata;
import com.alibaba.assistant.agent.common.tools.definition.CodeactToolDefinition;
import com.alibaba.assistant.agent.runtime.tool.codeact.CapabilityBridgeToolFactory;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

class TenantAwareToolRegistryTest {

	@Test
	void shouldCacheSnapshotAndInvalidateOnToolPublishedEvent() {
		CapabilityBridgeToolFactory bridgeFactory = mock(CapabilityBridgeToolFactory.class);
		CodeactTool tool = mockCodeactTool("leave_application_execute");
		when(bridgeFactory.createToolsForTenant("default")).thenReturn(List.of(tool));

		TenantAwareToolRegistry registry = new TenantAwareToolRegistry(bridgeFactory);
		assertTrue(registry.getTool("leave_application_execute").isPresent());

		registry.createSessionRegistry("default");
		registry.createSessionRegistry("default");
		verify(bridgeFactory, times(1)).createToolsForTenant("default");

		registry.onToolPublished(new TenantAwareToolRegistry.ToolPublishedEvent("default"));
		registry.createSessionRegistry("default");
		verify(bridgeFactory, times(2)).createToolsForTenant("default");
	}

	private static CodeactTool mockCodeactTool(String name) {
		CodeactTool tool = mock(CodeactTool.class);
		ToolDefinition definition = DefaultToolDefinition.builder()
				.name(name)
				.description("mock tool")
				.inputSchema("{\"type\":\"object\",\"properties\":{}}")
				.build();
		CodeactToolMetadata metadata = DefaultCodeactToolMetadata.builder()
				.addSupportedLanguage(Language.PYTHON)
				.targetClassName("mock_tools")
				.targetClassDescription("mock tools")
				.codeInvocationTemplate(name + "() -> Dict[str, Any]")
				.fewShots(List.of(new CodeExample("demo", "mock_tools." + name + "()", "mock behavior")))
				.returnDirect(false)
				.build();

		when(tool.getToolDefinition()).thenReturn(definition);
		when(tool.getCodeactMetadata()).thenReturn(metadata);
		when(tool.getCodeactDefinition()).thenReturn((CodeactToolDefinition) null);
		return tool;
	}
}
