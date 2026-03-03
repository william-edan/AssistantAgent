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
package com.alibaba.assistant.agent.runtime.intent;

import com.alibaba.assistant.agent.common.constant.CodeactStateKeys;
import com.alibaba.assistant.agent.controlplane.toolregistry.ToolMeta;
import com.alibaba.assistant.agent.controlplane.toolregistry.ToolMetaService;
import com.alibaba.cloud.ai.graph.KeyStrategy;
import com.alibaba.assistant.agent.extension.experience.model.Experience;
import com.alibaba.assistant.agent.extension.experience.model.ExperienceArtifact;
import com.alibaba.assistant.agent.extension.experience.model.ExperienceType;
import com.alibaba.assistant.agent.runtime.agent.AssistantStateKeys;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.hook.JumpTo;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AssistantFastIntentHookTest {

	@Test
	void shouldUseReplaceStrategyForJumpToState() {
		AssistantIntentRouter router = mock(AssistantIntentRouter.class);
		AssistantFastIntentHook hook = new AssistantFastIntentHook(router, new ObjectMapper());

		Map<String, KeyStrategy> strategies = hook.getKeyStrategys();

		assertTrue(strategies.containsKey("jump_to"));
		assertEquals("ReplaceStrategy", strategies.get("jump_to").getClass().getSimpleName());
	}

	@Test
	void shouldReturnEmptyUpdatesWhenRouteIsMainFlow() {
		AssistantIntentRouter router = mock(AssistantIntentRouter.class);
		when(router.route(any(), any(), any())).thenReturn(AssistantIntentRouter.IntentResult.mainFlow());

		AssistantFastIntentHook hook = new AssistantFastIntentHook(router, new ObjectMapper());

		Map<String, Object> updates = hook.beforeAgent(new OverAllState(), RunnableConfig.builder().build()).join();

		assertTrue(updates.isEmpty());
	}

	@Test
	void shouldJumpToToolWhenFastIntentMatched() {
		AssistantIntentRouter router = mock(AssistantIntentRouter.class);
		Experience matched = createReactExperience("exp-fast-1", "slot_collect");
		when(router.route(any(), any(), any())).thenReturn(AssistantIntentRouter.IntentResult.fastIntent(matched));

		AssistantFastIntentHook hook = new AssistantFastIntentHook(router, new ObjectMapper());
		OverAllState state = new OverAllState();
		state.updateState(Map.of(
				"input", "发起请假",
				CodeactStateKeys.AVAILABLE_TOOL_NAMES, List.of("slot_collect", "slot_confirm")));

		Map<String, Object> updates = hook.beforeAgent(state, RunnableConfig.builder().build()).join();

		assertEquals(JumpTo.tool, updates.get("jump_to"));
		assertTrue(updates.containsKey("fast_intent"));
		List<?> messages = (List<?>) updates.get("messages");
		assertEquals(1, messages.size());
		AssistantMessage assistantMessage = assertInstanceOf(AssistantMessage.class, messages.get(0));
		assertEquals(1, assistantMessage.getToolCalls().size());
		assertEquals("slot_collect", assistantMessage.getToolCalls().get(0).name());
	}

	@Test
	void shouldSkipWhenToolNotAllowedByWhitelist() {
		AssistantIntentRouter router = mock(AssistantIntentRouter.class);
		Experience matched = createReactExperience("exp-fast-2", "slot_collect");
		when(router.route(any(), any(), any())).thenReturn(AssistantIntentRouter.IntentResult.fastIntent(matched));

		AssistantFastIntentHook hook = new AssistantFastIntentHook(router, new ObjectMapper());
		OverAllState state = new OverAllState();
		state.updateState(Map.of(
				"input", "发起请假",
				CodeactStateKeys.AVAILABLE_TOOL_NAMES, List.of("reply")));

		Map<String, Object> updates = hook.beforeAgent(state, RunnableConfig.builder().build()).join();

		assertTrue(updates.isEmpty());
	}

	@Test
	void shouldPreRouteToSlotCollectWhenOperationIntentIsClear() throws Exception {
		AssistantIntentRouter router = mock(AssistantIntentRouter.class);
		when(router.route(any(), any(), any())).thenReturn(AssistantIntentRouter.IntentResult.mainFlow());

		ToolMetaService toolMetaService = mock(ToolMetaService.class);
		when(toolMetaService.listEnabledByTenantAndSystem("default", "gougu_oa"))
				.thenReturn(List.of(
						createToolMeta("gougu_oa.leave_application", "请假申请", "发起请假申请审批"),
						createToolMeta("gougu_oa.leave_query", "请假查询", "查询请假记录")));

		AssistantFastIntentHook hook = new AssistantFastIntentHook(router, new ObjectMapper(), toolMetaService);
		OverAllState state = new OverAllState();
		state.updateState(Map.of(
				"input", "我明天有点事情需要请假一天",
				AssistantStateKeys.SYSTEM_CODE, "gougu_oa",
				CodeactStateKeys.AVAILABLE_TOOL_NAMES, List.of("slot_collect", "slot_confirm")));

		Map<String, Object> updates = hook.beforeAgent(state, RunnableConfig.builder().build()).join();

		assertEquals(JumpTo.tool, updates.get("jump_to"));
		assertEquals(LocalDate.now().toString(), updates.get("current_date"));
		assertTrue(updates.containsKey(AssistantStateKeys.MATCHED_TOOL_META));
		List<?> messages = (List<?>) updates.get("messages");
		AssistantMessage assistantMessage = assertInstanceOf(AssistantMessage.class, messages.get(0));
		AssistantMessage.ToolCall toolCall = assistantMessage.getToolCalls().get(0);
		assertEquals("slot_collect", toolCall.name());

		@SuppressWarnings("unchecked")
		Map<String, Object> args = new ObjectMapper().readValue(toolCall.arguments(), Map.class);
		assertEquals("gougu_oa.leave_application", args.get("toolCode"));
		verify(router, never()).route(any(), any(), any());
	}

	@Test
	void shouldNotPreRouteForQueryIntent() {
		AssistantIntentRouter router = mock(AssistantIntentRouter.class);
		when(router.route(any(), any(), any())).thenReturn(AssistantIntentRouter.IntentResult.mainFlow());

		ToolMetaService toolMetaService = mock(ToolMetaService.class);
		when(toolMetaService.listEnabledByTenantAndSystem("default", "gougu_oa"))
				.thenReturn(List.of(createToolMeta("gougu_oa.leave_application", "请假申请", "发起请假申请审批")));

		AssistantFastIntentHook hook = new AssistantFastIntentHook(router, new ObjectMapper(), toolMetaService);
		OverAllState state = new OverAllState();
		state.updateState(Map.of(
				"input", "帮我查询一下请假政策",
				AssistantStateKeys.SYSTEM_CODE, "gougu_oa",
				CodeactStateKeys.AVAILABLE_TOOL_NAMES, List.of("slot_collect", "slot_confirm")));

		Map<String, Object> updates = hook.beforeAgent(state, RunnableConfig.builder().build()).join();

		assertTrue(updates.isEmpty());
		verify(router, times(1)).route(any(), any(), any());
	}

	@Test
	void shouldAutoExecuteWhenUserConfirmsInConfirmingPhase() throws Exception {
		AssistantIntentRouter router = mock(AssistantIntentRouter.class);
		when(router.route(any(), any(), any())).thenReturn(AssistantIntentRouter.IntentResult.mainFlow());

		AssistantFastIntentHook hook = new AssistantFastIntentHook(router, new ObjectMapper());
		OverAllState state = new OverAllState();
		state.updateState(Map.of(
				"input", "确认提交",
				AssistantStateKeys.CONVERSATION_PHASE, "CONFIRMING",
				AssistantStateKeys.MATCHED_TOOL_META, Map.of("toolCode", "gougu_oa.leave_application"),
				AssistantStateKeys.COLLECTED_SLOTS, Map.of("reason", "个人事务", "start_date", "2026-03-01"),
				CodeactStateKeys.AVAILABLE_TOOL_NAMES, List.of("slot_collect", "slot_confirm", "gougu_oa_leave_application_execute")));

		Map<String, Object> updates = hook.beforeAgent(state, RunnableConfig.builder().build()).join();

		assertEquals(JumpTo.tool, updates.get("jump_to"));
		assertFalse(updates.containsKey("fast_intent"));
		assertEquals(Boolean.TRUE, updates.get(AssistantStateKeys.EXECUTION_CONFIRM_GRANTED));
		assertEquals("gougu_oa_leave_application_execute", updates.get(AssistantStateKeys.EXECUTION_CONFIRM_TOOL_NAME));
		assertEquals("确认提交", updates.get(AssistantStateKeys.EXECUTION_CONFIRM_USER_INPUT));
		List<?> messages = (List<?>) updates.get("messages");
		assertEquals(1, messages.size());

		AssistantMessage assistantMessage = assertInstanceOf(AssistantMessage.class, messages.get(0));
		assertEquals(1, assistantMessage.getToolCalls().size());
		AssistantMessage.ToolCall call = assistantMessage.getToolCalls().get(0);
		assertEquals("gougu_oa_leave_application_execute", call.name());

		@SuppressWarnings("unchecked")
		Map<String, Object> args = new ObjectMapper().readValue(call.arguments(), Map.class);
		assertEquals("个人事务", args.get("reason"));
		assertEquals("2026-03-01", args.get("start_date"));
		assertEquals(Boolean.TRUE, args.get("confirmed"));
	}

	@Test
	void shouldNotAutoExecuteWhenInputNotChangedSinceSlotCollection() {
		AssistantIntentRouter router = mock(AssistantIntentRouter.class);
		when(router.route(any(), any(), any())).thenReturn(AssistantIntentRouter.IntentResult.mainFlow());

		AssistantFastIntentHook hook = new AssistantFastIntentHook(router, new ObjectMapper());
		OverAllState state = new OverAllState();
		state.updateState(Map.of(
				"input", "确认提交",
				AssistantStateKeys.LAST_COLLECT_USER_INPUT, "确认提交",
				AssistantStateKeys.CONVERSATION_PHASE, "CONFIRMING",
				AssistantStateKeys.MATCHED_TOOL_META, Map.of("toolCode", "gougu_oa.leave_application"),
				CodeactStateKeys.AVAILABLE_TOOL_NAMES, List.of("slot_collect", "slot_confirm", "gougu_oa_leave_application_execute")));

		Map<String, Object> updates = hook.beforeAgent(state, RunnableConfig.builder().build()).join();

		assertTrue(updates.isEmpty());
	}

	@Test
	void shouldNotBuildConfirmationExecutionWhenAllowlistExplicitlyEmpty() {
		AssistantIntentRouter router = mock(AssistantIntentRouter.class);
		when(router.route(any(), any(), any())).thenReturn(AssistantIntentRouter.IntentResult.mainFlow());

		AssistantFastIntentHook hook = new AssistantFastIntentHook(router, new ObjectMapper());
		OverAllState state = new OverAllState();
		state.updateState(Map.of(
				"input", "确认提交",
				AssistantStateKeys.LAST_COLLECT_USER_INPUT, "请帮我请假",
				AssistantStateKeys.CONVERSATION_PHASE, "CONFIRMING",
				AssistantStateKeys.MATCHED_TOOL_META, Map.of("toolCode", "gougu_oa.leave_application"),
				CodeactStateKeys.AVAILABLE_TOOL_NAMES, List.of()));

		Map<String, Object> updates = hook.beforeAgent(state, RunnableConfig.builder().build()).join();

		assertTrue(updates.isEmpty());
	}

	@Test
	void shouldPreferAllowlistExecuteNameWhenToolCodeContainsSystemPrefix() {
		AssistantIntentRouter router = mock(AssistantIntentRouter.class);
		when(router.route(any(), any(), any())).thenReturn(AssistantIntentRouter.IntentResult.mainFlow());

		AssistantFastIntentHook hook = new AssistantFastIntentHook(router, new ObjectMapper());
		OverAllState state = new OverAllState();
		state.updateState(Map.of(
				"input", "确认",
				AssistantStateKeys.CONVERSATION_PHASE, "CONFIRMING",
				AssistantStateKeys.MATCHED_TOOL_META, Map.of("toolCode", "gougu_oa.leave_application"),
				CodeactStateKeys.AVAILABLE_TOOL_NAMES, List.of("slot_collect", "slot_confirm", "leave_application_execute")));

		Map<String, Object> updates = hook.beforeAgent(state, RunnableConfig.builder().build()).join();

		List<?> messages = (List<?>) updates.get("messages");
		AssistantMessage assistantMessage = assertInstanceOf(AssistantMessage.class, messages.get(0));
		AssistantMessage.ToolCall call = assistantMessage.getToolCalls().get(0);
		assertEquals("leave_application_execute", call.name());
	}

	private ToolMeta createToolMeta(String toolCode, String toolName, String description) {
		ToolMeta toolMeta = new ToolMeta();
		toolMeta.setToolCode(toolCode);
		toolMeta.setToolName(toolName);
		toolMeta.setDescription(description);
		toolMeta.setSystemCode("gougu_oa");
		return toolMeta;
	}

	private Experience createReactExperience(String id, String toolName) {
		Experience experience = new Experience();
		experience.setId(id);
		experience.setType(ExperienceType.REACT);

		ExperienceArtifact.ToolCallSpec toolCallSpec = new ExperienceArtifact.ToolCallSpec();
		toolCallSpec.setToolName(toolName);
		toolCallSpec.setArguments(Map.of("toolCode", "gougu_oa.leave_apply"));

		ExperienceArtifact.ToolPlan plan = new ExperienceArtifact.ToolPlan();
		plan.setToolCalls(List.of(toolCallSpec));

		ExperienceArtifact.ReactArtifact reactArtifact = new ExperienceArtifact.ReactArtifact();
		reactArtifact.setAssistantText("命中快速路径");
		reactArtifact.setPlan(plan);

		ExperienceArtifact artifact = new ExperienceArtifact();
		artifact.setReact(reactArtifact);

		experience.setArtifact(artifact);
		return experience;
	}

}
