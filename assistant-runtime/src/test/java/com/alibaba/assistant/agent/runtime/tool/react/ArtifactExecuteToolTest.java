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
package com.alibaba.assistant.agent.runtime.tool.react;

import com.alibaba.assistant.agent.common.enums.Language;
import com.alibaba.assistant.agent.common.tools.CodeExample;
import com.alibaba.assistant.agent.common.tools.CodeactTool;
import com.alibaba.assistant.agent.common.tools.CodeactToolMetadata;
import com.alibaba.assistant.agent.common.tools.DefaultCodeactToolMetadata;
import com.alibaba.assistant.agent.runtime.agent.AssistantStateKeys;
import com.alibaba.assistant.agent.runtime.compiler.RuntimeArtifact;
import com.alibaba.assistant.agent.runtime.execution.ArtifactRuntimeExecutor;
import com.alibaba.assistant.agent.runtime.registry.ArtifactPublicationLookupService;
import com.alibaba.assistant.agent.runtime.registry.PublishedToolDescriptor;
import com.alibaba.assistant.agent.slot.computed.ComputedFieldProcessor;
import com.alibaba.assistant.agent.slot.computed.DatePeriodPresetFunction;
import com.alibaba.assistant.agent.slot.computed.DateRangeLabelFunction;
import com.alibaba.assistant.agent.slot.computed.SelectorSwitchFunction;
import com.alibaba.assistant.agent.slot.model.ComputedFieldConfig;
import com.alibaba.assistant.agent.slot.model.SlotAskMode;
import com.alibaba.assistant.agent.slot.model.SlotDefinition;
import com.alibaba.assistant.agent.slot.model.SlotValue;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.agent.tools.ToolContextConstants;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ArtifactExecuteToolTest {

    @Test
    void shouldResolvePublishedArtifactAndExecuteByToolCode() {
        ArtifactPublicationLookupService lookupService = mock(ArtifactPublicationLookupService.class);
        ArtifactRuntimeExecutor runtimeExecutor = mock(ArtifactRuntimeExecutor.class);
        ObjectMapper objectMapper = new ObjectMapper();
        ArtifactExecuteTool tool = createTool(lookupService, runtimeExecutor, objectMapper);

        PublishedToolDescriptor descriptor = mock(PublishedToolDescriptor.class);
        RuntimeArtifact artifact = mock(RuntimeArtifact.class);
        when(artifact.getArtifactCode()).thenReturn("oa.leave.apply");
        ToolContext toolContext = new ToolContext(Map.of("space_code", "default", "agent_app_code", "hr-assistant"));
        when(lookupService.findPublishedArtifact("oa.leave.apply", toolContext)).thenReturn(Optional.of(descriptor));
        when(descriptor.isArtifactPublication()).thenReturn(true);
        when(descriptor.artifact()).thenReturn(artifact);
        when(runtimeExecutor.execute(same(descriptor), anyMap(), same(toolContext)))
                .thenReturn(Map.of("success", true, "artifactCode", "oa.leave.apply"));

        ArtifactExecuteTool.Request request = new ArtifactExecuteTool.Request();
        request.toolCode = "oa.leave.apply";
        request.params = Map.of("reason", "事假");
        request.confirmed = true;

        ArtifactExecuteTool.Response response = tool.apply(request, toolContext);

        assertTrue(response.success);
        assertEquals("oa.leave.apply", response.artifactCode);
        assertEquals(true, response.result.get("success"));
    }

    @Test
    void shouldExecutePublishedDirectToolWhenArtifactLookupResolvesToDirectPublication() throws Exception {
        ArtifactPublicationLookupService lookupService = mock(ArtifactPublicationLookupService.class);
        ArtifactRuntimeExecutor runtimeExecutor = mock(ArtifactRuntimeExecutor.class);
        ObjectMapper objectMapper = new ObjectMapper();
        ArtifactExecuteTool tool = createTool(lookupService, runtimeExecutor, objectMapper);

        ToolContext toolContext = new ToolContext(Map.of("space_code", "default", "agent_app_code", "hr-assistant"));
        CodeactTool directTool = mockCodeactTool("gougu_oa.leave_application", "gougu_oa_leave_application_execute");
        when(directTool.call(anyString(), same(toolContext)))
                .thenReturn("{\"success\":true,\"mode\":\"FLOW\",\"toolCode\":\"gougu_oa.leave_application\"}");
        PublishedToolDescriptor descriptor = PublishedToolDescriptor.forDirectTool(
                "tool-meta-catalog",
                "tool-meta-catalog:gougu_oa_leave_application_execute",
                "请假申请",
                directTool);
        when(lookupService.findPublishedArtifact("gougu_oa.leave_application", toolContext))
                .thenReturn(Optional.of(descriptor));

        ArtifactExecuteTool.Request request = new ArtifactExecuteTool.Request();
        request.toolCode = "gougu_oa.leave_application";
        request.params = Map.of("reason", "事假", "types", 1);
        request.confirmed = true;

        ArtifactExecuteTool.Response response = tool.apply(request, toolContext);

        assertTrue(response.success);
        assertEquals("gougu_oa.leave_application", response.artifactCode);
        assertEquals("FLOW", response.result.get("mode"));
        ArgumentCaptor<String> requestCaptor = ArgumentCaptor.forClass(String.class);
        verify(directTool).call(requestCaptor.capture(), same(toolContext));
        @SuppressWarnings("unchecked")
        Map<String, Object> toolInput = objectMapper.readValue(requestCaptor.getValue(), Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> nestedParams = (Map<String, Object>) toolInput.get("params");
        assertEquals("事假", toolInput.get("reason"));
        assertEquals(1, ((Number) toolInput.get("types")).intValue());
        assertEquals("事假", nestedParams.get("reason"));
        assertEquals(1, ((Number) nestedParams.get("types")).intValue());
        verifyNoInteractions(runtimeExecutor);
    }

    @Test
    void shouldNormalizeLeaveApplicationAliasesForPublishedDirectToolExecution() throws Exception {
        ArtifactPublicationLookupService lookupService = mock(ArtifactPublicationLookupService.class);
        ArtifactRuntimeExecutor runtimeExecutor = mock(ArtifactRuntimeExecutor.class);
        ObjectMapper objectMapper = new ObjectMapper();
        ArtifactExecuteTool tool = createTool(lookupService, runtimeExecutor, objectMapper);

        ToolContext toolContext = new ToolContext(Map.of("space_code", "default", "agent_app_code", "hr-assistant"));
        CodeactTool directTool = mockCodeactTool("gougu_oa.leave_application", "gougu_oa_leave_application_execute");
        when(directTool.call(anyString(), same(toolContext)))
                .thenReturn("{\"success\":true,\"mode\":\"FLOW\",\"toolCode\":\"gougu_oa.leave_application\"}");
        PublishedToolDescriptor descriptor = PublishedToolDescriptor.forDirectTool(
                "tool-meta-catalog",
                "tool-meta-catalog:gougu_oa_leave_application_execute",
                "请假申请",
                directTool);
        when(lookupService.findPublishedArtifact("gougu_oa.leave_application", toolContext))
                .thenReturn(Optional.of(descriptor));

        ArtifactExecuteTool.Request request = new ArtifactExecuteTool.Request();
        request.toolCode = "gougu_oa.leave_application";
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("leaveType", "事假");
        params.put("leaveDate", "2026-03-14");
        params.put("reason", "个人事务");
        params.put("startTime", "09:00");
        params.put("endTime", "18:00");
        request.params = params;
        request.confirmed = true;

        ArtifactExecuteTool.Response response = tool.apply(request, toolContext);

        assertTrue(response.success);
        ArgumentCaptor<String> requestCaptor = ArgumentCaptor.forClass(String.class);
        verify(directTool).call(requestCaptor.capture(), same(toolContext));
        @SuppressWarnings("unchecked")
        Map<String, Object> toolInput = objectMapper.readValue(requestCaptor.getValue(), Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> nestedParams = (Map<String, Object>) toolInput.get("params");
        assertEquals(1, ((Number) toolInput.get("types")).intValue());
        assertEquals("2026-03-14", toolInput.get("start_date"));
        assertEquals("2026-03-14", toolInput.get("end_date"));
        assertEquals("个人事务", toolInput.get("reason"));
        assertEquals(1, ((Number) nestedParams.get("types")).intValue());
        assertEquals("2026-03-14", nestedParams.get("start_date"));
        assertEquals("2026-03-14", nestedParams.get("end_date"));
        assertEquals("个人事务", nestedParams.get("reason"));
        verifyNoInteractions(runtimeExecutor);
    }

    @Test
    void shouldPreferStateCollectedParamsAndSanitizeNestedCollectionMarkersWhenConfirmed() {
        ArtifactPublicationLookupService lookupService = mock(ArtifactPublicationLookupService.class);
        ArtifactRuntimeExecutor runtimeExecutor = mock(ArtifactRuntimeExecutor.class);
        ObjectMapper objectMapper = new ObjectMapper();
        ArtifactExecuteTool tool = createTool(lookupService, runtimeExecutor, objectMapper);

        PublishedToolDescriptor descriptor = mock(PublishedToolDescriptor.class);
        RuntimeArtifact artifact = mock(RuntimeArtifact.class);
        OverAllState state = new OverAllState();
        state.updateState(Map.of(
                AssistantStateKeys.COLLECTED_SLOTS,
                Map.of(
                        "room_id", SlotValue.fromUser("room_id", 1),
                        "join_uids", SlotValue.fromUser("join_uids", List.of("1", "2", "7")),
                        "requirement", SlotValue.fromUser("requirement", List.of("13", "14")),
                        "num", SlotValue.fromUser("num", 5))));
        ToolContext toolContext = new ToolContext(Map.of(
                ToolContextConstants.AGENT_STATE_CONTEXT_KEY, state,
                "space_code", "default",
                "agent_app_code", "hr-assistant"));

        when(lookupService.findPublishedArtifact("oa.meeting_room_booking", toolContext)).thenReturn(Optional.of(descriptor));
        when(descriptor.isArtifactPublication()).thenReturn(true);
        when(descriptor.artifact()).thenReturn(artifact);
        when(artifact.getArtifactCode()).thenReturn("oa.meeting_room_booking");
        when(runtimeExecutor.execute(same(descriptor), anyMap(), same(toolContext)))
                .thenReturn(Map.of("success", true, "artifactCode", "oa.meeting_room_booking"));

        ArtifactExecuteTool.Request request = new ArtifactExecuteTool.Request();
        request.toolCode = "oa.meeting_room_booking";
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("join_uids", List.of("java.util.ArrayList", List.of("1", "2", "7")));
        params.put("requirement", List.of("java.util.LinkedList", List.of("java.util.ArrayList", List.of("13", "14"))));
        params.put("num", 6);
        request.params = params;
        request.confirmed = true;

        ArtifactExecuteTool.Response response = tool.apply(request, toolContext);

        assertTrue(response.success);
        ArgumentCaptor<Map<String, Object>> paramsCaptor = ArgumentCaptor.forClass(Map.class);
        verify(runtimeExecutor).execute(same(descriptor), paramsCaptor.capture(), same(toolContext));
        Map<String, Object> executionParams = paramsCaptor.getValue();
        assertEquals(1, executionParams.get("room_id"));
        assertEquals(List.of("1", "2", "7"), executionParams.get("join_uids"));
        assertEquals(List.of("13", "14"), executionParams.get("requirement"));
        assertEquals(6, ((Number) executionParams.get("num")).intValue());
    }

    @Test
    void shouldMaterializeDefaultsAndComputedFieldsForConfirmedArtifactExecution() {
        ArtifactPublicationLookupService lookupService = mock(ArtifactPublicationLookupService.class);
        ArtifactRuntimeExecutor runtimeExecutor = mock(ArtifactRuntimeExecutor.class);
        ObjectMapper objectMapper = new ObjectMapper();
        ArtifactExecuteTool tool = createTool(lookupService, runtimeExecutor, objectMapper);

        PublishedToolDescriptor descriptor = mock(PublishedToolDescriptor.class);
        RuntimeArtifact artifact = mock(RuntimeArtifact.class);
        OverAllState state = new OverAllState();
        state.updateState(Map.of(
                AssistantStateKeys.COLLECTED_SLOTS,
                Map.of(
                        "types", SlotValue.fromUser("types", 2),
                        "works", SlotValue.fromUser("works", "本周完成接口联调"),
                        "to_uids", SlotValue.fromUser("to_uids", "1")),
                AssistantStateKeys.SLOT_DEFINITIONS, workReportSlotDefinitions(),
                "current_date", "2026-03-15"));
        ToolContext toolContext = new ToolContext(Map.of(
                ToolContextConstants.AGENT_STATE_CONTEXT_KEY, state,
                "space_code", "default",
                "agent_app_code", "hr-assistant"));

        when(lookupService.findPublishedArtifact("gougu_oa.work_report", toolContext)).thenReturn(Optional.of(descriptor));
        when(descriptor.isArtifactPublication()).thenReturn(true);
        when(descriptor.artifact()).thenReturn(artifact);
        when(artifact.getArtifactCode()).thenReturn("gougu_oa.work_report");
        when(runtimeExecutor.execute(same(descriptor), anyMap(), same(toolContext)))
                .thenReturn(Map.of("success", true, "artifactCode", "gougu_oa.work_report"));

        ArtifactExecuteTool.Request request = new ArtifactExecuteTool.Request();
        request.toolCode = "gougu_oa.work_report";
        request.confirmed = true;

        ArtifactExecuteTool.Response response = tool.apply(request, toolContext);

        assertTrue(response.success);
        ArgumentCaptor<Map<String, Object>> paramsCaptor = ArgumentCaptor.forClass(Map.class);
        verify(runtimeExecutor).execute(same(descriptor), paramsCaptor.capture(), same(toolContext));
        Map<String, Object> executionParams = paramsCaptor.getValue();
        assertEquals(2, ((Number) executionParams.get("types")).intValue());
        assertEquals("2026-03-09", executionParams.get("start_date"));
        assertEquals("2026-03-15", executionParams.get("end_date"));
        assertEquals("2026-03-09 ~ 2026-03-15", executionParams.get("range_date"));
        assertEquals("2026-03-09 ~ 2026-03-15", executionParams.get("submit_range_date"));
        assertEquals(0, ((Number) executionParams.get("send")).intValue());
        assertEquals("本周完成接口联调", executionParams.get("works"));
        assertEquals("1", executionParams.get("to_uids"));
    }

    @Test
    void shouldReturnErrorWhenArtifactPublicationDoesNotExist() {
        ArtifactPublicationLookupService lookupService = mock(ArtifactPublicationLookupService.class);
        ArtifactRuntimeExecutor runtimeExecutor = mock(ArtifactRuntimeExecutor.class);
        ArtifactExecuteTool tool = createTool(lookupService, runtimeExecutor, new ObjectMapper());

        when(lookupService.findPublishedArtifact(eq("oa.leave.apply"), org.mockito.ArgumentMatchers.any()))
                .thenReturn(Optional.empty());

        ArtifactExecuteTool.Request request = new ArtifactExecuteTool.Request();
        request.toolCode = "oa.leave.apply";

        ArtifactExecuteTool.Response response = tool.apply(request, new ToolContext(Map.of()));

        assertTrue(!response.success);
        assertTrue(response.error.contains("oa.leave.apply"));
    }

    private ArtifactExecuteTool createTool(
            ArtifactPublicationLookupService lookupService,
            ArtifactRuntimeExecutor runtimeExecutor,
            ObjectMapper objectMapper) {
        ArtifactExecutionParamAssembler assembler = new ArtifactExecutionParamAssembler(
                new ComputedFieldProcessor(List.of(
                        new DatePeriodPresetFunction(),
                        new DateRangeLabelFunction(),
                        new SelectorSwitchFunction())),
                objectMapper);
        return new ArtifactExecuteTool(lookupService, runtimeExecutor, assembler, objectMapper);
    }

    private static List<SlotDefinition> workReportSlotDefinitions() {
        SlotDefinition types = new SlotDefinition();
        types.setName("types");
        types.setType("integer");

        SlotDefinition startDate = computedSlot(
                "start_date",
                "period_preset",
                Map.of(
                        "selector", "types",
                        "target", "start",
                        "anchor", "current_date",
                        "presets", Map.of("1", "DAY", "2", "WEEK", "3", "MONTH")));

        SlotDefinition endDate = computedSlot(
                "end_date",
                "period_preset",
                Map.of(
                        "selector", "types",
                        "target", "end",
                        "anchor", "current_date",
                        "presets", Map.of("1", "DAY", "2", "WEEK", "3", "MONTH")));

        SlotDefinition rangeDate = computedSlot(
                "range_date",
                "date_range_label",
                Map.of(
                        "start", "start_date",
                        "end", "end_date",
                        "collapse_same_day", true));
        rangeDate.setSubmit(false);

        SlotDefinition submitRangeDate = computedSlot(
                "submit_range_date",
                "selector_switch",
                Map.of(
                        "selector", "types",
                        "cases", Map.of("1", "", "2", "range_date", "3", "range_date"),
                        "default", ""));
        submitRangeDate.setSubmit(false);

        SlotDefinition works = new SlotDefinition();
        works.setName("works");
        works.setType("string");

        SlotDefinition send = new SlotDefinition();
        send.setName("send");
        send.setType("integer");
        send.setAskMode(SlotAskMode.FORM_ONLY);
        send.setDefaultValue(0);

        SlotDefinition toUids = new SlotDefinition();
        toUids.setName("to_uids");
        toUids.setType("string");

        return List.of(types, startDate, endDate, rangeDate, submitRangeDate, works, send, toUids);
    }

    private static SlotDefinition computedSlot(String name, String function, Map<String, Object> params) {
        SlotDefinition slotDefinition = new SlotDefinition();
        slotDefinition.setName(name);
        slotDefinition.setType("string");
        ComputedFieldConfig computedFieldConfig = new ComputedFieldConfig();
        computedFieldConfig.setEnabled(true);
        computedFieldConfig.setType(ComputedFieldConfig.ComputationType.FUNCTION);
        computedFieldConfig.setFunction(function);
        computedFieldConfig.setParams(params);
        slotDefinition.setComputed(computedFieldConfig);
        return slotDefinition;
    }

    private static CodeactTool mockCodeactTool(String toolCode, String toolName) {
        CodeactTool tool = mock(CodeactTool.class);
        ToolDefinition definition = DefaultToolDefinition.builder()
                .name(toolName)
                .description("请假申请")
                .inputSchema("{\"type\":\"object\",\"properties\":{}}")
                .build();
        CodeactToolMetadata metadata = DefaultCodeactToolMetadata.builder()
                .addSupportedLanguage(Language.PYTHON)
                .targetClassName("published_tools")
                .targetClassDescription("Published tools")
                .codeInvocationTemplate(toolName + "() -> Dict[str, Any]")
                .fewShots(List.of(new CodeExample("demo", "published_tools." + toolName + "()", "mock behavior")))
                .displayName("请假申请")
                .addAlias(toolCode)
                .returnDirect(false)
                .build();
        when(tool.getToolDefinition()).thenReturn(definition);
        when(tool.getCodeactMetadata()).thenReturn(metadata);
        return tool;
    }
}
