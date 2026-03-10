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
import com.alibaba.assistant.agent.execution.flow.FlowDefinition;
import com.alibaba.assistant.agent.runtime.compiler.RuntimeArtifact;
import com.alibaba.assistant.agent.runtime.tool.codeact.ArtifactToolFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ToolPublicationMaterializerTest {

	@Test
	void shouldMaterializeArtifactAndDirectToolPublicationsInDeclaredOrder() {
		ArtifactToolFactory artifactToolFactory = new ArtifactToolFactory(new ObjectMapper());
		ToolPublicationMaterializer materializer = new ToolPublicationMaterializer(artifactToolFactory);
		CodeactTool directTool = mockCodeactTool("legacy_leave_execute");
		RuntimeArtifact artifact = new RuntimeArtifact(
				1L,
				"oa.leave.apply",
				RuntimeArtifact.ArtifactType.WORKFLOW,
				"请假申请",
				1,
				null,
				null,
				null,
				null,
				null,
				new FlowDefinition(),
				Map.of(),
				Map.of());

		List<CodeactTool> tools = materializer.materialize(List.of(
				PublishedToolDescriptor.forDirectTool(
						"legacy-bridge",
						"legacy:leave",
						"Legacy Leave",
						directTool),
				new PublishedToolDescriptor(
						"artifact-catalog",
						"workflow:oa.leave.apply",
						"请假申请",
						"oa_tools",
						"OA workflow tools",
						false,
						null,
						artifact)));

		assertEquals(2, tools.size());
		assertSame(directTool, tools.get(0));
		assertEquals("oa_leave_apply_execute", tools.get(1).getToolDefinition().name());
	}

	@Test
	void shouldIgnoreDescriptorsWithoutExecutablePayload() {
		ArtifactToolFactory artifactToolFactory = new ArtifactToolFactory(new ObjectMapper());
		ToolPublicationMaterializer materializer = new ToolPublicationMaterializer(artifactToolFactory);

		List<CodeactTool> tools = materializer.materialize(List.of(
				new PublishedToolDescriptor(
						"artifact-catalog",
						"broken",
						"Broken",
						null,
						null,
						false,
						null,
						(RuntimeArtifact) null)));

		assertTrue(tools.isEmpty());
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
