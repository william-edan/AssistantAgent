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
package com.alibaba.assistant.agent.runtime.prompt;

import com.alibaba.assistant.agent.controlplane.toolregistry.ToolMeta;
import com.alibaba.assistant.agent.controlplane.toolregistry.ToolMetaService;
import com.alibaba.assistant.agent.runtime.config.RuntimeConfigCompatibilityAdapter;
import com.alibaba.assistant.agent.prompt.PromptContribution;
import com.alibaba.assistant.agent.prompt.PromptContributorContext;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class ToolCatalogContributorTest {

	@Test
	void shouldSkipWhenDynamicPromptDisabled() {
		ToolMetaService toolMetaService = mock(ToolMetaService.class);
		RuntimeConfigCompatibilityAdapter adapter = mock(RuntimeConfigCompatibilityAdapter.class);
		when(adapter.promptDynamicEnabled()).thenReturn(false);

		ToolCatalogContributor contributor = new ToolCatalogContributor(toolMetaService, adapter);
		boolean shouldContribute = contributor.shouldContribute(context(Map.of()));

		assertFalse(shouldContribute);
	}

	@Test
	void shouldRenderCatalogInMessagesToAppendAndApplyLimit() {
		ToolMetaService toolMetaService = mock(ToolMetaService.class);
		RuntimeConfigCompatibilityAdapter adapter = mock(RuntimeConfigCompatibilityAdapter.class);
		when(adapter.promptDynamicEnabled()).thenReturn(true);
		when(adapter.promptMaxToolsInPrompt()).thenReturn(1);

		ToolMeta first = tool("gougu_oa.leave_apply", "请假申请", "发起请假");
		ToolMeta second = tool("gougu_oa.current_user", "当前用户", "查询当前用户");
		when(toolMetaService.listEnabledByTenantAndSystem(eq("default"), eq(null)))
				.thenReturn(List.of(first, second));

		ToolCatalogContributor contributor = new ToolCatalogContributor(toolMetaService, adapter);
		PromptContribution contribution = contributor.contribute(context(Map.of()));

		assertNotNull(contribution);
		assertEquals(1, contribution.messagesToAppend().size());
		Message message = contribution.messagesToAppend().get(0);
		assertInstanceOf(UserMessage.class, message);
		assertTrue(message.getText().contains("gougu_oa.leave_apply"));
		assertFalse(message.getText().contains("gougu_oa.current_user"));
		assertTrue(message.getText().contains("1/2"));
		assertTrue(contribution.systemTextToAppend() == null || contribution.systemTextToAppend().isBlank());
	}

	@Test
	void shouldResolveTenantAndSystemFromContextAttributes() {
		ToolMetaService toolMetaService = mock(ToolMetaService.class);
		RuntimeConfigCompatibilityAdapter adapter = mock(RuntimeConfigCompatibilityAdapter.class);
		when(adapter.promptDynamicEnabled()).thenReturn(true);
		when(adapter.promptMaxToolsInPrompt()).thenReturn(5);
		when(toolMetaService.listEnabledByTenantAndSystem(eq("tenant-a"), eq("gougu_oa")))
				.thenReturn(List.of(tool("gougu_oa.leave_apply", "请假申请", null)));

		ToolCatalogContributor contributor = new ToolCatalogContributor(toolMetaService, adapter);
		PromptContribution contribution = contributor.contribute(context(Map.of(
				"tenant_id", "tenant-a",
				"system_code", "gougu_oa")));

		assertEquals(1, contribution.messagesToAppend().size());
		verify(toolMetaService, times(1)).listEnabledByTenantAndSystem("tenant-a", "gougu_oa");
	}

	private ToolMeta tool(String toolCode, String toolName, String desc) {
		ToolMeta toolMeta = new ToolMeta();
		toolMeta.setToolCode(toolCode);
		toolMeta.setToolName(toolName);
		toolMeta.setDescription(desc);
		return toolMeta;
	}

	private PromptContributorContext context(Map<String, Object> attrs) {
		return new PromptContributorContext() {
			@Override
			public List<Message> getMessages() {
				return Collections.emptyList();
			}

			@Override
			public Optional<SystemMessage> getSystemMessage() {
				return Optional.empty();
			}

			@Override
			public Map<String, Object> getAttributes() {
				return attrs;
			}

			@Override
			public Optional<String> getPhase() {
				return Optional.of("REACT");
			}
		};
	}

}
