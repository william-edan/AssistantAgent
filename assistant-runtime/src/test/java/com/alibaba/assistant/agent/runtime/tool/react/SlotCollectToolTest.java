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
import com.alibaba.assistant.agent.common.tools.CodeactTool;
import com.alibaba.assistant.agent.common.tools.CodeactToolMetadata;
import com.alibaba.assistant.agent.common.tools.DefaultCodeactToolMetadata;
import com.alibaba.assistant.agent.runtime.agent.AssistantStateKeys;
import com.alibaba.assistant.agent.runtime.planner.DependencyResolver;
import com.alibaba.assistant.agent.runtime.planner.FieldMappingProcessor;
import com.alibaba.assistant.agent.runtime.planner.ToolExecutor;
import com.alibaba.assistant.agent.runtime.registry.ArtifactPublicationLookupService;
import com.alibaba.assistant.agent.runtime.registry.PublishedToolDescriptor;
import com.alibaba.assistant.agent.slot.SlotCollectorService;
import com.alibaba.assistant.agent.slot.SlotEnricherService;
import com.alibaba.assistant.agent.slot.SlotSchemaParser;
import com.alibaba.assistant.agent.slot.computed.ComputedFieldProcessor;
import com.alibaba.assistant.agent.slot.computed.DatePeriodPresetFunction;
import com.alibaba.assistant.agent.slot.computed.DateRangeLabelFunction;
import com.alibaba.assistant.agent.slot.model.EnrichedSlot;
import com.alibaba.assistant.agent.slot.model.SlotOption;
import com.alibaba.assistant.agent.slot.model.SlotValue;
import com.alibaba.assistant.agent.slot.model.ToolMetaSnapshot;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.agent.tools.ToolContextConstants;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SlotCollectToolTest {

    private static final String WORK_REPORT_SCHEMA = """
            {
              "slots": [
                {"name": "works", "type": "string", "priority": "CORE", "required": true, "ask_mode": "BATCH"}
              ]
            }
            """;


    private static final String WORK_REPORT_FLOW_SCHEMA = """
            {
              "slots": [
                {
                  "name": "types",
                  "type": "integer",
                  "title": "汇报类型",
                  "priority": "CORE",
                  "required": true,
                  "ask_mode": "BATCH",
                  "options": {
                    "source": "ENUM",
                    "enum_mapping": {
                      "日报": 1,
                      "周报": 2,
                      "月报": 3
                    }
                  }
                },
                {
                  "name": "start_date",
                  "type": "string",
                  "title": "开始日期",
                  "priority": "OPTIONAL",
                  "required": false,
                  "ask_mode": "AUTO",
                  "depends_on": ["types"],
                  "computed": {
                    "enabled": true,
                    "type": "FUNCTION",
                    "function": "period_preset",
                    "params": {
                      "selector": "types",
                      "target": "start",
                      "anchor": "current_date",
                      "presets": {
                        "1": "DAY",
                        "2": "WEEK",
                        "3": "MONTH"
                      }
                    }
                  }
                },
                {
                  "name": "end_date",
                  "type": "string",
                  "title": "结束日期",
                  "priority": "OPTIONAL",
                  "required": false,
                  "ask_mode": "AUTO",
                  "depends_on": ["types"],
                  "computed": {
                    "enabled": true,
                    "type": "FUNCTION",
                    "function": "period_preset",
                    "params": {
                      "selector": "types",
                      "target": "end",
                      "anchor": "current_date",
                      "presets": {
                        "1": "DAY",
                        "2": "WEEK",
                        "3": "MONTH"
                      }
                    }
                  }
                },
                {
                  "name": "range_date",
                  "type": "string",
                  "title": "汇报周期",
                  "priority": "OPTIONAL",
                  "required": false,
                  "ask_mode": "NEVER",
                  "depends_on": ["types"],
                  "submit": false,
                  "computed": {
                    "enabled": true,
                    "type": "FUNCTION",
                    "function": "date_range_label",
                    "params": {
                      "start": "start_date",
                      "end": "end_date",
                      "collapse_same_day": true
                    }
                  }
                },
                {
                  "name": "works",
                  "type": "string",
                  "title": "工作内容",
                  "priority": "CORE",
                  "required": true,
                  "ask_mode": "BATCH",
                  "depends_on": ["types"]
                },
                {
                  "name": "plans",
                  "type": "string",
                  "title": "工作计划",
                  "priority": "SUPPLEMENTARY",
                  "required": false,
                  "ask_mode": "AUTO",
                  "depends_on": ["types"]
                },
                {
                  "name": "remark",
                  "type": "string",
                  "title": "其他事项",
                  "priority": "SUPPLEMENTARY",
                  "required": false,
                  "ask_mode": "AUTO",
                  "depends_on": ["types"]
                }
              ]
            }
            """;

    private static final String STRUCTURED_OPTION_FORM_SCHEMA = """
            {
              "slots": [
                {
                  "name": "works",
                  "type": "string",
                  "title": "工作内容",
                  "priority": "CORE",
                  "required": true,
                  "ask_mode": "BATCH"
                },
                {
                  "name": "send",
                  "type": "integer",
                  "title": "发送方式",
                  "priority": "OPTIONAL",
                  "required": false,
                  "ask_mode": "FORM_ONLY",
                  "options": {
                    "source": "ENUM",
                    "enum_mapping": {
                      "仅保存": 0,
                      "保存并发送": 1
                    }
                  }
                }
              ]
            }
            """;

    private static final String LEAVE_SCHEMA = """
            {
              "slots": [
                {
                  "name": "leave_type",
                  "type": "enum",
                  "priority": "CORE",
                  "required": true,
                  "ask_mode": "BATCH",
                  "options": {
                    "source": "ENUM",
                    "enum_mapping": {
                      "事假": "1",
                      "年假": "2",
                      "病假": "4"
                    }
                  }
                },
                {"name": "start_date", "type": "date", "priority": "CORE", "required": true, "ask_mode": "BATCH"},
                {"name": "end_date", "type": "date", "priority": "CORE", "required": true, "ask_mode": "BATCH"},
                {"name": "reason", "type": "string", "priority": "OPTIONAL", "required": false, "ask_mode": "BATCH"}
              ]
            }
            """;

    private static final String ALIAS_DRIVEN_LEAVE_SCHEMA = """
            {
              "slots": [
                {
                  "name": "leave_kind",
                  "type": "enum",
                  "title": "类型",
                  "aliases": ["请假类型"],
                  "priority": "CORE",
                  "required": true,
                  "ask_mode": "BATCH",
                  "options": {
                    "source": "ENUM",
                    "enum_mapping": {
                      "事假": "1",
                      "年假": "2",
                      "病假": "4"
                    }
                  }
                },
                {
                  "name": "leave_from",
                  "type": "date",
                  "aliases": ["开始日期"],
                  "priority": "CORE",
                  "required": true,
                  "ask_mode": "BATCH"
                },
                {
                  "name": "leave_to",
                  "type": "date",
                  "aliases": ["结束日期"],
                  "priority": "CORE",
                  "required": true,
                  "ask_mode": "BATCH"
                },
                {
                  "name": "memo",
                  "type": "string",
                  "title": "备注",
                  "aliases": ["请假原因", "原因"],
                  "priority": "OPTIONAL",
                  "required": false,
                  "ask_mode": "BATCH"
                }
              ]
            }
            """;

    private static final String DEPENDENCY_SCHEMA = """
            {
              "slots": [
                {"name": "applicant_id", "type": "string", "priority": "CORE", "required": true, "ask_mode": "BATCH"}
              ]
            }
            """;

    private static final String APPROVER_OPTION_SCHEMA = """
            {
              "slots": [
                {
                  "name": "approver_id",
                  "type": "string",
                  "priority": "CORE",
                  "required": true,
                  "ask_mode": "BATCH",
                  "options": {
                    "source": "TOOL",
                    "tool": {
                      "toolCode": "oa.user.options",
                      "resultPath": "items",
                      "labelField": "name",
                      "valueField": "id"
                    }
                  }
                }
              ]
            }
            """;


    private static final String MEETING_SCHEMA = """
            {
              "slots": [
                {"name": "room_id", "type": "integer", "title": "会议室", "priority": "CORE", "required": true, "ask_mode": "BATCH", "ui_component": "select"},
                {"name": "title", "type": "string", "title": "会议主题", "priority": "CORE", "required": true, "ask_mode": "BATCH", "ui_component": "text"},
                {"name": "start_date", "type": "string", "title": "开始时间", "priority": "CORE", "required": true, "ask_mode": "BATCH", "ui_component": "datetime"},
                {"name": "end_date", "type": "string", "title": "结束时间", "priority": "CORE", "required": true, "ask_mode": "BATCH", "ui_component": "datetime"},
                {"name": "num", "type": "integer", "title": "会议人数", "priority": "CORE", "required": true, "ask_mode": "BATCH", "ui_component": "number"},
                {"name": "requirement", "type": "array", "title": "会议需求", "priority": "CORE", "required": true, "ask_mode": "BATCH", "ui_component": "checkbox"},
                {"name": "join_uids", "type": "array", "title": "参会人员", "priority": "SUPPLEMENTARY", "required": false, "ask_mode": "AUTO", "ui_component": "select"},
                {"name": "check_uids", "type": "string", "title": "审批人", "priority": "OPTIONAL", "required": false, "ask_mode": "AUTO", "ui_component": "select"}
              ]
            }
            """;

    private static final String ALIAS_DRIVEN_MEETING_SCHEMA = """
            {
              "slots": [
                {
                  "name": "topic",
                  "type": "string",
                  "title": "会议主题",
                  "aliases": ["主题"],
                  "priority": "CORE",
                  "required": true,
                  "ask_mode": "BATCH"
                },
                {
                  "name": "from_at",
                  "type": "string",
                  "aliases": ["开始时间"],
                  "priority": "CORE",
                  "required": true,
                  "ask_mode": "BATCH",
                  "ui_component": "datetime"
                },
                {
                  "name": "to_at",
                  "type": "string",
                  "aliases": ["结束时间"],
                  "priority": "CORE",
                  "required": true,
                  "ask_mode": "BATCH",
                  "ui_component": "datetime"
                },
                {
                  "name": "attendee_count",
                  "type": "integer",
                  "aliases": ["人数"],
                  "priority": "CORE",
                  "required": true,
                  "ask_mode": "BATCH"
                }
              ]
            }
            """;

    private static final String LEAVE_EDIT_SCHEMA = """
            {
              "slots": [
                {
                  "name": "types",
                  "type": "integer",
                  "priority": "CORE",
                  "required": true,
                  "ask_mode": "BATCH",
                  "options": {
                    "source": "ENUM",
                    "enum_mapping": {
                      "事假": 1,
                      "年假": 2,
                      "病假": 4
                    }
                  }
                },
                {"name": "start_date", "type": "string", "priority": "CORE", "required": true, "ask_mode": "BATCH"},
                {"name": "end_date", "type": "string", "priority": "CORE", "required": true, "ask_mode": "BATCH"},
                {"name": "reason", "type": "string", "priority": "CORE", "required": true, "ask_mode": "BATCH"},
                {
                  "name": "check_uids",
                  "title": "审批人",
                  "type": "string",
                  "priority": "SUPPLEMENTARY",
                  "required": false,
                  "ask_mode": "AUTO",
                  "options": {
                    "source": "TOOL",
                    "tool": {
                      "toolCode": "gougu_oa.approver_candidates",
                      "resultPath": "data",
                      "labelField": "name",
                      "valueField": "id"
                    }
                  }
                }
              ]
            }
            """;

    @Test
    void shouldApplyCurrentTurnSlotValuesWhenCollecting() {
        ObjectMapper objectMapper = new ObjectMapper();
        SlotEnricherService enricher = mock(SlotEnricherService.class);
        when(enricher.enrichSlots(anyList(), anyString(), anyString())).thenReturn(new ArrayList<>());

        SlotCollectTool tool = new SlotCollectTool(
                new SlotCollectorService(),
                enricher,
                new ComputedFieldProcessor(List.of(new DatePeriodPresetFunction(), new DateRangeLabelFunction())),
                new SlotSchemaParser(objectMapper),
                objectMapper);

        OverAllState state = stateWithSnapshot(snapshot("work.report", WORK_REPORT_SCHEMA, null));
        state.updateState(Map.of(
                AssistantStateKeys.SYSTEM_CODE, "oa",
                AssistantStateKeys.ASSISTANT_UID, "u1",
                AssistantStateKeys.CURRENT_TURN_SLOT_INPUTS, Map.of("works", "完成需求评审")));

        SlotCollectTool.Response response = tool.apply(new SlotCollectTool.Request(), toolContext(state));

        assertEquals("COMPLETE", response.status);
        assertEquals("完成需求评审", response.collected.get("works"));
    }

    @Test
    void shouldApplyFlatStateDeltaSlotValuesAndClearTransientEntriesWhenCollecting() {
        ObjectMapper objectMapper = new ObjectMapper();
        SlotEnricherService enricher = mock(SlotEnricherService.class);
        when(enricher.enrichSlots(anyList(), anyString(), anyString())).thenReturn(new ArrayList<>());

        SlotCollectTool tool = new SlotCollectTool(
                new SlotCollectorService(),
                enricher,
                new ComputedFieldProcessor(List.of(new DatePeriodPresetFunction(), new DateRangeLabelFunction())),
                new SlotSchemaParser(objectMapper),
                objectMapper);

        OverAllState state = stateWithSnapshot(snapshot("work.report", WORK_REPORT_SCHEMA, null));
        state.updateState(Map.of(
                AssistantStateKeys.SYSTEM_CODE, "oa",
                AssistantStateKeys.ASSISTANT_UID, "u1",
                "works", "完成需求评审"));

        SlotCollectTool.Response response = tool.apply(new SlotCollectTool.Request(), toolContext(state));

        assertEquals("COMPLETE", response.status);
        assertEquals("完成需求评审", response.collected.get("works"));
        assertTrue(state.value("works", Object.class).isEmpty());
    }

    @Test
    void shouldPreserveExplicitStructuredOptionValueWithoutTextFieldCue() {
        ObjectMapper objectMapper = new ObjectMapper();
        SlotEnricherService enricher = mock(SlotEnricherService.class);
        when(enricher.enrichSlots(anyList(), anyString(), anyString())).thenReturn(new ArrayList<>());

        SlotCollectTool tool = new SlotCollectTool(
                new SlotCollectorService(),
                enricher,
                new ComputedFieldProcessor(List.of(new DatePeriodPresetFunction(), new DateRangeLabelFunction())),
                new SlotSchemaParser(objectMapper),
                objectMapper);

        OverAllState state = stateWithSnapshot(snapshot("work.report", STRUCTURED_OPTION_FORM_SCHEMA, null));
        state.updateState(Map.of(
                AssistantStateKeys.SYSTEM_CODE, "oa",
                AssistantStateKeys.ASSISTANT_UID, "u1",
                AssistantStateKeys.CURRENT_TURN_SLOT_INPUTS, Map.of(
                        "works", "完成需求评审",
                        "send", 0),
                AssistantStateKeys.ENRICHED_SLOTS, new ArrayList<>(List.of(
                        enrichedSlot("send", "发送方式", List.of(
                                new SlotOption("仅保存", 0),
                                new SlotOption("保存并发送", 1))))),
                "input", "补充汇报内容"));

        SlotCollectTool.Response response = tool.apply(new SlotCollectTool.Request(), toolContext(state));

        assertEquals("COMPLETE", response.status);
        assertEquals("完成需求评审", response.collected.get("works"));
        assertEquals(0, ((Number) response.collected.get("send")).intValue());
    }

    @Test
    void shouldCollectWorkReportTypeBeforeOtherSlots() {
        ObjectMapper objectMapper = new ObjectMapper();
        SlotEnricherService enricher = mock(SlotEnricherService.class);
        when(enricher.enrichSlots(anyList(), anyString(), anyString())).thenReturn(new ArrayList<>());

        SlotCollectTool tool = new SlotCollectTool(
                new SlotCollectorService(),
                enricher,
                new ComputedFieldProcessor(List.of(new DatePeriodPresetFunction(), new DateRangeLabelFunction())),
                new SlotSchemaParser(objectMapper),
                objectMapper);

        OverAllState state = stateWithSnapshot(snapshot("gougu_oa.work_report", WORK_REPORT_FLOW_SCHEMA, null));
        state.updateState(Map.of(
                AssistantStateKeys.SYSTEM_CODE, "oa",
                AssistantStateKeys.ASSISTANT_UID, "u1",
                "current_date", "2026-03-15",
                "input", "我要写汇报"));

        SlotCollectTool.Response response = tool.apply(new SlotCollectTool.Request(), toolContext(state));

        assertEquals("COLLECTING", response.status);
        assertNotNull(response.missing);
        assertFalse(response.missing.isEmpty());
        assertEquals("types", response.missing.get(0).name);
        assertFalse(response.collected.containsKey("start_date"));
        assertFalse(response.collected.containsKey("end_date"));
    }

    @Test
    void shouldInferWeeklyWorkReportTypeAndAutofillCurrentWeek() {
        ObjectMapper objectMapper = new ObjectMapper();
        SlotEnricherService enricher = mock(SlotEnricherService.class);
        when(enricher.enrichSlots(anyList(), anyString(), anyString())).thenReturn(new ArrayList<>());

        SlotCollectTool tool = new SlotCollectTool(
                new SlotCollectorService(),
                enricher,
                new ComputedFieldProcessor(List.of(new DatePeriodPresetFunction(), new DateRangeLabelFunction())),
                new SlotSchemaParser(objectMapper),
                objectMapper);

        OverAllState state = stateWithSnapshot(snapshot("gougu_oa.work_report", WORK_REPORT_FLOW_SCHEMA, null));
        state.updateState(Map.of(
                AssistantStateKeys.SYSTEM_CODE, "oa",
                AssistantStateKeys.ASSISTANT_UID, "u1",
                "current_date", "2026-03-15",
                "input", "我要写周报"));

        SlotCollectTool.Request request = new SlotCollectTool.Request();
        request.extractedSlots = Map.of("works", "完成需求评审");

        SlotCollectTool.Response response = tool.apply(request, toolContext(state));

        assertEquals("COMPLETE", response.status);
        assertEquals(2, ((Number) response.collected.get("types")).intValue());
        assertEquals("2026-03-09", response.collected.get("start_date"));
        assertEquals("2026-03-15", response.collected.get("end_date"));
        assertEquals("2026-03-09 ~ 2026-03-15", response.collected.get("range_date"));
    }

    @Test
    void shouldCaptureFollowUpFreeTextIntoPrimaryWorkContentSlot() {
        ObjectMapper objectMapper = new ObjectMapper();
        SlotEnricherService enricher = mock(SlotEnricherService.class);
        when(enricher.enrichSlots(anyList(), anyString(), anyString())).thenReturn(new ArrayList<>());

        SlotCollectTool tool = new SlotCollectTool(
                new SlotCollectorService(),
                enricher,
                new ComputedFieldProcessor(List.of(new DatePeriodPresetFunction(), new DateRangeLabelFunction())),
                new SlotSchemaParser(objectMapper),
                objectMapper);

        OverAllState state = stateWithSnapshot(snapshot("gougu_oa.work_report", WORK_REPORT_FLOW_SCHEMA, null));
        state.updateState(Map.of(
                AssistantStateKeys.SYSTEM_CODE, "oa",
                AssistantStateKeys.ASSISTANT_UID, "u1",
                AssistantStateKeys.CONVERSATION_PHASE, "COLLECTING",
                AssistantStateKeys.COLLECT_ROUND, 2,
                AssistantStateKeys.LAST_COLLECT_USER_INPUT, "周报",
                AssistantStateKeys.COLLECTED_SLOTS, Map.of(
                        "types", SlotValue.resolved("types", "2", 2, "2")),
                "current_date", "2026-03-15",
                "input", "本周完成了工作汇报周期规则整改、真实启动验证和接口联调"));

        SlotCollectTool.Response response = tool.apply(new SlotCollectTool.Request(), toolContext(state));

        assertEquals("COMPLETE", response.status);
        assertEquals("本周完成了工作汇报周期规则整改、真实启动验证和接口联调", response.collected.get("works"));
        assertEquals(2, ((Number) response.collected.get("types")).intValue());
        assertEquals("2026-03-09", response.collected.get("start_date"));
        assertEquals("2026-03-15", response.collected.get("end_date"));
    }

    @Test
    void shouldInferSingleDayRangeForDailyWorkReport() {
        ObjectMapper objectMapper = new ObjectMapper();
        SlotEnricherService enricher = mock(SlotEnricherService.class);
        when(enricher.enrichSlots(anyList(), anyString(), anyString())).thenReturn(new ArrayList<>());

        SlotCollectTool tool = new SlotCollectTool(
                new SlotCollectorService(),
                enricher,
                new ComputedFieldProcessor(List.of(new DatePeriodPresetFunction(), new DateRangeLabelFunction())),
                new SlotSchemaParser(objectMapper),
                objectMapper);

        OverAllState state = stateWithSnapshot(snapshot("gougu_oa.work_report", WORK_REPORT_FLOW_SCHEMA, null));
        state.updateState(Map.of(
                AssistantStateKeys.SYSTEM_CODE, "oa",
                AssistantStateKeys.ASSISTANT_UID, "u1",
                "current_date", "2026-03-15"));

        SlotCollectTool.Request request = new SlotCollectTool.Request();
        request.extractedSlots = Map.of("types", 1, "works", "完成需求评审");

        SlotCollectTool.Response response = tool.apply(request, toolContext(state));

        assertEquals("COMPLETE", response.status);
        assertEquals("2026-03-15", response.collected.get("start_date"));
        assertEquals("2026-03-15", response.collected.get("end_date"));
        assertEquals("2026-03-15", response.collected.get("range_date"));
    }

    @Test
    void shouldInferCurrentMonthRangeForMonthlyWorkReport() {
        ObjectMapper objectMapper = new ObjectMapper();
        SlotEnricherService enricher = mock(SlotEnricherService.class);
        when(enricher.enrichSlots(anyList(), anyString(), anyString())).thenReturn(new ArrayList<>());

        SlotCollectTool tool = new SlotCollectTool(
                new SlotCollectorService(),
                enricher,
                new ComputedFieldProcessor(List.of(new DatePeriodPresetFunction(), new DateRangeLabelFunction())),
                new SlotSchemaParser(objectMapper),
                objectMapper);

        OverAllState state = stateWithSnapshot(snapshot("gougu_oa.work_report", WORK_REPORT_FLOW_SCHEMA, null));
        state.updateState(Map.of(
                AssistantStateKeys.SYSTEM_CODE, "oa",
                AssistantStateKeys.ASSISTANT_UID, "u1",
                "current_date", "2026-03-15"));

        SlotCollectTool.Request request = new SlotCollectTool.Request();
        request.extractedSlots = Map.of("types", 3, "works", "完成需求评审");

        SlotCollectTool.Response response = tool.apply(request, toolContext(state));

        assertEquals("COMPLETE", response.status);
        assertEquals("2026-03-01", response.collected.get("start_date"));
        assertEquals("2026-03-31", response.collected.get("end_date"));
        assertEquals("2026-03-01 ~ 2026-03-31", response.collected.get("range_date"));
    }

    @Test
    void shouldClearStaleJumpToAfterPersistingCollectionState() {
        ObjectMapper objectMapper = new ObjectMapper();
        SlotEnricherService enricher = mock(SlotEnricherService.class);
        when(enricher.enrichSlots(anyList(), anyString(), anyString())).thenReturn(new ArrayList<>());

        SlotCollectTool tool = new SlotCollectTool(
                new SlotCollectorService(),
                enricher,
                new ComputedFieldProcessor(List.of(new DatePeriodPresetFunction(), new DateRangeLabelFunction())),
                new SlotSchemaParser(objectMapper),
                objectMapper);

        OverAllState state = stateWithSnapshot(snapshot("work.report", WORK_REPORT_SCHEMA, null));
        state.updateState(Map.of(
                "input", "本周完成了需求评审",
                "jump_to", "tool",
                AssistantStateKeys.SYSTEM_CODE, "oa",
                AssistantStateKeys.ASSISTANT_UID, "u1"));

        SlotCollectTool.Request request = new SlotCollectTool.Request();
        request.extractedSlots = Map.of("works", "完成需求评审");

        SlotCollectTool.Response response = tool.apply(request, toolContext(state));

        assertEquals("COMPLETE", response.status);
        assertEquals("完成需求评审", response.collected.get("works"));
        assertTrue(state.value("jump_to", Object.class).isEmpty());
    }

    @Test
    void shouldInferLeaveTypeAndDatesFromMessagesWhenInputMissing() {
        ObjectMapper objectMapper = new ObjectMapper();
        SlotEnricherService enricher = mock(SlotEnricherService.class);
        when(enricher.enrichSlots(anyList(), anyString(), anyString())).thenReturn(new ArrayList<>());

        SlotCollectTool tool = new SlotCollectTool(
                new SlotCollectorService(),
                enricher,
                new ComputedFieldProcessor(List.of(new DatePeriodPresetFunction(), new DateRangeLabelFunction())),
                new SlotSchemaParser(objectMapper),
                objectMapper);

        OverAllState state = stateWithSnapshot(snapshot("leave_application", LEAVE_SCHEMA, null));
        state.updateState(Map.of(
                AssistantStateKeys.SYSTEM_CODE, "oa",
                AssistantStateKeys.ASSISTANT_UID, "u1",
                "messages", List.of(new UserMessage("明天家里有事需要请假一天"))));

        SlotCollectTool.Response response = tool.apply(new SlotCollectTool.Request(), toolContext(state));

        String tomorrow = LocalDate.now().plusDays(1).toString();
        assertEquals("COMPLETE", response.status);
        assertEquals("1", response.collected.get("leave_type"));
        assertEquals(tomorrow, response.collected.get("start_date"));
        assertEquals(tomorrow, response.collected.get("end_date"));
        assertEquals("家里有事", response.collected.get("reason"));
    }

    @Test
    void shouldApplyDependencyFieldMappingsFromPublishedQueryTool() {
        ObjectMapper objectMapper = new ObjectMapper();
        SlotEnricherService enricher = mock(SlotEnricherService.class);
        when(enricher.enrichSlots(anyList(), anyString(), anyString())).thenReturn(new ArrayList<>());
        ArtifactPublicationLookupService lookupService = mock(ArtifactPublicationLookupService.class);
        ToolExecutor toolExecutor = mock(ToolExecutor.class);

        ToolDefinition definition = DefaultToolDefinition.builder()
                .name("oa.current.user")
                .description("Current user")
                .inputSchema("{\"type\":\"object\",\"properties\":{}}")
                .build();
        StubCodeactTool directTool = new StubCodeactTool(
                definition,
                DefaultCodeactToolMetadata.builder()
                        .addSupportedLanguage(Language.PYTHON)
                        .displayName("Current user")
                        .addAlias("oa.current.user")
                        .build(),
                "{}");

        when(lookupService.findPublishedArtifact(eq("oa.current.user"), any()))
                .thenReturn(Optional.of(new PublishedToolDescriptor(
                        "tool-meta-catalog",
                        "oa.current.user@1",
                        "Current user",
                        null,
                        null,
                        false,
                        "oa",
                        null,
                        directTool)));
        when(toolExecutor.execute(eq("default"), eq("oa.current.user"), anyMap(), any()))
                .thenReturn(ToolExecutor.ExecutionResult.success(
                        "oa.current.user",
                        Map.of("success", true),
                        Map.of("employeeId", "E001")));

        SlotCollectTool tool = new SlotCollectTool(
                new SlotCollectorService(),
                enricher,
                new ComputedFieldProcessor(List.of(new DatePeriodPresetFunction(), new DateRangeLabelFunction())),
                new SlotSchemaParser(objectMapper),
                objectMapper,
                new DependencyResolver(objectMapper),
                new FieldMappingProcessor(),
                toolExecutor,
                lookupService);

        ToolMetaSnapshot snapshot = snapshot(
                "leave.apply",
                DEPENDENCY_SCHEMA,
                """
                        {
                          "dependsOn": ["oa.current.user"],
                          "fieldMappings": [
                            {"fromTool": "oa.current.user", "fromField": "employeeId", "toField": "applicant_id"}
                          ]
                        }
                        """);
        OverAllState state = stateWithSnapshot(snapshot);
        state.updateState(Map.of(
                AssistantStateKeys.SYSTEM_CODE, "oa",
                AssistantStateKeys.ASSISTANT_UID, "u1",
                AssistantStateKeys.THREAD_ID, "thread-1"));

        SlotCollectTool.Response response = tool.apply(new SlotCollectTool.Request(), toolContext(state));

        assertEquals("COMPLETE", response.status);
        assertEquals("E001", response.collected.get("applicant_id"));
        verify(toolExecutor).execute(eq("default"), eq("oa.current.user"), anyMap(), any());
    }

    @Test
    void shouldResolveToolBackedOptionsFromQueryTool() {
        ObjectMapper objectMapper = new ObjectMapper();
        SlotEnricherService enricher = mock(SlotEnricherService.class);
        when(enricher.enrichSlots(anyList(), anyString(), anyString())).thenReturn(new ArrayList<>());
        ToolExecutor toolExecutor = mock(ToolExecutor.class);
        when(toolExecutor.execute(eq("default"), eq("oa.user.options"), anyMap(), any()))
                .thenReturn(ToolExecutor.ExecutionResult.success(
                        "oa.user.options",
                        Map.of("success", true, "items", List.of(Map.of("id", 4, "name", "直属领导"))),
                        Map.of()));

        SlotCollectTool tool = new SlotCollectTool(
                new SlotCollectorService(),
                enricher,
                new ComputedFieldProcessor(List.of(new DatePeriodPresetFunction(), new DateRangeLabelFunction())),
                new SlotSchemaParser(objectMapper),
                objectMapper,
                null,
                new FieldMappingProcessor(),
                toolExecutor,
                null);

        OverAllState state = stateWithSnapshot(snapshot("leave.approver", APPROVER_OPTION_SCHEMA, null));
        state.updateState(Map.of(
                AssistantStateKeys.SYSTEM_CODE, "oa",
                AssistantStateKeys.ASSISTANT_UID, "u1"));

        SlotCollectTool.Response response = tool.apply(new SlotCollectTool.Request(), toolContext(state));

        assertEquals("COLLECTING", response.phase);
        assertNotNull(response.enrichedSlots);
        EnrichedSlot approverSlot = response.enrichedSlots.stream()
                .filter(item -> item.getDefinition() != null && "approver_id".equals(item.getDefinition().getName()))
                .findFirst()
                .orElseThrow();
        assertFalse(approverSlot.getOptions().isEmpty());
        SlotOption option = approverSlot.getOptions().get(0);
        assertEquals("直属领导", option.getLabel());
        assertEquals(4, ((Number) option.getValue()).intValue());
    }

    @Test
    void shouldWaitWhenNoNewUserInputDetectedInSameTurn() {
        ObjectMapper objectMapper = new ObjectMapper();
        SlotEnricherService enricher = mock(SlotEnricherService.class);
        when(enricher.enrichSlots(anyList(), anyString(), anyString())).thenReturn(new ArrayList<>());

        SlotCollectTool tool = new SlotCollectTool(
                new SlotCollectorService(),
                enricher,
                new ComputedFieldProcessor(List.of(new DatePeriodPresetFunction(), new DateRangeLabelFunction())),
                new SlotSchemaParser(objectMapper),
                objectMapper);

        OverAllState state = stateWithSnapshot(snapshot("work.report", WORK_REPORT_SCHEMA, null));
        state.updateState(Map.of(
                AssistantStateKeys.SYSTEM_CODE, "oa",
                AssistantStateKeys.ASSISTANT_UID, "u1",
                AssistantStateKeys.CONVERSATION_PHASE, "CONFIRMING",
                AssistantStateKeys.LAST_COLLECT_USER_INPUT, "本周完成了需求评审",
                "input", "本周完成了需求评审"));

        SlotCollectTool.Request request = new SlotCollectTool.Request();
        request.extractedSlots = Map.of("works", "完成需求评审");

        SlotCollectTool.Response response = tool.apply(request, toolContext(state));

        assertEquals("COLLECTING", response.phase);
        assertEquals("No new user input detected; waiting for user input.", response.message);
        assertTrue(response.collected == null || response.collected.isEmpty());
    }

    @Test
    void shouldPreferTrailingUserMessageOverStaleInputWhenContinuingLeaveCollection() {
        ObjectMapper objectMapper = new ObjectMapper();
        SlotEnricherService enricher = mock(SlotEnricherService.class);
        when(enricher.enrichSlots(anyList(), anyString(), anyString())).thenReturn(new ArrayList<>());

        SlotCollectTool tool = new SlotCollectTool(
                new SlotCollectorService(),
                enricher,
                new ComputedFieldProcessor(List.of(new DatePeriodPresetFunction(), new DateRangeLabelFunction())),
                new SlotSchemaParser(objectMapper),
                objectMapper);

        OverAllState state = stateWithSnapshot(snapshot("gougu_oa.leave_application", LEAVE_SCHEMA, null));
        state.updateState(Map.of(
                AssistantStateKeys.SYSTEM_CODE, "oa",
                AssistantStateKeys.ASSISTANT_UID, "u1",
                AssistantStateKeys.CONVERSATION_PHASE, "COLLECTING",
                AssistantStateKeys.LAST_COLLECT_USER_INPUT, "我要请假",
                "input", "我要请假",
                "messages", List.of(
                        org.springframework.ai.chat.messages.AssistantMessage.builder()
                                .content("请补充结束日期")
                                .build(),
                        new UserMessage("请假类型：事假，开始日期：2026-03-17，结束日期：2026-03-18，请假原因：123123")),
                AssistantStateKeys.COLLECTED_SLOTS, Map.of(
                        "leave_type", SlotValue.resolved("leave_type", "1", "1", "1"),
                        "start_date", SlotValue.resolved("start_date", "2026-03-17", "2026-03-17", "2026-03-17"))));

        SlotCollectTool.Response response = tool.apply(new SlotCollectTool.Request(), toolContext(state));

        assertEquals("COMPLETE", response.status);
        assertEquals("2026-03-18", response.collected.get("end_date"));
        assertEquals("123123", response.collected.get("reason"));
    }

    @Test
    void shouldExtractExplicitStartAndEndDateTimeFromTrailingUserMessage() {
        ObjectMapper objectMapper = new ObjectMapper();
        SlotEnricherService enricher = mock(SlotEnricherService.class);
        when(enricher.enrichSlots(anyList(), anyString(), anyString())).thenReturn(new ArrayList<>());

        SlotCollectTool tool = new SlotCollectTool(
                new SlotCollectorService(),
                enricher,
                new ComputedFieldProcessor(List.of(new DatePeriodPresetFunction(), new DateRangeLabelFunction())),
                new SlotSchemaParser(objectMapper),
                objectMapper);

        OverAllState state = stateWithSnapshot(snapshot("gougu_oa.meeting_room_booking", MEETING_SCHEMA, null));
        state.updateState(Map.of(
                AssistantStateKeys.SYSTEM_CODE, "oa",
                AssistantStateKeys.ASSISTANT_UID, "u1",
                AssistantStateKeys.CONVERSATION_PHASE, "COLLECTING",
                AssistantStateKeys.LAST_COLLECT_USER_INPUT, "我要订会议室",
                "input", "我要订会议室",
                "messages", List.of(
                        org.springframework.ai.chat.messages.AssistantMessage.builder()
                                .content("请补充开始时间和结束时间")
                                .build(),
                        new UserMessage("开始时间：2026-03-16 14:00，结束时间：2026-03-16 15:00，主题：项目评审，5个人"))));

        SlotCollectTool.Response response = tool.apply(new SlotCollectTool.Request(), toolContext(state));

        assertEquals("2026-03-16 14:00", response.collected.get("start_date"));
        assertEquals("2026-03-16 15:00", response.collected.get("end_date"));
    }

    @Test
    void shouldUpdateApproverAndMirrorSingleDateWhenEditingConfirmedLeaveForm() {
        ObjectMapper objectMapper = new ObjectMapper();
        SlotEnricherService enricher = mock(SlotEnricherService.class);
        when(enricher.enrichSlots(anyList(), anyString(), anyString())).thenReturn(new ArrayList<>());

        SlotCollectTool tool = new SlotCollectTool(
                new SlotCollectorService(),
                enricher,
                new ComputedFieldProcessor(List.of(new DatePeriodPresetFunction(), new DateRangeLabelFunction())),
                new SlotSchemaParser(objectMapper),
                objectMapper);

        ToolMetaSnapshot snapshot = snapshot("gougu_oa.leave_application", LEAVE_EDIT_SCHEMA, null);
        OverAllState state = stateWithSnapshot(snapshot);
        state.updateState(Map.of(
                AssistantStateKeys.SYSTEM_CODE, "oa",
                AssistantStateKeys.ASSISTANT_UID, "u1",
                AssistantStateKeys.CONVERSATION_PHASE, "CONFIRMING",
                AssistantStateKeys.COLLECT_ROUND, 1,
                AssistantStateKeys.COLLECTED_SLOTS, Map.of(
                        "types", SlotValue.resolved("types", "2", 2, "2"),
                        "start_date", SlotValue.resolved("start_date", "2026-03-16", "2026-03-16", "2026-03-16"),
                        "end_date", SlotValue.resolved("end_date", "2026-03-16", "2026-03-16", "2026-03-16"),
                        "reason", SlotValue.resolved("reason", "个人事务", "个人事务", "个人事务"),
                        "check_uids", SlotValue.resolved("check_uids", "4", "4", "4")),
                AssistantStateKeys.ENRICHED_SLOTS, new ArrayList<>(List.of(enrichedSlot(
                        "check_uids",
                        "审批人",
                        List.of(
                                new SlotOption("人事领导（推荐）", "4"),
                                new SlotOption("财务领导 - 财务部", "5"))))),
                "input", "把审批人改成财务领导，日期改成后天，原因改成处理家事",
                "current_date", "2026-03-15"));

        SlotCollectTool.Response response = tool.apply(new SlotCollectTool.Request(), toolContext(state));

        assertEquals("COMPLETE", response.status);
        assertEquals("5", response.collected.get("check_uids"));
        assertEquals("2026-03-17", response.collected.get("start_date"));
        assertEquals("2026-03-17", response.collected.get("end_date"));
        assertEquals("处理家事", response.collected.get("reason"));
    }


    @Test
    void shouldInferMeetingDateTimeTitleAndHeadcountFromNaturalLanguage() {
        ObjectMapper objectMapper = new ObjectMapper();
        SlotEnricherService enricher = mock(SlotEnricherService.class);
        when(enricher.enrichSlots(anyList(), anyString(), anyString())).thenReturn(new ArrayList<>());

        SlotCollectTool tool = new SlotCollectTool(
                new SlotCollectorService(),
                enricher,
                new ComputedFieldProcessor(List.of(new DatePeriodPresetFunction(), new DateRangeLabelFunction())),
                new SlotSchemaParser(objectMapper),
                objectMapper);

        OverAllState state = stateWithSnapshot(snapshot("gougu_oa.meeting_room_booking", MEETING_SCHEMA, null));
        state.updateState(Map.of(
                AssistantStateKeys.SYSTEM_CODE, "oa",
                AssistantStateKeys.ASSISTANT_UID, "u1",
                "input", "明天下午两点到三点，主题是项目评审，5个人",
                "current_date", "2026-03-15"));

        SlotCollectTool.Response response = tool.apply(new SlotCollectTool.Request(), toolContext(state));

        assertEquals("COLLECTING", response.phase);
        assertEquals("2026-03-16 14:00", response.collected.get("start_date"));
        assertEquals("2026-03-16 15:00", response.collected.get("end_date"));
        assertEquals("项目评审", response.collected.get("title"));
        assertEquals(5, response.collected.get("num"));
    }

    @Test
    void shouldResolveAliasDrivenLeavePatchFromCurrentTurnInput() {
        ObjectMapper objectMapper = new ObjectMapper();
        SlotEnricherService enricher = mock(SlotEnricherService.class);
        when(enricher.enrichSlots(anyList(), anyString(), anyString())).thenReturn(new ArrayList<>());

        SlotCollectTool tool = new SlotCollectTool(
                new SlotCollectorService(),
                enricher,
                new ComputedFieldProcessor(List.of(new DatePeriodPresetFunction(), new DateRangeLabelFunction())),
                new SlotSchemaParser(objectMapper),
                objectMapper);

        OverAllState state = stateWithSnapshot(snapshot("gougu_oa.leave_alias", ALIAS_DRIVEN_LEAVE_SCHEMA, null));
        state.updateState(Map.of(
                AssistantStateKeys.SYSTEM_CODE, "oa",
                AssistantStateKeys.ASSISTANT_UID, "u1",
                "input", "请假类型：事假，开始日期：2026-03-17，结束日期：2026-03-18，请假原因：123123",
                "current_date", "2026-03-15"));

        SlotCollectTool.Response response = tool.apply(new SlotCollectTool.Request(), toolContext(state));

        assertEquals("COMPLETE", response.status);
        assertEquals("1", response.collected.get("leave_kind"));
        assertEquals("2026-03-17", response.collected.get("leave_from"));
        assertEquals("2026-03-18", response.collected.get("leave_to"));
        assertEquals("123123", response.collected.get("memo"));
    }

    @Test
    void shouldResolveAliasDrivenMeetingPatchFromCurrentTurnInput() {
        ObjectMapper objectMapper = new ObjectMapper();
        SlotEnricherService enricher = mock(SlotEnricherService.class);
        when(enricher.enrichSlots(anyList(), anyString(), anyString())).thenReturn(new ArrayList<>());

        SlotCollectTool tool = new SlotCollectTool(
                new SlotCollectorService(),
                enricher,
                new ComputedFieldProcessor(List.of(new DatePeriodPresetFunction(), new DateRangeLabelFunction())),
                new SlotSchemaParser(objectMapper),
                objectMapper);

        OverAllState state = stateWithSnapshot(snapshot("gougu_oa.meeting_alias", ALIAS_DRIVEN_MEETING_SCHEMA, null));
        state.updateState(Map.of(
                AssistantStateKeys.SYSTEM_CODE, "oa",
                AssistantStateKeys.ASSISTANT_UID, "u1",
                "input", "开始时间：2026-03-16 14:00，结束时间：2026-03-16 15:00，主题：项目评审，人数：5",
                "current_date", "2026-03-15"));

        SlotCollectTool.Response response = tool.apply(new SlotCollectTool.Request(), toolContext(state));

        assertEquals("COMPLETE", response.status);
        assertEquals("2026-03-16 14:00", response.collected.get("from_at"));
        assertEquals("2026-03-16 15:00", response.collected.get("to_at"));
        assertEquals("项目评审", response.collected.get("topic"));
        assertEquals(5, response.collected.get("attendee_count"));
    }

    @Test
    void shouldResolveMeetingOptionMentionsAndMergeAttendees() {
        ObjectMapper objectMapper = new ObjectMapper();
        SlotEnricherService enricher = mock(SlotEnricherService.class);
        when(enricher.enrichSlots(anyList(), anyString(), anyString())).thenReturn(new ArrayList<>());

        SlotCollectTool tool = new SlotCollectTool(
                new SlotCollectorService(),
                enricher,
                new ComputedFieldProcessor(List.of(new DatePeriodPresetFunction(), new DateRangeLabelFunction())),
                new SlotSchemaParser(objectMapper),
                objectMapper);

        OverAllState state = stateWithSnapshot(snapshot("gougu_oa.meeting_room_booking", MEETING_SCHEMA, null));
        state.updateState(Map.of(
                AssistantStateKeys.SYSTEM_CODE, "oa",
                AssistantStateKeys.ASSISTANT_UID, "u1",
                AssistantStateKeys.COLLECTED_SLOTS, Map.of(
                        "join_uids", SlotValue.resolved("join_uids", "1", "1", "1")),
                AssistantStateKeys.ENRICHED_SLOTS, new ArrayList<>(List.of(
                        enrichedSlot("room_id", "会议室", List.of(
                                new SlotOption("一号会议室", "1"),
                                new SlotOption("二号会议室", "2"))),
                        enrichedSlot("requirement", "会议需求", List.of(
                                new SlotOption("电子屏", "13"),
                                new SlotOption("投影背景", "14"))),
                        enrichedSlot("join_uids", "参会人员", List.of(
                                new SlotOption("超级员工 - 董事会", "1"),
                                new SlotOption("张三 - 人事部", "2"),
                                new SlotOption("王五 - 市场部", "7"))),
                        enrichedSlot("check_uids", "审批人", List.of(
                                new SlotOption("张三 - 人事部", "2"),
                                new SlotOption("王五 - 市场部", "7"))))),
                "input", "一号会议室，需要投影背景和电子屏，参会人加上张三和王五"));

        SlotCollectTool.Response response = tool.apply(new SlotCollectTool.Request(), toolContext(state));

        assertEquals(1, response.collected.get("room_id"));
        assertEquals(List.of("13", "14"), stringifyList(response.collected.get("requirement")));
        assertEquals(List.of("1", "2", "7"), stringifyList(response.collected.get("join_uids")));
        assertFalse(response.collected.containsKey("check_uids"));
    }



    @Test
    void shouldIgnoreAmbiguousOptionHallucinationsWhenUpdatingMeetingAttendees() {
        ObjectMapper objectMapper = new ObjectMapper();
        SlotEnricherService enricher = mock(SlotEnricherService.class);
        when(enricher.enrichSlots(anyList(), anyString(), anyString())).thenReturn(new ArrayList<>());

        SlotCollectTool tool = new SlotCollectTool(
                new SlotCollectorService(),
                enricher,
                new ComputedFieldProcessor(List.of(new DatePeriodPresetFunction(), new DateRangeLabelFunction())),
                new SlotSchemaParser(objectMapper),
                objectMapper);

        OverAllState state = stateWithSnapshot(snapshot("gougu_oa.meeting_room_booking", MEETING_SCHEMA, null));
        state.updateState(Map.of(
                AssistantStateKeys.SYSTEM_CODE, "oa",
                AssistantStateKeys.ASSISTANT_UID, "u1",
                AssistantStateKeys.COLLECTED_SLOTS, Map.of(
                        "room_id", SlotValue.resolved("room_id", "1", 1, "1"),
                        "title", SlotValue.resolved("title", "项目评审", "项目评审", "项目评审"),
                        "start_date", SlotValue.resolved("start_date", "2026-03-16 14:00", "2026-03-16 14:00", "2026-03-16 14:00"),
                        "end_date", SlotValue.resolved("end_date", "2026-03-16 15:00", "2026-03-16 15:00", "2026-03-16 15:00"),
                        "num", SlotValue.resolved("num", "5", 5, "5"),
                        "requirement", SlotValue.resolved("requirement", List.of("13", "14"), List.of("13", "14"), "[13, 14]"),
                        "join_uids", SlotValue.resolved("join_uids", "1", "1", "1"),
                        "check_uids", SlotValue.resolved("check_uids", "4", "4", "4")),
                AssistantStateKeys.ENRICHED_SLOTS, new ArrayList<>(List.of(
                        enrichedSlot("requirement", "会议需求", List.of(
                                new SlotOption("电子屏", "13"),
                                new SlotOption("投影背景", "14"))),
                        enrichedSlot("join_uids", "参会人员", List.of(
                                new SlotOption("超级员工 - 董事会", "1"),
                                new SlotOption("张三 - 人事部", "2"),
                                new SlotOption("王五 - 市场部", "7"))),
                        enrichedSlot("check_uids", "审批人", List.of(
                                new SlotOption("人事领导（推荐）", "4"),
                                new SlotOption("张三 - 人事部", "2"),
                                new SlotOption("王五 - 市场部", "7"))))),
                "input", "参会人加上张三和王五"));

        SlotCollectTool.Request request = new SlotCollectTool.Request();
        request.extractedSlots = Map.of(
                "check_uids", "2",
                "requirement", List.of("java.util.ArrayList", List.of("13", "14")));

        SlotCollectTool.Response response = tool.apply(request, toolContext(state));

        assertEquals("4", response.collected.get("check_uids"));
        assertEquals(List.of("13", "14"), stringifyList(response.collected.get("requirement")));
        assertEquals(List.of("1", "2", "7"), stringifyList(response.collected.get("join_uids")));
    }

    private static List<String> stringifyList(Object rawValue) {
        if (!(rawValue instanceof List<?> values)) {
            return List.of();
        }
        List<String> normalized = new ArrayList<>();
        for (Object value : values) {
            normalized.add(String.valueOf(value));
        }
        return normalized;
    }

    private static ToolMetaSnapshot snapshot(String toolCode, String slotSchema, String behaviorConfig) {
        ToolMetaSnapshot snapshot = new ToolMetaSnapshot();
        snapshot.setToolCode(toolCode);
        snapshot.setSlotSchema(slotSchema);
        snapshot.setBehaviorConfig(behaviorConfig);
        snapshot.setSystemCode("oa");
        return snapshot;
    }

    private static EnrichedSlot enrichedSlot(String slotName, String title, List<SlotOption> options) {
        com.alibaba.assistant.agent.slot.model.SlotDefinition definition =
                new com.alibaba.assistant.agent.slot.model.SlotDefinition();
        definition.setName(slotName);
        definition.setTitle(title);
        EnrichedSlot enrichedSlot = new EnrichedSlot(definition);
        enrichedSlot.setOptions(options);
        return enrichedSlot;
    }

    private static OverAllState stateWithSnapshot(ToolMetaSnapshot snapshot) {
        OverAllState state = new OverAllState();
        state.updateState(Map.of(AssistantStateKeys.MATCHED_TOOL_META, snapshot));
        return state;
    }

    private static ToolContext toolContext(OverAllState state) {
        return new ToolContext(Map.of(ToolContextConstants.AGENT_STATE_CONTEXT_KEY, state));
    }

    private static final class StubCodeactTool implements CodeactTool {

        private final ToolDefinition toolDefinition;
        private final CodeactToolMetadata metadata;
        private final String response;

        private StubCodeactTool(ToolDefinition toolDefinition, CodeactToolMetadata metadata, String response) {
            this.toolDefinition = toolDefinition;
            this.metadata = metadata;
            this.response = response;
        }

        @Override
        public String call(String toolInput) {
            return response;
        }

        @Override
        public String call(String toolInput, ToolContext toolContext) {
            return response;
        }

        @Override
        public ToolDefinition getToolDefinition() {
            return toolDefinition;
        }

        @Override
        public CodeactToolMetadata getCodeactMetadata() {
            return metadata;
        }
    }
}



