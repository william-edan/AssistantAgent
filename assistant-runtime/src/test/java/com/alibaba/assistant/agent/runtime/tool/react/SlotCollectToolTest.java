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

import com.alibaba.assistant.agent.controlplane.toolregistry.ToolMeta;
import com.alibaba.assistant.agent.controlplane.toolregistry.ToolMetaService;
import com.alibaba.assistant.agent.runtime.planner.DependencyResolver;
import com.alibaba.assistant.agent.runtime.planner.FieldMappingProcessor;
import com.alibaba.assistant.agent.runtime.planner.ToolExecutor;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.agent.tools.ToolContextConstants;
import com.alibaba.assistant.agent.slot.SlotCollectorService;
import com.alibaba.assistant.agent.slot.SlotEnricherService;
import com.alibaba.assistant.agent.slot.SlotSchemaParser;
import com.alibaba.assistant.agent.slot.computed.ComputedFieldProcessor;
import com.alibaba.assistant.agent.slot.model.CollectBehavior;
import com.alibaba.assistant.agent.slot.model.EnrichedSlot;
import com.alibaba.assistant.agent.slot.model.SlotAskMode;
import com.alibaba.assistant.agent.slot.model.SlotCollectStatus;
import com.alibaba.assistant.agent.slot.model.SlotDefinition;
import com.alibaba.assistant.agent.slot.model.SlotOption;
import com.alibaba.assistant.agent.slot.model.SlotPriority;
import com.alibaba.assistant.agent.slot.model.SlotValue;
import com.alibaba.assistant.agent.slot.model.ToolMetaSnapshot;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ToolContext;

import java.time.LocalDate;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class SlotCollectToolTest {

    @Test
    void shouldInferLeaveTypeAndDatesFromMessagesWhenInputMissing() {
        SlotCollectorService collector = mock(SlotCollectorService.class);
        SlotEnricherService enricher = mock(SlotEnricherService.class);
        ComputedFieldProcessor computed = mock(ComputedFieldProcessor.class);
        SlotSchemaParser parser = mock(SlotSchemaParser.class);

        SlotDefinition leaveType = slot("leave_type", SlotPriority.CORE, SlotAskMode.BATCH, true);
        leaveType.setType("enum");
        com.alibaba.assistant.agent.slot.model.SlotOptions options =
                new com.alibaba.assistant.agent.slot.model.SlotOptions();
        options.setEnumMapping(Map.of("事假", "1", "年假", "2", "病假", "4"));
        leaveType.setOptions(options);

        SlotDefinition startDate = slot("start_date", SlotPriority.CORE, SlotAskMode.BATCH, true);
        SlotDefinition endDate = slot("end_date", SlotPriority.CORE, SlotAskMode.BATCH, true);
        SlotDefinition reason = slot("reason", SlotPriority.CONFIRM, SlotAskMode.BATCH, false);
        List<SlotDefinition> definitions = List.of(leaveType, startDate, endDate, reason);

        when(parser.parse(any(ToolMetaSnapshot.class))).thenReturn(definitions);
        when(collector.collectFromAgent(anyMap(), eq(definitions), anyMap())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Map<String, Object> extraction = invocation.getArgument(0);
            Map<String, SlotValue> mapped = new LinkedHashMap<>();
            for (Map.Entry<String, Object> entry : extraction.entrySet()) {
                Object value = entry.getValue();
                mapped.put(entry.getKey(), SlotValue.resolved(
                        entry.getKey(),
                        String.valueOf(value),
                        value,
                        String.valueOf(value)));
            }
            return mapped;
        });
        when(collector.checkCollectionStatus(anyList(), anyMap())).thenReturn(SlotCollectStatus.COMPLETE);
        when(collector.buildFinalParams(anyList(), anyMap())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Map<String, SlotValue> slotValues = invocation.getArgument(1);
            return toResolvedMap(slotValues);
        });

        SlotCollectTool tool = new SlotCollectTool(collector, enricher, computed, parser, new ObjectMapper());
        SlotCollectTool.Request request = new SlotCollectTool.Request();
        request.slotSchema = "{\"slots\":[]}";
        request.extractedSlots = Collections.emptyMap();

        ToolContext toolContext = toolContextWithState(Map.of(
                "messages", List.of(new UserMessage("明天有的事情需要请假一天"))));
        SlotCollectTool.Response response = tool.apply(request, toolContext);

        String tomorrow = LocalDate.now().plusDays(1).toString();
        assertEquals(SlotCollectStatus.COMPLETE.name(), response.status);
        assertEquals("1", response.collected.get("leave_type"));
        assertEquals(tomorrow, response.collected.get("start_date"));
        assertEquals(tomorrow, response.collected.get("end_date"));
        assertEquals("个人事务", response.collected.get("reason"));
    }

    @Test
    void shouldKeepExplicitLeaveTypeWhenAlreadyExtracted() {
        SlotCollectorService collector = mock(SlotCollectorService.class);
        SlotEnricherService enricher = mock(SlotEnricherService.class);
        ComputedFieldProcessor computed = mock(ComputedFieldProcessor.class);
        SlotSchemaParser parser = mock(SlotSchemaParser.class);

        SlotDefinition leaveType = slot("leave_type", SlotPriority.CORE, SlotAskMode.BATCH, true);
        leaveType.setType("enum");
        List<SlotDefinition> definitions = List.of(leaveType);

        when(parser.parse(any(ToolMetaSnapshot.class))).thenReturn(definitions);
        when(collector.collectFromAgent(anyMap(), eq(definitions), anyMap())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Map<String, Object> extraction = invocation.getArgument(0);
            Map<String, SlotValue> mapped = new LinkedHashMap<>();
            for (Map.Entry<String, Object> entry : extraction.entrySet()) {
                Object value = entry.getValue();
                mapped.put(entry.getKey(), SlotValue.resolved(
                        entry.getKey(),
                        String.valueOf(value),
                        value,
                        String.valueOf(value)));
            }
            return mapped;
        });
        when(collector.checkCollectionStatus(anyList(), anyMap())).thenReturn(SlotCollectStatus.COMPLETE);
        when(collector.buildFinalParams(anyList(), anyMap())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Map<String, SlotValue> slotValues = invocation.getArgument(1);
            return toResolvedMap(slotValues);
        });

        SlotCollectTool tool = new SlotCollectTool(collector, enricher, computed, parser, new ObjectMapper());
        SlotCollectTool.Request request = new SlotCollectTool.Request();
        request.slotSchema = "{\"slots\":[]}";
        request.extractedSlots = Map.of("leave_type", "2");

        ToolContext toolContext = toolContextWithInput("明天有的事情需要请假一天");
        SlotCollectTool.Response response = tool.apply(request, toolContext);

        assertEquals(SlotCollectStatus.COMPLETE.name(), response.status);
        assertEquals("2", response.collected.get("leave_type"));
    }

    @Test
    void shouldInferTomorrowDateAndReasonFromUserInputWhenExtractionMissing() {
        SlotCollectorService collector = mock(SlotCollectorService.class);
        SlotEnricherService enricher = mock(SlotEnricherService.class);
        ComputedFieldProcessor computed = mock(ComputedFieldProcessor.class);
        SlotSchemaParser parser = mock(SlotSchemaParser.class);

        SlotDefinition startDate = slot("start_date", SlotPriority.CORE, SlotAskMode.BATCH, true);
        SlotDefinition endDate = slot("end_date", SlotPriority.CORE, SlotAskMode.BATCH, true);
        SlotDefinition reason = slot("reason", SlotPriority.CORE, SlotAskMode.BATCH, true);
        List<SlotDefinition> definitions = List.of(startDate, endDate, reason);

        when(parser.parse(any(ToolMetaSnapshot.class))).thenReturn(definitions);
        when(collector.collectFromAgent(anyMap(), eq(definitions), anyMap())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Map<String, Object> extraction = invocation.getArgument(0);
            Map<String, SlotValue> mapped = new LinkedHashMap<>();
            for (Map.Entry<String, Object> entry : extraction.entrySet()) {
                Object value = entry.getValue();
                mapped.put(entry.getKey(), SlotValue.resolved(
                        entry.getKey(),
                        String.valueOf(value),
                        value,
                        String.valueOf(value)));
            }
            return mapped;
        });
        when(collector.checkCollectionStatus(anyList(), anyMap())).thenReturn(SlotCollectStatus.COMPLETE);
        when(collector.buildFinalParams(anyList(), anyMap())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Map<String, SlotValue> slotValues = invocation.getArgument(1);
            return toResolvedMap(slotValues);
        });

        SlotCollectTool tool = new SlotCollectTool(collector, enricher, computed, parser, new ObjectMapper());
        SlotCollectTool.Request request = new SlotCollectTool.Request();
        request.slotSchema = "{\"slots\":[]}";
        request.extractedSlots = Collections.emptyMap();

        ToolContext toolContext = toolContextWithInput("我明天有的事情需要请假一天");
        SlotCollectTool.Response response = tool.apply(request, toolContext);

        String tomorrow = LocalDate.now().plusDays(1).toString();
        assertEquals(SlotCollectStatus.COMPLETE.name(), response.status);
        assertEquals(tomorrow, response.collected.get("start_date"));
        assertEquals(tomorrow, response.collected.get("end_date"));
        assertEquals("个人事务", response.collected.get("reason"));
    }

    @Test
    void shouldKeepExplicitReasonWhenAlreadyExtracted() {
        SlotCollectorService collector = mock(SlotCollectorService.class);
        SlotEnricherService enricher = mock(SlotEnricherService.class);
        ComputedFieldProcessor computed = mock(ComputedFieldProcessor.class);
        SlotSchemaParser parser = mock(SlotSchemaParser.class);

        SlotDefinition reason = slot("reason", SlotPriority.CORE, SlotAskMode.BATCH, true);
        List<SlotDefinition> definitions = List.of(reason);

        when(parser.parse(any(ToolMetaSnapshot.class))).thenReturn(definitions);
        when(collector.collectFromAgent(anyMap(), eq(definitions), anyMap())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Map<String, Object> extraction = invocation.getArgument(0);
            Map<String, SlotValue> mapped = new LinkedHashMap<>();
            for (Map.Entry<String, Object> entry : extraction.entrySet()) {
                Object value = entry.getValue();
                mapped.put(entry.getKey(), SlotValue.resolved(
                        entry.getKey(),
                        String.valueOf(value),
                        value,
                        String.valueOf(value)));
            }
            return mapped;
        });
        when(collector.checkCollectionStatus(anyList(), anyMap())).thenReturn(SlotCollectStatus.COMPLETE);
        when(collector.buildFinalParams(anyList(), anyMap())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Map<String, SlotValue> slotValues = invocation.getArgument(1);
            return toResolvedMap(slotValues);
        });

        SlotCollectTool tool = new SlotCollectTool(collector, enricher, computed, parser, new ObjectMapper());
        SlotCollectTool.Request request = new SlotCollectTool.Request();
        request.slotSchema = "{\"slots\":[]}";
        request.extractedSlots = Map.of("reason", "看病");

        ToolContext toolContext = toolContextWithInput("我明天有的事情需要请假一天");
        SlotCollectTool.Response response = tool.apply(request, toolContext);

        assertEquals(SlotCollectStatus.COMPLETE.name(), response.status);
        assertEquals("看病", response.collected.get("reason"));
    }

    @Test
    void shouldNotInvokeMergeWhenNoExtractedSlots() {
        SlotCollectorService collector = mock(SlotCollectorService.class);
        SlotEnricherService enricher = mock(SlotEnricherService.class);
        ComputedFieldProcessor computed = mock(ComputedFieldProcessor.class);
        SlotSchemaParser parser = mock(SlotSchemaParser.class);

        SlotDefinition reason = slot("reason", SlotPriority.CORE, SlotAskMode.BATCH, true);
        List<SlotDefinition> definitions = List.of(reason);

        when(parser.parse(any(ToolMetaSnapshot.class))).thenReturn(definitions);
        when(collector.checkCollectionStatus(anyList(), anyMap())).thenReturn(SlotCollectStatus.COLLECTING);
        when(collector.getNextSlotsToCollect(anyList(), anyMap(), any(CollectBehavior.class), anyInt()))
                .thenReturn(definitions);

        SlotCollectTool tool = new SlotCollectTool(collector, enricher, computed, parser, new ObjectMapper());
        SlotCollectTool.Request request = new SlotCollectTool.Request();
        request.slotSchema = "{\"slots\":[]}";
        request.extractedSlots = Collections.emptyMap();

        SlotCollectTool.Response response = tool.apply(request, null);

        assertEquals("COLLECTING", response.phase);
        verify(collector, never()).collectFromAgent(anyMap(), anyList(), anyMap());
    }

    @Test
    void shouldAutoSelectFirstOptionForAutoSlotWhenNoExplicitAutoSelectConfig() {
        SlotCollectorService collector = mock(SlotCollectorService.class);
        SlotEnricherService enricher = mock(SlotEnricherService.class);
        ComputedFieldProcessor computed = mock(ComputedFieldProcessor.class);
        SlotSchemaParser parser = mock(SlotSchemaParser.class);

        SlotDefinition reason = slot("reason", SlotPriority.CORE, SlotAskMode.BATCH, true);
        SlotDefinition flow = slot("check_flow_id", SlotPriority.OPTIONAL, SlotAskMode.AUTO, false);
        List<SlotDefinition> definitions = List.of(reason, flow);

        Map<String, Object> extracted = Map.of("reason", "personal");
        Map<String, SlotValue> merged = new LinkedHashMap<>();
        merged.put("reason", SlotValue.fromUser("reason", "personal"));

        EnrichedSlot reasonEnriched = new EnrichedSlot(reason);
        EnrichedSlot flowEnriched = new EnrichedSlot(flow);
        flowEnriched.setOptions(List.of(new SlotOption("Default Flow", 1001), new SlotOption("Backup Flow", 1002)));

        when(parser.parse(any(ToolMetaSnapshot.class))).thenReturn(definitions);
        when(collector.collectFromAgent(eq(extracted), eq(definitions), anyMap())).thenReturn(merged);
        when(enricher.enrichSlots(eq(definitions), eq("oa"), eq("u1")))
                .thenReturn(List.of(reasonEnriched, flowEnriched));
        when(collector.checkCollectionStatus(anyList(), anyMap())).thenReturn(SlotCollectStatus.COMPLETE);
        when(collector.buildFinalParams(anyList(), anyMap())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Map<String, SlotValue> slotValues = invocation.getArgument(1);
            return toResolvedMap(slotValues);
        });

        SlotCollectTool tool = new SlotCollectTool(collector, enricher, computed, parser, new ObjectMapper());
        SlotCollectTool.Request request = new SlotCollectTool.Request();
        request.slotSchema = "{\"slots\":[]}";
        request.systemCode = "oa";
        request.assistantUid = "u1";
        request.extractedSlots = extracted;

        SlotCollectTool.Response response = tool.apply(request, null);

        assertEquals(SlotCollectStatus.COMPLETE.name(), response.status);
        assertEquals("READY_TO_CONFIRM", response.phase);
        assertNotNull(response.collected);
        assertEquals(1001, response.collected.get("check_flow_id"));

        ArgumentCaptor<Map<String, SlotValue>> collectorMapCaptor = ArgumentCaptor.forClass(Map.class);
        verify(collector).checkCollectionStatus(eq(definitions), collectorMapCaptor.capture());
        assertEquals(1001, collectorMapCaptor.getValue().get("check_flow_id").getResolvedValue());
    }

    @Test
    void shouldApplyDependencyFieldMappingsBeforeSlotCollection() {
        SlotCollectorService collector = mock(SlotCollectorService.class);
        SlotEnricherService enricher = mock(SlotEnricherService.class);
        ComputedFieldProcessor computed = mock(ComputedFieldProcessor.class);
        SlotSchemaParser parser = mock(SlotSchemaParser.class);
        ToolMetaService toolMetaService = mock(ToolMetaService.class);
        DependencyResolver dependencyResolver = mock(DependencyResolver.class);
        ToolExecutor toolExecutor = mock(ToolExecutor.class);

        SlotDefinition employeeId = slot("employeeId", SlotPriority.CORE, SlotAskMode.BATCH, true);
        SlotDefinition reason = slot("reason", SlotPriority.CORE, SlotAskMode.BATCH, true);
        List<SlotDefinition> definitions = List.of(employeeId, reason);

        when(parser.parse(any(ToolMetaSnapshot.class))).thenReturn(definitions);
        when(collector.checkCollectionStatus(anyList(), anyMap())).thenReturn(SlotCollectStatus.COLLECTING);
        when(collector.getNextSlotsToCollect(anyList(), anyMap(), any(CollectBehavior.class), anyInt()))
                .thenReturn(List.of(reason));

        ToolMeta leaveApply = new ToolMeta();
        leaveApply.setToolCode("leave_apply");
        leaveApply.setInteractionPolicy("{\"dependsOn\":[\"current_user\"]}");
        when(toolMetaService.findLatestEnabledByToolCode("default", "leave_apply"))
                .thenReturn(Optional.of(leaveApply));

        List<DependencyResolver.ResolvedStep> resolvedSteps = List.of(
                new DependencyResolver.ResolvedStep(
                        "current_user",
                        "read current user",
                        List.of(new DependencyResolver.FieldMapping("current_user", "employeeId", "employeeId"))),
                new DependencyResolver.ResolvedStep("leave_apply", "apply leave", List.of()));
        when(dependencyResolver.resolve(eq("leave_apply"), any())).thenReturn(resolvedSteps);
        when(toolExecutor.execute(eq("default"), eq("current_user"), anyMap(), any()))
                .thenReturn(ToolExecutor.ExecutionResult.success(
                        "current_user",
                        Map.of("success", true),
                        Map.of("employeeId", "E001")));

        SlotCollectTool tool = new SlotCollectTool(
                collector,
                enricher,
                computed,
                parser,
                new ObjectMapper(),
                toolMetaService,
                dependencyResolver,
                new FieldMappingProcessor(),
                toolExecutor);
        SlotCollectTool.Request request = new SlotCollectTool.Request();
        request.toolCode = "leave_apply";
        request.slotSchema = "{\"slots\":[]}";
        request.extractedSlots = Collections.emptyMap();

        SlotCollectTool.Response response = tool.apply(request, null);

        assertEquals("COLLECTING", response.phase);
        assertEquals("E001", response.collected.get("employeeId"));
        verify(toolExecutor, times(1)).execute(eq("default"), eq("current_user"), anyMap(), any());
    }

    private static SlotDefinition slot(String name, SlotPriority priority, SlotAskMode askMode, boolean required) {
        SlotDefinition definition = new SlotDefinition();
        definition.setName(name);
        definition.setType("string");
        definition.setPriority(priority);
        definition.setAskMode(askMode);
        definition.setRequired(required);
        return definition;
    }

    private static Map<String, Object> toResolvedMap(Map<String, SlotValue> slotValues) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<String, SlotValue> entry : slotValues.entrySet()) {
            SlotValue value = entry.getValue();
            result.put(entry.getKey(), value != null ? value.getResolvedValue() : null);
        }
        return result;
    }

    private static ToolContext toolContextWithInput(String input) {
        OverAllState state = new OverAllState();
        state.updateState(Map.of("input", input));

        ToolContext toolContext = mock(ToolContext.class);
        when(toolContext.getContext()).thenReturn(Map.of(
                ToolContextConstants.AGENT_STATE_CONTEXT_KEY, state));
        return toolContext;
    }

    private static ToolContext toolContextWithState(Map<String, Object> stateUpdates) {
        OverAllState state = new OverAllState();
        state.updateState(stateUpdates != null ? stateUpdates : Map.of());

        ToolContext toolContext = mock(ToolContext.class);
        when(toolContext.getContext()).thenReturn(Map.of(
                ToolContextConstants.AGENT_STATE_CONTEXT_KEY, state));
        return toolContext;
    }
}
