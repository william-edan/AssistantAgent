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
import com.alibaba.assistant.agent.execution.flow.FlowDefinition;
import com.alibaba.assistant.agent.extension.experience.model.Experience;
import com.alibaba.assistant.agent.extension.experience.model.ExperienceArtifact;
import com.alibaba.assistant.agent.extension.experience.model.ExperienceType;
import com.alibaba.assistant.agent.runtime.agent.AssistantStateKeys;
import com.alibaba.assistant.agent.runtime.compiler.RuntimeArtifact;
import com.alibaba.assistant.agent.runtime.registry.ArtifactPublicationLookupService;
import com.alibaba.assistant.agent.runtime.registry.PublishedToolDescriptor;
import com.alibaba.cloud.ai.graph.KeyStrategy;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.hook.JumpTo;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ToolContext;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
    void shouldDisableStreamingWhenExplicitlyConfigured() {
        AssistantIntentRouter router = mock(AssistantIntentRouter.class);
        when(router.route(any(), any(), any())).thenReturn(AssistantIntentRouter.IntentResult.mainFlow());

        AssistantFastIntentHook hook = new AssistantFastIntentHook(router, new ObjectMapper(), true);
        RunnableConfig config = RunnableConfig.builder()
                .addMetadata("_stream_", true)
                .build();

        hook.beforeAgent(new OverAllState(), config).join();

        assertEquals(Boolean.FALSE, config.metadata("_stream_").orElse(null));
    }

    @Test
    void shouldResetStaleToolJumpWhenRouteFallsBackToMainFlow() {
        AssistantIntentRouter router = mock(AssistantIntentRouter.class);
        when(router.route(any(), any(), any())).thenReturn(AssistantIntentRouter.IntentResult.mainFlow());

        AssistantFastIntentHook hook = new AssistantFastIntentHook(router, new ObjectMapper());
        OverAllState state = new OverAllState();
        state.updateState(Map.of("input", "请帮我看下请假规则", "jump_to", "tool"));

        Map<String, Object> updates = hook.beforeAgent(state, RunnableConfig.builder().build()).join();

        assertTrue(updates.containsKey("jump_to"));
        assertNull(updates.get("jump_to"));
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
        AssistantMessage assistantMessage = firstAssistantMessage(updates);
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
    void shouldPreRouteToPublishedArtifactWhenOperationIntentIsClear() throws Exception {
        AssistantIntentRouter router = mock(AssistantIntentRouter.class);
        when(router.route(any(), any(), any())).thenReturn(AssistantIntentRouter.IntentResult.mainFlow());
        ArtifactPublicationLookupService lookupService = mock(ArtifactPublicationLookupService.class);
        when(lookupService.listPublishedArtifacts(any(ToolContext.class))).thenReturn(List.of(
                publishedArtifact("oa.leave.apply", "请假申请", "提交请假申请", "gougu_oa", null, "HIGH", null)));

        AssistantFastIntentHook hook = new AssistantFastIntentHook(router, new ObjectMapper(), false, lookupService);
        OverAllState state = new OverAllState();
        state.updateState(Map.of(
                "input", "我明天有点事情需要请假一天",
                AssistantStateKeys.SYSTEM_CODE, "gougu_oa",
                CodeactStateKeys.AVAILABLE_TOOL_NAMES, List.of("slot_collect", "slot_confirm")));

        Map<String, Object> updates = hook.beforeAgent(state, RunnableConfig.builder().build()).join();

        assertEquals(JumpTo.tool, updates.get("jump_to"));
        assertEquals(LocalDate.now().toString(), updates.get("current_date"));
        @SuppressWarnings("unchecked")
        Map<String, Object> matchedToolMeta = (Map<String, Object>) updates.get(AssistantStateKeys.MATCHED_TOOL_META);
        assertEquals("oa.leave.apply", matchedToolMeta.get("toolCode"));
        assertEquals("请假申请", matchedToolMeta.get("toolName"));
        assertEquals("HIGH", matchedToolMeta.get("riskLevel"));
        assertEquals(Boolean.TRUE, matchedToolMeta.get("requiresConfirm"));

        AssistantMessage assistantMessage = firstAssistantMessage(updates);
        assertEquals("slot_collect", assistantMessage.getToolCalls().get(0).name());
        @SuppressWarnings("unchecked")
        Map<String, Object> args = new ObjectMapper().readValue(assistantMessage.getToolCalls().get(0).arguments(), Map.class);
        assertEquals("oa.leave.apply", args.get("toolCode"));
        verify(router, never()).route(any(), any(), any());
    }

    @Test
    void shouldNotPreRouteWhenNoPublishedArtifactMatch() {
        AssistantIntentRouter router = mock(AssistantIntentRouter.class);
        when(router.route(any(), any(), any())).thenReturn(AssistantIntentRouter.IntentResult.mainFlow());
        ArtifactPublicationLookupService lookupService = mock(ArtifactPublicationLookupService.class);
        when(lookupService.listPublishedArtifacts(any(ToolContext.class))).thenReturn(List.of());

        AssistantFastIntentHook hook = new AssistantFastIntentHook(router, new ObjectMapper(), false, lookupService);
        OverAllState state = new OverAllState();
        state.updateState(Map.of(
                "input", "我明天有点事情需要请假一天",
                AssistantStateKeys.SYSTEM_CODE, "gougu_oa",
                CodeactStateKeys.AVAILABLE_TOOL_NAMES, List.of("slot_collect", "slot_confirm")));

        Map<String, Object> updates = hook.beforeAgent(state, RunnableConfig.builder().build()).join();

        assertTrue(updates.isEmpty());
        verify(router, times(1)).route(any(), any(), any());
    }

    @Test
    void shouldNotPreRouteWhenCollectingPhaseHasNoNewUserInput() {
        AssistantIntentRouter router = mock(AssistantIntentRouter.class);
        when(router.route(any(), any(), any())).thenReturn(AssistantIntentRouter.IntentResult.mainFlow());
        ArtifactPublicationLookupService lookupService = mock(ArtifactPublicationLookupService.class);

        AssistantFastIntentHook hook = new AssistantFastIntentHook(router, new ObjectMapper(), false, lookupService);
        OverAllState state = new OverAllState();
        state.updateState(Map.of(
                "input", "发起工作汇报",
                AssistantStateKeys.LAST_COLLECT_USER_INPUT, "发起工作汇报",
                AssistantStateKeys.CONVERSATION_PHASE, "COLLECTING",
                AssistantStateKeys.SYSTEM_CODE, "gougu_oa",
                CodeactStateKeys.AVAILABLE_TOOL_NAMES, List.of("slot_collect", "slot_confirm")));

        Map<String, Object> updates = hook.beforeAgent(state, RunnableConfig.builder().build()).join();

        assertTrue(updates.isEmpty());
        verify(router, times(1)).route(any(), any(), any());
    }

    @Test
    void shouldContinueSlotCollectionWhenCollectingPhaseReceivesNewSlotValueInput() {
        AssistantIntentRouter router = mock(AssistantIntentRouter.class);
        when(router.route(any(), any(), any())).thenReturn(AssistantIntentRouter.IntentResult.mainFlow());

        AssistantFastIntentHook hook = new AssistantFastIntentHook(router, new ObjectMapper());
        OverAllState state = new OverAllState();
        state.updateState(Map.of(
                "input", "年假",
                AssistantStateKeys.LAST_COLLECT_USER_INPUT, "我要请假",
                AssistantStateKeys.CONVERSATION_PHASE, "COLLECTING",
                AssistantStateKeys.MATCHED_TOOL_META, Map.of("toolCode", "gougu_oa.leave_application"),
                CodeactStateKeys.AVAILABLE_TOOL_NAMES, List.of("slot_collect", "slot_confirm")));

        Map<String, Object> updates = hook.beforeAgent(state, RunnableConfig.builder().build()).join();

        assertEquals(JumpTo.tool, updates.get("jump_to"));
        AssistantMessage assistantMessage = firstAssistantMessage(updates);
        assertEquals("slot_collect", assistantMessage.getToolCalls().get(0).name());
        verify(router, never()).route(any(), any(), any());
    }

    @Test
    void shouldContinueSlotCollectionWhenTrailingUserMessageIsNewerThanStaleInput() {
        AssistantIntentRouter router = mock(AssistantIntentRouter.class);
        when(router.route(any(), any(), any())).thenReturn(AssistantIntentRouter.IntentResult.mainFlow());

        AssistantFastIntentHook hook = new AssistantFastIntentHook(router, new ObjectMapper());
        OverAllState state = new OverAllState();
        state.updateState(Map.of(
                "input", "我要请假",
                "messages", List.of(
                        AssistantMessage.builder().content("请补充结束日期").build(),
                        new UserMessage("请假类型：事假，开始日期：2026-03-17，结束日期：2026-03-18，请假原因：123123")),
                AssistantStateKeys.LAST_COLLECT_USER_INPUT, "我要请假",
                AssistantStateKeys.CONVERSATION_PHASE, "COLLECTING",
                AssistantStateKeys.MATCHED_TOOL_META, Map.of("toolCode", "gougu_oa.leave_application"),
                CodeactStateKeys.AVAILABLE_TOOL_NAMES, List.of("slot_collect", "slot_confirm")));

        Map<String, Object> updates = hook.beforeAgent(state, RunnableConfig.builder().build()).join();

        assertEquals(JumpTo.tool, updates.get("jump_to"));
        AssistantMessage assistantMessage = firstAssistantMessage(updates);
        assertEquals("slot_collect", assistantMessage.getToolCalls().get(0).name());
        verify(router, never()).route(any(), any(), any());
    }

    @Test
    void shouldNotPreRouteForQueryIntent() {
        AssistantIntentRouter router = mock(AssistantIntentRouter.class);
        when(router.route(any(), any(), any())).thenReturn(AssistantIntentRouter.IntentResult.mainFlow());
        ArtifactPublicationLookupService lookupService = mock(ArtifactPublicationLookupService.class);
        when(lookupService.listPublishedArtifacts(any(ToolContext.class))).thenReturn(List.of(
                publishedArtifact("oa.leave.apply", "请假申请", "提交请假申请", "gougu_oa", null, null, null)));

        AssistantFastIntentHook hook = new AssistantFastIntentHook(router, new ObjectMapper(), false, lookupService);
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
    void shouldPreRouteWorkReportToTypeSelectionWithoutVisibleNarration() throws Exception {
        AssistantIntentRouter router = mock(AssistantIntentRouter.class);
        when(router.route(any(), any(), any())).thenReturn(AssistantIntentRouter.IntentResult.mainFlow());
        ArtifactPublicationLookupService lookupService = mock(ArtifactPublicationLookupService.class);
        when(lookupService.listPublishedArtifacts(any(ToolContext.class))).thenReturn(List.of(
                publishedArtifact("gougu_oa.work_report", "工作汇报", "创建工作日报、周报或月报", "gougu_oa", null, null, null)));

        AssistantFastIntentHook hook = new AssistantFastIntentHook(router, new ObjectMapper(), false, lookupService);
        OverAllState state = new OverAllState();
        state.updateState(Map.of(
                "input", "我要写汇报",
                AssistantStateKeys.SYSTEM_CODE, "gougu_oa",
                CodeactStateKeys.AVAILABLE_TOOL_NAMES, List.of("slot_collect", "slot_confirm")));

        Map<String, Object> updates = hook.beforeAgent(state, RunnableConfig.builder().build()).join();

        assertEquals(JumpTo.tool, updates.get("jump_to"));
        assertEquals(LocalDate.now().toString(), updates.get("current_date"));
        @SuppressWarnings("unchecked")
        Map<String, Object> matchedToolMeta = (Map<String, Object>) updates.get(AssistantStateKeys.MATCHED_TOOL_META);
        assertEquals("gougu_oa.work_report", matchedToolMeta.get("toolCode"));

        AssistantMessage assistantMessage = firstAssistantMessage(updates);
        assertEquals("slot_collect", assistantMessage.getToolCalls().get(0).name());
        assertFalse(org.springframework.util.StringUtils.hasText(assistantMessage.getText()));

        @SuppressWarnings("unchecked")
        Map<String, Object> args = new ObjectMapper().readValue(assistantMessage.getToolCalls().get(0).arguments(), Map.class);
        assertEquals("gougu_oa.work_report", args.get("toolCode"));
        verify(router, never()).route(any(), any(), any());
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
                CodeactStateKeys.AVAILABLE_TOOL_NAMES,
                List.of("slot_collect", "slot_confirm")));

        Map<String, Object> updates = hook.beforeAgent(state, RunnableConfig.builder().build()).join();

        assertEquals(JumpTo.tool, updates.get("jump_to"));
        assertFalse(updates.containsKey("fast_intent"));
        assertEquals(Boolean.TRUE, updates.get(AssistantStateKeys.EXECUTION_CONFIRM_GRANTED));
        assertEquals("artifact_execute", updates.get(AssistantStateKeys.EXECUTION_CONFIRM_TOOL_NAME));
        assertEquals("确认提交", updates.get(AssistantStateKeys.EXECUTION_CONFIRM_USER_INPUT));
        assertEquals(List.of("slot_collect", "slot_confirm", "artifact_execute"),
                updates.get(CodeactStateKeys.AVAILABLE_TOOL_NAMES));
        AssistantMessage assistantMessage = firstAssistantMessage(updates);
        assertEquals("artifact_execute", assistantMessage.getToolCalls().get(0).name());
        assertFalse(org.springframework.util.StringUtils.hasText(assistantMessage.getText()));

        @SuppressWarnings("unchecked")
        Map<String, Object> args = new ObjectMapper().readValue(assistantMessage.getToolCalls().get(0).arguments(), Map.class);
        assertEquals("gougu_oa.leave_application", args.get("toolCode"));
        assertEquals(Boolean.TRUE, args.get("confirmed"));
        assertEquals(Map.of("reason", "个人事务", "start_date", "2026-03-01"), args.get("params"));
    }

    @Test
    void shouldPreferArtifactExecuteWhenPublishedArtifactExists() throws Exception {
        AssistantIntentRouter router = mock(AssistantIntentRouter.class);
        when(router.route(any(), any(), any())).thenReturn(AssistantIntentRouter.IntentResult.mainFlow());
        ArtifactPublicationLookupService lookupService = mock(ArtifactPublicationLookupService.class);
        when(lookupService.findPublishedArtifact(eq("oa.leave.apply"), any()))
                .thenReturn(Optional.of(mock(PublishedToolDescriptor.class)));

        AssistantFastIntentHook hook = new AssistantFastIntentHook(router, new ObjectMapper(), false, lookupService);
        OverAllState state = new OverAllState();
        state.updateState(Map.of(
                "input", "确认提交",
                AssistantStateKeys.CONVERSATION_PHASE, "CONFIRMING",
                AssistantStateKeys.MATCHED_TOOL_META, Map.of("toolCode", "oa.leave.apply"),
                AssistantStateKeys.COLLECTED_SLOTS, Map.of("reason", "个人事务", "start_date", "2026-03-01"),
                CodeactStateKeys.AVAILABLE_TOOL_NAMES, List.of("slot_collect", "slot_confirm")));

        Map<String, Object> updates = hook.beforeAgent(state, RunnableConfig.builder().build()).join();

        assertEquals("artifact_execute", updates.get(AssistantStateKeys.EXECUTION_CONFIRM_TOOL_NAME));
        assertEquals(List.of("slot_collect", "slot_confirm", "artifact_execute"),
                updates.get(CodeactStateKeys.AVAILABLE_TOOL_NAMES));
        AssistantMessage assistantMessage = firstAssistantMessage(updates);
        assertEquals("artifact_execute", assistantMessage.getToolCalls().get(0).name());
        assertFalse(org.springframework.util.StringUtils.hasText(assistantMessage.getText()));

        @SuppressWarnings("unchecked")
        Map<String, Object> args = new ObjectMapper().readValue(assistantMessage.getToolCalls().get(0).arguments(), Map.class);
        assertEquals("oa.leave.apply", args.get("toolCode"));
        assertEquals(Boolean.TRUE, args.get("confirmed"));
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

    private AssistantMessage firstAssistantMessage(Map<String, Object> updates) {
        List<?> messages = (List<?>) updates.get("messages");
        return assertInstanceOf(AssistantMessage.class, messages.get(0));
    }

    private PublishedToolDescriptor publishedArtifact(
            String artifactCode,
            String displayName,
            String description,
            String systemCode,
            String confirmationPolicyJson,
            String riskLevel,
            Long approvalPolicyId) {
        RuntimeArtifact.Interaction interaction = new RuntimeArtifact.Interaction(
                1L,
                artifactCode + ".interaction",
                "{\"slots\":[{\"name\":\"reason\",\"type\":\"string\",\"required\":true}]}",
                null,
                confirmationPolicyJson);
        Map<String, RuntimeArtifact.ActionBinding> actions = Map.of();
        if (riskLevel != null || approvalPolicyId != null) {
            actions = Map.of("submit", new RuntimeArtifact.ActionBinding(
                    1L,
                    artifactCode + ".submit",
                    1L,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    riskLevel,
                    approvalPolicyId,
                    null,
                    1));
        }
        RuntimeArtifact artifact = new RuntimeArtifact(
                1L,
                artifactCode,
                RuntimeArtifact.ArtifactType.WORKFLOW,
                displayName,
                1,
                null,
                null,
                null,
                null,
                interaction,
                new FlowDefinition(),
                actions,
                Map.of());
        return PublishedToolDescriptor.forArtifact(
                "tool-meta-catalog",
                "tool:" + artifactCode,
                displayName,
                null,
                description,
                false,
                systemCode,
                artifact);
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


