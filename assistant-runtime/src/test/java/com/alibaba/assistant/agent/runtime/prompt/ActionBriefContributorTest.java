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
import com.alibaba.assistant.agent.prompt.PromptContribution;
import com.alibaba.assistant.agent.prompt.PromptContributorContext;
import com.alibaba.assistant.agent.runtime.agent.AssistantStateKeys;
import com.alibaba.assistant.agent.runtime.config.RuntimeConfigCompatibilityAdapter;
import com.alibaba.assistant.agent.slot.SlotSchemaParser;
import com.alibaba.assistant.agent.slot.model.SlotAskMode;
import com.alibaba.assistant.agent.slot.model.SlotDefinition;
import com.alibaba.assistant.agent.slot.model.SlotPriority;
import com.alibaba.assistant.agent.slot.model.ToolMetaSnapshot;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ActionBriefContributorTest {

	@Test
	void shouldSkipWhenDynamicPromptDisabled() {
		RuntimeConfigCompatibilityAdapter adapter = mock(RuntimeConfigCompatibilityAdapter.class);
		SlotSchemaParser parser = mock(SlotSchemaParser.class);
		when(adapter.promptDynamicEnabled()).thenReturn(false);

		ActionBriefContributor contributor = new ActionBriefContributor(adapter, parser, new ObjectMapper());
		assertFalse(contributor.shouldContribute(context(Map.of())));
	}

	@Test
	void shouldRenderActionBriefWithCollectedAndMissingSlots() {
		RuntimeConfigCompatibilityAdapter adapter = mock(RuntimeConfigCompatibilityAdapter.class);
		SlotSchemaParser parser = mock(SlotSchemaParser.class);
		when(adapter.promptDynamicEnabled()).thenReturn(true);

		SlotDefinition startDate = slot("start_date", "开始日期", "请假开始日期", "YYYY-MM-DD", SlotPriority.CORE, true);
		SlotDefinition endDate = slot("end_date", "结束日期", "请假结束日期", "YYYY-MM-DD", SlotPriority.CORE, true);
		SlotDefinition reason = slot("reason", "请假事由", "请假原因", "如有事可填个人事务", SlotPriority.CONFIRM, false);
		reason.setDefaultValue("个人事务");
		when(parser.parse(any(ToolMetaSnapshot.class))).thenReturn(List.of(startDate, endDate, reason));

		ToolMeta matchedToolMeta = new ToolMeta();
		matchedToolMeta.setToolCode("leave_apply");
		matchedToolMeta.setToolName("请假申请");
		matchedToolMeta.setDescription("发起请假流程");
		matchedToolMeta.setApiEndpoint("/internal/leave/apply");
		matchedToolMeta.setParameterSchema("{\"slots\":[]}");

		ActionBriefContributor contributor = new ActionBriefContributor(adapter, parser, new ObjectMapper());
		PromptContribution contribution = contributor.contribute(context(Map.of(
				AssistantStateKeys.MATCHED_TOOL_META, matchedToolMeta,
				AssistantStateKeys.COLLECTED_SLOTS, Map.of("start_date", "2026-03-01"),
				AssistantStateKeys.CONVERSATION_PHASE, "COLLECTING")));

		String text = contribution.messagesToAppend().get(0).getText();
		assertTrue(text.contains("当前动作收集指引"));
		assertTrue(text.contains("leave_apply"));
		assertTrue(text.contains("请假申请"));
		assertTrue(text.contains("已识别参数"));
		assertTrue(text.contains("start_date=2026-03-01"));
		assertTrue(text.contains("仍需补充参数"));
		assertTrue(text.contains("end_date"));
		assertTrue(text.contains("YYYY-MM-DD"));
		assertTrue(text.contains("追问规则：仅围绕上述必填缺失字段"));
		assertTrue(text.contains("可自动填充/无需追问字段"));
		assertFalse(text.contains("/internal/leave/apply"));
	}

	@Test
	void shouldExcludeInferredSlotFromFollowUpWhenSourcesCollected() {
		RuntimeConfigCompatibilityAdapter adapter = mock(RuntimeConfigCompatibilityAdapter.class);
		SlotSchemaParser parser = mock(SlotSchemaParser.class);
		when(adapter.promptDynamicEnabled()).thenReturn(true);

		SlotDefinition reason = slot("reason", "请假原因", "请假事由", "如有事可填个人事务", SlotPriority.CORE, true);
		SlotDefinition leaveType = slot("leave_type", "请假类型", "请假类型", "可根据原因推断", SlotPriority.CORE, true);
		leaveType.setInferredFrom(List.of("reason"));
		SlotDefinition startDate = slot("start_date", "开始日期", "请假开始日期", "YYYY-MM-DD", SlotPriority.CORE, true);
		when(parser.parse(any(ToolMetaSnapshot.class))).thenReturn(List.of(reason, leaveType, startDate));

		ToolMeta matchedToolMeta = new ToolMeta();
		matchedToolMeta.setToolCode("leave_apply");
		matchedToolMeta.setToolName("请假申请");
		matchedToolMeta.setParameterSchema("{\"slots\":[]}");

		ActionBriefContributor contributor = new ActionBriefContributor(adapter, parser, new ObjectMapper());
		PromptContribution contribution = contributor.contribute(context(Map.of(
				AssistantStateKeys.MATCHED_TOOL_META, matchedToolMeta,
				AssistantStateKeys.COLLECTED_SLOTS, Map.of("reason", "个人事务"),
				AssistantStateKeys.CONVERSATION_PHASE, "COLLECTING")));

		String text = contribution.messagesToAppend().get(0).getText();
		assertTrue(text.contains("可自动填充/无需追问字段"));
		assertTrue(text.contains("leave_type（请假类型） [可由前序字段推断]"));
		assertTrue(text.contains("inferred_from 可推断字段"));

		int missingStart = text.indexOf("仍需补充参数（优先这些）：");
		int ruleStart = text.indexOf("追问规则：");
		String missingSection = missingStart >= 0 && ruleStart > missingStart
				? text.substring(missingStart, ruleStart)
				: text;
		assertTrue(missingSection.contains("start_date"));
		assertFalse(missingSection.contains("leave_type"));
	}

	private SlotDefinition slot(String name,
			String title,
			String description,
			String aiHint,
			SlotPriority priority,
			boolean required) {
		SlotDefinition slot = new SlotDefinition();
		slot.setName(name);
		slot.setType("string");
		slot.setTitle(title);
		slot.setDescription(description);
		slot.setAiHint(aiHint);
		slot.setPriority(priority);
		slot.setRequired(required);
		slot.setAskMode(SlotAskMode.BATCH);
		return slot;
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
