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
package com.alibaba.assistant.agent.core.executor;

import com.alibaba.assistant.agent.common.enums.Language;
import com.alibaba.assistant.agent.core.context.CodeContext;
import com.alibaba.assistant.agent.core.tool.CodeactToolRegistry;
import com.alibaba.assistant.agent.core.tool.ToolContextScopedCodeactToolRegistry;
import com.alibaba.assistant.agent.core.tool.ToolRegistryBridge;
import com.alibaba.assistant.agent.core.tool.ToolRegistryBridgeFactory;
import com.alibaba.cloud.ai.graph.OverAllState;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Value;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GraalCodeExecutorTest {

	@Test
	void shouldResolveScopedRegistryBeforeInjectingTools() throws Exception {
		RuntimeEnvironmentManager environmentManager = mock(RuntimeEnvironmentManager.class);
		OverAllState state = mock(OverAllState.class);
		ToolContextScopedCodeactToolRegistry registry = mock(ToolContextScopedCodeactToolRegistry.class);
		CodeactToolRegistry scopedRegistry = mock(CodeactToolRegistry.class);
		ToolRegistryBridgeFactory bridgeFactory = mock(ToolRegistryBridgeFactory.class);
		ToolRegistryBridge bridge = mock(ToolRegistryBridge.class);
		Context graalContext = mock(Context.class);
		Value bindings = mock(Value.class);
		ToolContext toolContext = new ToolContext(Map.of("appName", "hr-assistant"));

		when(registry.scope(toolContext)).thenReturn(scopedRegistry);
		when(bridgeFactory.create(scopedRegistry, toolContext)).thenReturn(bridge);
		when(scopedRegistry.getToolsForLanguage(Language.PYTHON)).thenReturn(List.of());
		when(graalContext.getBindings("python")).thenReturn(bindings);

		GraalCodeExecutor executor = new GraalCodeExecutor(
				environmentManager,
				new CodeContext(Language.PYTHON),
				List.of(),
				state,
				registry,
				bridgeFactory,
				false,
				false,
				1000L);

		ToolRegistryBridge actual = invokeInjectCodeactTools(executor, graalContext, registry, Language.PYTHON, toolContext);

		assertSame(bridge, actual);
		verify(registry).scope(toolContext);
		verify(bridgeFactory).create(scopedRegistry, toolContext);
		verify(scopedRegistry).getToolsForLanguage(Language.PYTHON);
		verify(registry, never()).getToolsForLanguage(Language.PYTHON);
		verify(bindings).putMember("__tool_registry__", bridge);
	}

	private ToolRegistryBridge invokeInjectCodeactTools(
			GraalCodeExecutor executor,
			Context graalContext,
			CodeactToolRegistry registry,
			Language language,
			ToolContext toolContext) throws Exception {
		Method method = GraalCodeExecutor.class.getDeclaredMethod(
				"injectCodeactTools",
				Context.class,
				CodeactToolRegistry.class,
				Language.class,
				ToolContext.class);
		method.setAccessible(true);
		return (ToolRegistryBridge) method.invoke(executor, graalContext, registry, language, toolContext);
	}

}
