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
package com.alibaba.assistant.agent.core.tool;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ToolRegistryScopeAdaptersTest {

	@Test
	void shouldUseScopedRegistryWhenAvailable() {
		ToolContextScopedCodeactToolRegistry scopedRegistry = mock(ToolContextScopedCodeactToolRegistry.class);
		CodeactToolRegistry resolvedRegistry = mock(CodeactToolRegistry.class);
		ToolContext toolContext = new ToolContext(Map.of("appName", "hr-assistant"));
		when(scopedRegistry.scope(toolContext)).thenReturn(resolvedRegistry);

		CodeactToolRegistry actual = ToolRegistryScopeAdapters.resolve(scopedRegistry, toolContext);

		assertSame(resolvedRegistry, actual);
	}

	@Test
	void shouldFallbackToOriginalRegistryWhenRegistryIsNotScoped() {
		CodeactToolRegistry registry = mock(CodeactToolRegistry.class);
		ToolContext toolContext = new ToolContext(Map.of());

		CodeactToolRegistry actual = ToolRegistryScopeAdapters.resolve(registry, toolContext);

		assertSame(registry, actual);
	}

}
