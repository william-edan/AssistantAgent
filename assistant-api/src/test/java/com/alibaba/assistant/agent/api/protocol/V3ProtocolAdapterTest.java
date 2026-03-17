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
package com.alibaba.assistant.agent.api.protocol;

import com.alibaba.assistant.agent.runtime.agent.AssistantStateKeys;
import com.alibaba.assistant.agent.slot.model.ToolMetaSnapshot;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SuppressWarnings("unchecked")
class V3ProtocolAdapterTest {

    @Test
    void shouldAdaptSlotCollectViaFormStateStrategy() {
        V3ProtocolAdapter adapter = adapterWithStrategies(
                new FormStateProtocolStrategy(new ProtocolPayloadSupport()));
        List<FrontendEvent> events = adapter.adapt(
                "slot_collect",
                """
                        {
                          \"status\":\"COLLECTING\",
                          \"phase\":\"COLLECTING\",
                          \"message\":\"Missing required slots, continue collecting.\",
                          \"round\":1,
                          \"collected\":{\"reason\":\"个人事务\"},
                          \"missing\":[{\"name\":\"types\"}],
                          \"enrichedSlots\":[
                            {
                              \"name\":\"types\",
                              \"definition\":{
                                \"name\":\"types\",
                                \"type\":\"integer\",
                                \"title\":\"请假类型\",
                                \"required\":true,
                                \"uiComponent\":\"select\"
                              },
                              \"options\":[{\"label\":\"事假\",\"value\":\"1\",\"disabled\":false}],
                              \"optionsLoaded\":true
                            }
                          ]
                        }
                        """,
                (Map<String, Object>) null);

        assertEquals(1, events.size());
        assertEquals(FrontendEventType.FORM_STATE, events.get(0).eventType());
        assertEquals(FrontendStage.COLLECTING, events.get(0).stage());
        assertEquals("COLLECT", events.get(0).payload().get("mode"));
    }

    @Test
    void shouldAdaptArtifactExecuteViaExecutionResultStrategy() {
        V3ProtocolAdapter adapter = adapterWithStrategies(
                new ExecutionResultProtocolStrategy(new ProtocolPayloadSupport()));
        List<FrontendEvent> events = adapter.adapt(
                "artifact_execute",
                """
                        {
                          "success":true,
                          "result":{"leave_id":12345},
                          "artifactCode":"oa.leave.apply",
                          "runId":"RUN-1"
                        }
                        """,
                (Map<String, Object>) null);

        assertEquals(2, events.size());
        assertEquals(FrontendEventType.TASK_STATE, events.get(0).eventType());
        assertEquals(FrontendEventType.RESULT, events.get(1).eventType());
    }

    @Test
    void shouldIgnoreUnknownToolWhenNoStrategyMatches() {
        V3ProtocolAdapter adapter = new V3ProtocolAdapter(new ObjectMapper(), List.of());

        List<FrontendEvent> events = adapter.adapt(
                "assistant_intent_analysis",
                "{\"message\":\"用户明确表示“我要写汇报”，意图清晰，匹配可用工具。\"}",
                (Map<String, Object>) null);

        assertTrue(events.isEmpty());
    }

    @Test
    void shouldAdaptSlotCollectToStructuredFormStateEvent() {
        V3ProtocolAdapter adapter = new V3ProtocolAdapter(new ObjectMapper());
        List<FrontendEvent> events = adapter.adapt(
                "slot_collect",
                """
                        {
                          \"status\":\"COLLECTING\",
                          \"phase\":\"COLLECTING\",
                          \"message\":\"Missing required slots, continue collecting.\",
                          \"round\":1,
                          \"collected\":{\"reason\":\"个人事务\"},
                          \"missing\":[{\"name\":\"types\"}],
                          \"enrichedSlots\":[
                            {
                              \"name\":\"types\",
                              \"definition\":{
                                \"name\":\"types\",
                                \"type\":\"integer\",
                                \"title\":\"请假类型\",
                                \"required\":true,
                                \"uiComponent\":\"select\"
                              },
                              \"options\":[{\"label\":\"事假\",\"value\":\"1\",\"disabled\":false}],
                              \"optionsLoaded\":true
                            }
                          ]
                        }
                        """,
                (Map<String, Object>) null);

        assertEquals(1, events.size());
        assertEquals(FrontendEventType.FORM_STATE, events.get(0).eventType());
        assertEquals(FrontendStage.COLLECTING, events.get(0).stage());
        assertNotNull(events.get(0).eventId());
        assertNotNull(events.get(0).timestamp());
        assertEquals("COLLECT", events.get(0).payload().get("mode"));
        assertEquals("WAITING_INPUT", events.get(0).payload().get("status"));
        assertEquals("types", ((List<Map<String, Object>>) events.get(0).payload().get("missingFields")).get(0).get("name"));
        assertEquals("types", ((List<Map<String, Object>>) events.get(0).payload().get("fields")).get(0).get("name"));
        assertEquals("个人事务", ((Map<String, Object>) events.get(0).payload().get("values")).get("reason"));
    }

    @Test
    void shouldNormalizeReadyToConfirmCollectFormAsConfirmationState() {
        V3ProtocolAdapter adapter = new V3ProtocolAdapter(new ObjectMapper());
        List<FrontendEvent> events = adapter.adapt(
                "slot_collect",
                """
                        {
                          \"status\":\"READY_TO_CONFIRM\",
                          \"phase\":\"READY_TO_CONFIRM\",
                          \"message\":\"请确认请假信息后提交。\",
                          \"round\":2,
                          \"collected\":{\"types\":1,\"reason\":\"个人事务\"},
                          \"enrichedSlots\":[]
                        }
                        """,
                (Map<String, Object>) null);

        assertEquals(1, events.size());
        assertEquals(FrontendEventType.FORM_STATE, events.get(0).eventType());
        assertEquals(FrontendStage.CONFIRMING, events.get(0).stage());
        assertEquals("CONFIRM", events.get(0).payload().get("mode"));
        assertEquals("WAITING_CONFIRMATION", events.get(0).payload().get("status"));
        assertEquals("CONFIRMING", events.get(0).payload().get("phase"));
        assertEquals(Boolean.TRUE, events.get(0).payload().get("canSubmit"));
    }

    @Test
    void shouldResolveFormToolCodeFromMatchedToolMetaSnapshot() {
        V3ProtocolAdapter adapter = new V3ProtocolAdapter(new ObjectMapper());
        ToolMetaSnapshot snapshot = new ToolMetaSnapshot();
        snapshot.setToolCode("gougu_oa.leave_application");

        List<FrontendEvent> events = adapter.adapt(
                "slot_collect",
                """
                        {
                          \"status\":\"READY_TO_CONFIRM\",
                          \"phase\":\"READY_TO_CONFIRM\",
                          \"message\":\"请确认请假信息后提交。\",
                          \"round\":2,
                          \"collected\":{\"types\":1,\"reason\":\"个人事务\"},
                          \"enrichedSlots\":[]
                        }
                        """,
                Map.of(AssistantStateKeys.MATCHED_TOOL_META, snapshot));

        assertEquals(1, events.size());
        assertEquals("gougu_oa.leave_application", events.get(0).payload().get("toolCode"));
    }

    @Test
    void shouldAdaptSlotConfirmToStructuredConfirmFormEvent() {
        V3ProtocolAdapter adapter = new V3ProtocolAdapter(new ObjectMapper());
        List<FrontendEvent> events = adapter.adapt(
                "slot_confirm",
                """
                        {
                          \"status\":\"CONFIRMING\",
                          \"phase\":\"CONFIRMING\",
                          \"message\":\"Confirmation form generated.\",
                          \"confirmForm\":{
                            \"toolCode\":\"gougu_oa.leave_application\",
                            \"collected\":{\"types\":1,\"reason\":\"个人事务\"},
                            \"formSummary\":{\"summaryItems\":[{\"label\":\"请假类型\",\"value\":\"事假\"}],\"secondaryItems\":[]},
                            \"enrichedSlots\":[
                              {
                                \"name\":\"types\",
                                \"definition\":{
                                  \"name\":\"types\",
                                  \"type\":\"integer\",
                                  \"title\":\"请假类型\",
                                  \"required\":true,
                                  \"uiComponent\":\"select\"
                                },
                                \"options\":[{\"label\":\"事假\",\"value\":\"1\",\"disabled\":false}],
                                \"optionsLoaded\":true
                              }
                            ]
                          }
                        }
                        """,
                (Map<String, Object>) null);

        assertEquals(1, events.size());
        assertEquals(FrontendEventType.FORM_STATE, events.get(0).eventType());
        assertEquals(FrontendStage.CONFIRMING, events.get(0).stage());
        assertEquals("CONFIRM", events.get(0).payload().get("mode"));
        assertEquals("WAITING_CONFIRMATION", events.get(0).payload().get("status"));
        assertEquals("CONFIRMING", events.get(0).payload().get("phase"));
        assertEquals("gougu_oa.leave_application", events.get(0).payload().get("toolCode"));
        assertEquals(1, ((List<?>) ((Map<String, Object>) events.get(0).payload().get("summary")).get("summaryItems")).size());
        assertEquals("types", ((List<Map<String, Object>>) events.get(0).payload().get("fields")).get(0).get("name"));
    }

    @Test
    void shouldSanitizeNestedCollectionMarkersInConfirmFormValues() {
        V3ProtocolAdapter adapter = new V3ProtocolAdapter(new ObjectMapper());
        List<FrontendEvent> events = adapter.adapt(
                "slot_confirm",
                """
                        {
                          "status":"CONFIRMING",
                          "phase":"CONFIRMING",
                          "message":"Confirmation form generated.",
                          "confirmForm":{
                            "toolCode":"gougu_oa.meeting_room_booking",
                            "collected":{
                              "join_uids":["java.util.ArrayList", ["1", "2", "7"]],
                              "requirement":["java.util.LinkedList", ["java.util.ArrayList", ["13", "14"]]]
                            },
                            "formSummary":{"summaryItems":[],"secondaryItems":[]},
                            "enrichedSlots":[
                              {
                                "name":"join_uids",
                                "definition":{
                                  "name":"join_uids",
                                  "type":"array",
                                  "title":"参会人"
                                },
                                "options":[],
                                "optionsLoaded":true
                              },
                              {
                                "name":"requirement",
                                "definition":{
                                  "name":"requirement",
                                  "type":"array",
                                  "title":"会议需求"
                                },
                                "options":[],
                                "optionsLoaded":true
                              }
                            ]
                          }
                        }
                        """,
                (Map<String, Object>) null);

        assertEquals(1, events.size());
        Map<String, Object> payload = events.get(0).payload();
        Map<String, Object> values = (Map<String, Object>) payload.get("values");
        assertEquals(List.of("1", "2", "7"), values.get("join_uids"));
        assertEquals(List.of("13", "14"), values.get("requirement"));

        List<Map<String, Object>> fields = (List<Map<String, Object>>) payload.get("fields");
        Map<String, Object> joinField = fields.stream()
                .filter(field -> "join_uids".equals(field.get("name")))
                .findFirst()
                .orElseThrow();
        Map<String, Object> requirementField = fields.stream()
                .filter(field -> "requirement".equals(field.get("name")))
                .findFirst()
                .orElseThrow();
        assertEquals(List.of("1", "2", "7"), joinField.get("value"));
        assertEquals(List.of("13", "14"), requirementField.get("value"));
    }

    @Test
    void shouldAdaptArtifactExecuteToResultAndTaskEvents() {
        V3ProtocolAdapter adapter = new V3ProtocolAdapter(new ObjectMapper());
        List<FrontendEvent> events = adapter.adapt(
                "artifact_execute",
                """
                        {
                          "success":true,
                          "result":{"leave_id":12345},
                          "artifactCode":"oa.leave.apply",
                          "runId":"RUN-1",
                          "executionEvents":[
                            {
                              "runId":"RUN-1",
                              "artifactCode":"oa.leave.apply",
                              "artifactType":"WORKFLOW",
                              "stepId":"submit_approval",
                              "sequence":2,
                              "eventType":"STEP_COMPLETED",
                              "lifecycleStatus":"COMPLETED",
                              "occurredAt":"2026-03-13T10:00:01Z",
                              "payload":{"stepName":"提交审批"}
                            }
                          ]
                        }
                        """,
                (Map<String, Object>) null);

        assertEquals(2, events.size());
        FrontendEvent taskEvent = events.stream()
                .filter(event -> event.eventType() == FrontendEventType.TASK_STATE)
                .findFirst()
                .orElseThrow();
        FrontendEvent resultEvent = events.stream()
                .filter(event -> event.eventType() == FrontendEventType.RESULT)
                .findFirst()
                .orElseThrow();

        assertEquals(FrontendStage.EXECUTING, taskEvent.stage());
        assertEquals("RUN-1", taskEvent.payload().get("taskId"));
        assertEquals("ARTIFACT_EXECUTION", taskEvent.payload().get("taskType"));
        assertEquals("RUNNING", taskEvent.payload().get("status"));
        assertEquals(Boolean.TRUE, taskEvent.payload().get("collapsible"));
        assertEquals("oa.leave.apply", taskEvent.payload().get("sourceCode"));

        assertEquals(FrontendStage.DONE, resultEvent.stage());
        assertEquals(Boolean.TRUE, resultEvent.payload().get("success"));
        assertEquals("oa.leave.apply", resultEvent.payload().get("artifactCode"));
    }

    @Test
    void shouldPreserveExecutionTaskMetadataForVisibilityAndInternalRouting() {
        V3ProtocolAdapter adapter = new V3ProtocolAdapter(new ObjectMapper());
        Map<String, Object> taskPayload = adapter.executionTaskPayload(Map.of(
                "runId", "RUN-META-1",
                "artifactCode", "oa.user.query",
                "eventType", "STEP_COMPLETED",
                "lifecycleStatus", "COMPLETED",
                "internal", true,
                "toolType", "QUERY",
                "visibility", "INTERNAL",
                "invocationPolicy", "DEPENDENCY_ONLY",
                "payload", Map.of("stepName", "查询用户")));

        assertEquals(Boolean.TRUE, taskPayload.get("internal"));
        assertEquals("QUERY", taskPayload.get("toolType"));
        assertEquals("INTERNAL", taskPayload.get("visibility"));
        assertEquals("DEPENDENCY_ONLY", taskPayload.get("invocationPolicy"));
    }

    @Test
    void shouldAdaptGenericSubAgentTaskPayloadToTaskEvent() {
        V3ProtocolAdapter adapter = new V3ProtocolAdapter(new ObjectMapper());
        List<FrontendEvent> events = adapter.adapt(
                "data_agent_query",
                """
                        {
                          "taskId":"TASK-200",
                          "taskType":"SUB_AGENT_CALL",
                          "title":"数据分析任务",
                          "status":"IN_PROGRESS",
                          "sourceType":"SUB_AGENT",
                          "sourceCode":"mcp:data-agent",
                          "progressPercent":65,
                          "background":true,
                          "detached":true,
                          "collapsible":true,
                          "liveOutput":[
                            {
                              "eventType":"PROGRESS",
                              "text":"已完成 2/3 批",
                              "occurredAt":"2026-03-13T10:02:00Z"
                            }
                          ],
                          "notification":{
                            "notificationId":"N-200",
                            "status":"UNREAD",
                            "title":"数据分析完成后通知我",
                            "body":"完成后点击查看结果"
                          }
                        }
                        """,
                Map.of("assistant_uid", "1001"));

        assertEquals(1, events.size());
        FrontendEvent taskEvent = events.get(0);
        assertEquals(FrontendEventType.TASK_STATE, taskEvent.eventType());
        assertEquals(FrontendStage.EXECUTING, taskEvent.stage());
        assertEquals("TASK-200", taskEvent.payload().get("taskId"));
        assertEquals("SUB_AGENT_CALL", taskEvent.payload().get("taskType"));
        assertEquals("SUB_AGENT", taskEvent.payload().get("sourceType"));
        assertEquals("RUNNING", taskEvent.payload().get("status"));
        assertEquals(65, taskEvent.payload().get("progressPercent"));
        assertEquals(true, taskEvent.payload().get("background"));
        assertEquals(true, taskEvent.payload().get("detached"));
        assertEquals("已完成 2/3 批 (65%)", taskEvent.payload().get("summaryText"));
        assertEquals(true, ((Map<String, Object>) taskEvent.payload().get("display")).get("collapsedByDefault"));
        assertEquals("TASK_DETAIL", ((Map<String, Object>) taskEvent.payload().get("action")).get("type"));
        assertTrue(taskEvent.payload().containsKey("notification"));
    }

    @Test
    void shouldAdaptReplyToMessageEvent() {
        V3ProtocolAdapter adapter = new V3ProtocolAdapter(new ObjectMapper());
        List<FrontendEvent> events = adapter.adapt(
                "reply",
                "{\"message\":\"请假已提交\"}",
                (Map<String, Object>) null);

        assertEquals(1, events.size());
        assertEquals(FrontendEventType.MESSAGE, events.get(0).eventType());
        assertEquals("请假已提交", events.get(0).payload().get("text"));
        assertFalse(events.get(0).payload().containsKey("rawOutput"));
    }
        @Test
    void shouldSuppressInternalPlanningNarrationFromReplyToolOutput() {
        V3ProtocolAdapter adapter = new V3ProtocolAdapter(new ObjectMapper());
        List<FrontendEvent> events = adapter.adapt(
                "reply",
                "{\"message\":\"用户明确表示‘我要写汇报’，根据执行策略应先调用 slot_collect。\"}",
                (Map<String, Object>) null);

        assertTrue(events.isEmpty());
    }

    @Test
    void shouldIgnoreUnknownToolOutputByDefault() {
        V3ProtocolAdapter adapter = new V3ProtocolAdapter(new ObjectMapper());
        List<FrontendEvent> events = adapter.adapt(
                "assistant_intent_analysis",
                "{\"message\":\"用户明确表示“我要写汇报”，意图清晰，匹配可用工具。\"}",
                (Map<String, Object>) null);

        assertTrue(events.isEmpty());
    }

    @Test
    void shouldProjectPendingThreadStateFromConfirmForm() {
        V3ProtocolAdapter adapter = new V3ProtocolAdapter(new ObjectMapper());
        Map<String, Object> snapshot = adapter.projectThreadState(
                "slot_confirm",
                """
                        {
                          \"status\":\"CONFIRMING\",
                          \"phase\":\"CONFIRMING\",
                          \"message\":\"Confirmation form generated.\",
                          \"confirmForm\":{
                            \"toolCode\":\"gougu_oa.leave_application\",
                            \"collected\":{\"types\":1},
                            \"formSummary\":{\"summaryItems\":[],\"secondaryItems\":[]},
                            \"enrichedSlots\":[]
                          }
                        }
                        """,
                Map.of("assistant_uid", "1001"));

        assertEquals("WAITING_CONFIRMATION", snapshot.get("status"));
        assertEquals(Boolean.TRUE, snapshot.get("unfinished"));
        assertEquals(Boolean.TRUE, snapshot.get("canResume"));
        assertEquals("gougu_oa.leave_application", ((Map<String, Object>) snapshot.get("pendingForm")).get("toolCode"));
        assertTrue(snapshot.containsKey("updatedAt"));
    }

    @Test
    void shouldProjectTaskCardsAndNotificationsIntoThreadState() {
        V3ProtocolAdapter adapter = new V3ProtocolAdapter(new ObjectMapper());
        Map<String, Object> snapshot = adapter.projectThreadState(
                "artifact_execute",
                """
                        {
                          "success":true,
                          "artifactCode":"data.agent.report",
                          "runId":"TASK-100",
                          "result":{"reportId":"R-1","summary":"分析完成"},
                          "executionEvents":[
                            {
                              "runId":"TASK-100",
                              "artifactCode":"data.agent.report",
                              "artifactType":"WORKFLOW",
                              "stepId":"aggregate",
                              "sequence":3,
                              "eventType":"STEP_COMPLETED",
                              "lifecycleStatus":"COMPLETED",
                              "occurredAt":"2026-03-13T10:00:03Z",
                              "payload":{"stepName":"聚合数据"}
                            }
                          ]
                        }
                        """,
                Map.of("assistant_uid", "1001"));

        assertEquals("COMPLETED", snapshot.get("status"));
        assertTrue(snapshot.containsKey("tasks"));
        assertEquals(1, ((List<?>) snapshot.get("tasks")).size());
        Map<String, Object> task = (Map<String, Object>) ((List<?>) snapshot.get("tasks")).get(0);
        assertEquals("TASK-100", task.get("taskId"));
        assertEquals("COMPLETED", task.get("status"));
        assertEquals(Boolean.TRUE, task.get("resultReady"));
        assertTrue(snapshot.containsKey("notifications"));
    }

    @Test
    void shouldProjectGenericTaskStateIntoThreadSnapshot() {
        V3ProtocolAdapter adapter = new V3ProtocolAdapter(new ObjectMapper());
        Map<String, Object> snapshot = adapter.projectThreadState(
                "data_agent_query",
                """
                        {
                          "taskId":"TASK-300",
                          "taskType":"SUB_AGENT_CALL",
                          "title":"报表分析任务",
                          "status":"COMPLETED",
                          "sourceType":"SUB_AGENT",
                          "sourceCode":"mcp:data-agent",
                          "resultReady":true,
                          "resultPreview":{"reportId":"R-300","summary":"分析完成"},
                          "notification":{
                            "notificationId":"N-300",
                            "status":"UNREAD",
                            "title":"报表分析已完成",
                            "body":"点击查看结果"
                          }
                        }
                        """,
                Map.of("assistant_uid", "1001"));

        assertEquals("COMPLETED", snapshot.get("status"));
        assertEquals("DONE", snapshot.get("phase"));
        Map<String, Object> task = (Map<String, Object>) ((List<?>) snapshot.get("tasks")).get(0);
        assertEquals("TASK-300", task.get("taskId"));
        assertEquals(Boolean.TRUE, task.get("resultReady"));
        Map<String, Object> notification = (Map<String, Object>) ((List<?>) snapshot.get("notifications")).get(0);
        assertEquals("N-300", notification.get("notificationId"));
    }

    @Test
    void shouldTreatWaitingApprovalArtifactExecutionAsResumableTaskState() {
        V3ProtocolAdapter adapter = new V3ProtocolAdapter(new ObjectMapper());
        List<FrontendEvent> events = adapter.adapt(
                "artifact_execute",
                """
                        {
                          "success":false,
                          "artifactCode":"oa.expense.submit",
                          "runId":"RUN-WAIT-1",
                          "lifecycleStatus":"WAITING_APPROVAL",
                          "approvalRequestId":"RUN-WAIT-1:submit_approval",
                          "executionEvents":[
                            {
                              "runId":"RUN-WAIT-1",
                              "artifactCode":"oa.expense.submit",
                              "artifactType":"WORKFLOW",
                              "stepId":"submit_approval",
                              "sequence":3,
                              "eventType":"STEP_WAITING_APPROVAL",
                              "lifecycleStatus":"WAITING_APPROVAL",
                              "occurredAt":"2026-03-13T10:00:03Z",
                              "payload":{
                                "stepName":"提交审批",
                                "approvalRequestId":"RUN-WAIT-1:submit_approval"
                              }
                            }
                          ]
                        }
                        """,
                Map.of("assistant_uid", "1001"));

        assertEquals(1, events.size());
        FrontendEvent taskEvent = events.get(0);
        assertEquals(FrontendEventType.TASK_STATE, taskEvent.eventType());
        assertEquals(FrontendStage.WAITING_APPROVAL, taskEvent.stage());
        assertEquals("RUN-WAIT-1", taskEvent.payload().get("taskId"));
        assertEquals("WAITING_APPROVAL", taskEvent.payload().get("status"));
        assertEquals(Boolean.FALSE, taskEvent.payload().get("resultReady"));
    }

    private V3ProtocolAdapter adapterWithStrategies(ProtocolStrategy... strategies) {
        return new V3ProtocolAdapter(new ObjectMapper(), List.of(strategies));
    }
}


