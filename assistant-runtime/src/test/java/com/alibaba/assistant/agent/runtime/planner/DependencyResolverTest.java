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
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class DependencyResolverTest {

	@Test
	void shouldResolveDependenciesInTopologicalOrder() {
		DependencyResolver resolver = new DependencyResolver(new ObjectMapper(), 4);
		Map<String, ToolMeta> tools = new HashMap<>();
		tools.put("leave_apply", tool("leave_apply", """
				{
				  "dependsOn": ["current_user"],
				  "fieldMappings": [
				    {"fromTool":"current_user","fromField":"employeeId","toField":"employeeId"}
				  ]
				}
				"""));
		tools.put("current_user", tool("current_user", "{}"));

		List<DependencyResolver.ResolvedStep> steps =
				resolver.resolve("leave_apply", code -> Optional.ofNullable(tools.get(code)));

		assertEquals(2, steps.size());
		assertEquals("current_user", steps.get(0).toolCode());
		assertEquals("leave_apply", steps.get(1).toolCode());
		assertEquals(1, steps.get(0).mappings().size());
		assertEquals("employeeId", steps.get(0).mappings().get(0).fromField());
	}

	@Test
	void shouldThrowWhenDependencyMissing() {
		DependencyResolver resolver = new DependencyResolver(new ObjectMapper(), 3);
		Map<String, ToolMeta> tools = new HashMap<>();
		tools.put("leave_apply", tool("leave_apply", "{\"dependsOn\":[\"current_user\"]}"));

		assertThrows(IllegalStateException.class,
				() -> resolver.resolve("leave_apply", code -> Optional.ofNullable(tools.get(code))));
	}

	@Test
	void shouldThrowWhenDependencyCycleDetected() {
		DependencyResolver resolver = new DependencyResolver(new ObjectMapper(), 5);
		Map<String, ToolMeta> tools = new HashMap<>();
		tools.put("tool_a", tool("tool_a", "{\"dependsOn\":[\"tool_b\"]}"));
		tools.put("tool_b", tool("tool_b", "{\"dependsOn\":[\"tool_a\"]}"));

		assertThrows(IllegalStateException.class,
				() -> resolver.resolve("tool_a", code -> Optional.ofNullable(tools.get(code))));
	}

	@Test
	void shouldThrowWhenDepthExceeded() {
		DependencyResolver resolver = new DependencyResolver(new ObjectMapper(), 2);
		Map<String, ToolMeta> tools = new HashMap<>();
		tools.put("a", tool("a", "{\"dependsOn\":[\"b\"]}"));
		tools.put("b", tool("b", "{\"dependsOn\":[\"c\"]}"));
		tools.put("c", tool("c", "{\"dependsOn\":[\"d\"]}"));
		tools.put("d", tool("d", "{}"));

		assertThrows(IllegalStateException.class,
				() -> resolver.resolve("a", code -> Optional.ofNullable(tools.get(code))));
	}

	private ToolMeta tool(String code, String interactionPolicy) {
		ToolMeta toolMeta = new ToolMeta();
		toolMeta.setToolCode(code);
		toolMeta.setDescription("desc-" + code);
		toolMeta.setInteractionPolicy(interactionPolicy);
		return toolMeta;
	}

}
