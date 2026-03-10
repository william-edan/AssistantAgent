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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LegacyToolPublicationProviderTest {

	@Test
	void shouldExposeLegacyBridgeToolsAsDirectPublications() {
		CapabilityBridgeToolFactory bridgeToolFactory = mock(CapabilityBridgeToolFactory.class);
		LegacyToolPublicationProvider provider = new LegacyToolPublicationProvider(bridgeToolFactory);
		CodeactTool tool = mockCodeactTool("leave_application_execute", "请假审批");
		when(bridgeToolFactory.createToolsForTenant("default")).thenReturn(List.of(tool));

		List<PublishedToolDescriptor> descriptors = provider.listPublishedTools(
				new ToolPublicationProvider.PublicationScope("default", 1L, "prod", "hr-assistant"));

		assertEquals(1, descriptors.size());
		assertEquals("legacy-bridge", descriptors.get(0).sourceType());
		assertEquals("legacy:leave_application_execute", descriptors.get(0).publicationKey());
		assertTrue(descriptors.get(0).isDirectToolPublication());
		assertSame(tool, descriptors.get(0).directTool());
	}

	private static CodeactTool mockCodeactTool(String name, String description) {
		CodeactTool tool = mock(CodeactTool.class);
		ToolDefinition definition = DefaultToolDefinition.builder()
				.name(name)
				.description(description)
				.inputSchema("{\"type\":\"object\",\"properties\":{}}")
				.build();
		CodeactToolMetadata metadata = DefaultCodeactToolMetadata.builder()
				.addSupportedLanguage(Language.PYTHON)
				.targetClassName("legacy_tools")
				.targetClassDescription("Legacy tools")
				.codeInvocationTemplate(name + "() -> Dict[str, Any]")
				.fewShots(List.of(new CodeExample("demo", "legacy_tools." + name + "()", "mock behavior")))
				.displayName(description)
				.returnDirect(false)
				.build();

		when(tool.getToolDefinition()).thenReturn(definition);
		when(tool.getCodeactMetadata()).thenReturn(metadata);
		when(tool.getCodeactDefinition()).thenReturn((CodeactToolDefinition) null);
		return tool;
	}

}
