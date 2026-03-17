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
package com.alibaba.assistant.agent.api.controller;

import com.alibaba.assistant.agent.api.controller.dto.ChatNotificationData;
import com.alibaba.assistant.agent.api.controller.dto.ChatTaskListItemData;
import com.alibaba.assistant.agent.api.security.AuthenticatedUserContext;
import com.alibaba.assistant.agent.api.service.ChatConversationHistoryService;
import com.alibaba.assistant.agent.api.service.ChatNotificationService;
import com.alibaba.assistant.agent.api.service.ChatTaskService;
import com.alibaba.assistant.agent.api.service.ChatThreadStateService;
import com.alibaba.assistant.agent.runtime.agent.AssistantStateKeys;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.checkpoint.BaseCheckpointSaver;
import com.alibaba.cloud.ai.graph.checkpoint.Checkpoint;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.security.Principal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
@SuppressWarnings("unchecked")
class ChatThreadStateControllerTest {

    private MockMvc mockMvc;

    @Mock
    private BaseCheckpointSaver checkpointSaver;

    @Mock
    private ChatTaskService chatTaskService;

    @Mock
    private ChatNotificationService chatNotificationService;

    @Mock
    private ChatConversationHistoryService chatConversationHistoryService;

    @BeforeEach
    void setUp() {
        ChatThreadStateController controller = new ChatThreadStateController(
                new ChatThreadStateService(
                        checkpointSaver,
                        null,
                        chatTaskService,
                        chatNotificationService,
                        chatConversationHistoryService));
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void shouldReturnPersistedPendingThreadState() throws Exception {
        when(checkpointSaver.get(any(RunnableConfig.class))).thenReturn(Optional.of(Checkpoint.builder()
                .id("cp-1")
                .state(Map.of(
                        AssistantStateKeys.ASSISTANT_UID, "1001",
                        AssistantStateKeys.ROLE_PACKAGE_CODE, "digital-admin",
                        AssistantStateKeys.ROLE_PACKAGE_VERSION, "v1",
                        AssistantStateKeys.ROLE_SCENARIO_CODE, "leave-approval",
                        AssistantStateKeys.FRONTEND_THREAD_STATE, Map.of(
                                "status", "WAITING_CONFIRMATION",
                                "phase", "CONFIRMING",
                                "unfinished", true,
                                "canResume", true,
                                "toolCode", "gougu_oa.leave_application",
                                "updatedAt", "2026-03-13T10:00:00Z",
                                "pendingForm", Map.of(
                                        "mode", "CONFIRM",
                                        "toolCode", "gougu_oa.leave_application",
                                        "fields", List.of(),
                                        "values", Map.of("types", 1)),
                                "tasks", List.of(Map.of(
                                        "taskId", "TASK-1",
                                        "status", "RUNNING",
                                        "title", "数据分析中",
                                        "collapsible", true)),
                                "notifications", List.of(Map.of(
                                        "notificationId", "N-1",
                                        "status", "UNREAD",
                                        "title", "任务已开始"))))
                )
                .nodeId("node")
                .nextNodeId("next")
                .build()));
        when(chatTaskService.listThreadTasks("1001", "T-1", 10)).thenReturn(List.of());
        when(chatNotificationService.listThreadNotifications("1001", "T-1", 10)).thenReturn(List.of());
        mockMvc.perform(get("/api/chat/threads/T-1/state").principal(authenticatedPrincipal("1001")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.threadId").value("T-1"))
                .andExpect(jsonPath("$.data.checkpointId").value("cp-1"))
                .andExpect(jsonPath("$.data.status").value("WAITING_CONFIRMATION"))
                .andExpect(jsonPath("$.data.unfinished").value(true))
                .andExpect(jsonPath("$.data.rolePackageCode").value("digital-admin"))
                .andExpect(jsonPath("$.data.rolePackageVersion").value("v1"))
                .andExpect(jsonPath("$.data.roleScenarioCode").value("leave-approval"))
                .andExpect(jsonPath("$.data.pendingForm.mode").value("CONFIRM"))
                .andExpect(jsonPath("$.data.pendingForm.toolCode").value("gougu_oa.leave_application"));
    }

    @Test
    void shouldDeriveCollectingStateFromLegacyCheckpoint() throws Exception {
        when(checkpointSaver.get(any(RunnableConfig.class))).thenReturn(Optional.of(Checkpoint.builder()
                .id("cp-2")
                .state(Map.of(
                        AssistantStateKeys.ASSISTANT_UID, "1001",
                        AssistantStateKeys.ROLE_PACKAGE_CODE, "digital-admin",
                        AssistantStateKeys.ROLE_PACKAGE_VERSION, "v1",
                        AssistantStateKeys.ROLE_SCENARIO_CODE, "leave-approval",
                        AssistantStateKeys.CONVERSATION_PHASE, "COLLECTING",
                        AssistantStateKeys.MATCHED_TOOL_META, Map.of("toolCode", "gougu_oa.leave_application"),
                        AssistantStateKeys.COLLECTED_SLOTS, Map.of("reason", "个人事务"),
                        AssistantStateKeys.ENRICHED_SLOTS, List.of(Map.of(
                                "name", "types",
                                "definition", Map.of(
                                        "name", "types",
                                        "type", "integer",
                                        "title", "请假类型",
                                        "required", true,
                                        "uiComponent", "select"),
                                "options", List.of(Map.of("label", "事假", "value", "1", "disabled", false)),
                                "optionsLoaded", true)),
                        "missing", List.of(Map.of("name", "types"))
                ))
                .nodeId("node")
                .nextNodeId("next")
                .build()));
        when(chatTaskService.listThreadTasks("1001", "T-legacy", 10)).thenReturn(List.of());
        when(chatNotificationService.listThreadNotifications("1001", "T-legacy", 10)).thenReturn(List.of());
        mockMvc.perform(get("/api/chat/threads/T-legacy/state").principal(authenticatedPrincipal("1001")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("WAITING_INPUT"))
                .andExpect(jsonPath("$.data.phase").value("COLLECTING"))
                .andExpect(jsonPath("$.data.unfinished").value(true))
                .andExpect(jsonPath("$.data.pendingForm.mode").value("COLLECT"))
                .andExpect(jsonPath("$.data.pendingForm.toolCode").value("gougu_oa.leave_application"))
                .andExpect(jsonPath("$.data.pendingForm.fields[0].name").value("types"))
                .andExpect(jsonPath("$.data.pendingForm.values.reason").value("个人事务"));
    }

    @Test
    void shouldRecoverWaitingConfirmationStateFromPersistedHistoryWhenCheckpointMissing() throws Exception {
        when(checkpointSaver.get(any(RunnableConfig.class))).thenReturn(Optional.empty());
        when(chatTaskService.listThreadTasks("1001", "T-history", 10)).thenReturn(List.of());
        when(chatNotificationService.listThreadNotifications("1001", "T-history", 10)).thenReturn(List.of());
        when(chatConversationHistoryService.findThreadStateSnapshot("1001", "T-history")).thenReturn(Optional.of(Map.ofEntries(
                Map.entry("status", "WAITING_CONFIRMATION"),
                Map.entry("phase", "CONFIRMING"),
                Map.entry("unfinished", true),
                Map.entry("canResume", true),
                Map.entry("toolCode", "gougu_oa.leave_application"),
                Map.entry("rolePackageCode", "digital-admin"),
                Map.entry("rolePackageVersion", "v1"),
                Map.entry("roleScenarioCode", "leave-approval"),
                Map.entry("updatedAt", "2026-03-13T12:00:05Z"),
                Map.entry("pendingForm", Map.of(
                        "mode", "CONFIRM",
                        "toolCode", "gougu_oa.leave_application",
                        "values", Map.of("types", 1, "check_uids", "4"))),
                Map.entry("lastMessage", "请确认审批人")
        )));

        mockMvc.perform(get("/api/chat/threads/T-history/state").principal(authenticatedPrincipal("1001")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.threadId").value("T-history"))
                .andExpect(jsonPath("$.data.status").value("WAITING_CONFIRMATION"))
                .andExpect(jsonPath("$.data.phase").value("CONFIRMING"))
                .andExpect(jsonPath("$.data.unfinished").value(true))
                .andExpect(jsonPath("$.data.rolePackageCode").value("digital-admin"))
                .andExpect(jsonPath("$.data.rolePackageVersion").value("v1"))
                .andExpect(jsonPath("$.data.roleScenarioCode").value("leave-approval"))
                .andExpect(jsonPath("$.data.pendingForm.mode").value("CONFIRM"))
                .andExpect(jsonPath("$.data.lastMessage").value("请确认审批人"));
    }

    @Test
    void shouldSynthesizeThreadStateFromPersistedTasksWhenCheckpointMissing() throws Exception {
        when(checkpointSaver.get(any(RunnableConfig.class))).thenReturn(Optional.empty());
        when(chatTaskService.listThreadTasks("1001", "T-task", 10)).thenReturn(List.of(
                new ChatTaskListItemData(
                        "TASK-1",
                        "T-task",
                        "SUB_AGENT_CALL",
                        "数据分析任务",
                        "RUNNING",
                        "SUB_AGENT",
                        "mcp:data-agent",
                        65,
                        true,
                        false,
                        "2026-03-13T10:00:00Z",
                        null,
                        "已完成 2/3 批",
                        "已完成 2/3 批 (65%)",
                        true,
                        true,
                        Map.of("showInChat", true, "collapsedByDefault", true))));
        when(chatNotificationService.listThreadNotifications("1001", "T-task", 10)).thenReturn(List.of(
                new ChatNotificationData(
                        "N-1",
                        "TASK-1",
                        "UNREAD",
                        "任务进行中",
                        "点击查看实时进度",
                        "2026-03-13T10:05:00Z",
                        Map.of("type", "TASK_DETAIL", "targetId", "TASK-1"))));
        when(chatConversationHistoryService.findThreadStateSnapshot("1001", "T-task")).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/chat/threads/T-task/state").principal(authenticatedPrincipal("1001")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.threadId").value("T-task"))
                .andExpect(jsonPath("$.data.status").value("RUNNING"))
                .andExpect(jsonPath("$.data.phase").value("EXECUTING"))
                .andExpect(jsonPath("$.data.unfinished").value(true))
                .andExpect(jsonPath("$.data.tasks[0].taskId").value("TASK-1"))
                .andExpect(jsonPath("$.data.tasks[0].sourceType").value("SUB_AGENT"))
                .andExpect(jsonPath("$.data.tasks[0].summaryText").value("已完成 2/3 批 (65%)"))
                .andExpect(jsonPath("$.data.tasks[0].background").value(true))
                .andExpect(jsonPath("$.data.notifications[0].notificationId").value("N-1"));
    }

    @Test
    void shouldExposeWaitingApprovalStateAndResumeActionFromPersistedTasks() throws Exception {
        when(checkpointSaver.get(any(RunnableConfig.class))).thenReturn(Optional.empty());
        when(chatTaskService.listThreadTasks("1001", "T-wait-approval", 10)).thenReturn(List.of(
                new ChatTaskListItemData(
                        "TASK-WAIT-1",
                        "T-wait-approval",
                        "ARTIFACT_EXECUTION",
                        "报销审批提交",
                        "WAITING_APPROVAL",
                        "ARTIFACT_EXECUTION",
                        "oa.expense.submit",
                        95,
                        true,
                        false,
                        "2026-03-13T10:00:00Z",
                        null,
                        "等待上级审批",
                        "等待上级审批",
                        false,
                        false,
                        Map.of("showInChat", true, "showResultPreview", false))));
        when(chatNotificationService.listThreadNotifications("1001", "T-wait-approval", 10)).thenReturn(List.of());
        when(chatConversationHistoryService.findThreadStateSnapshot("1001", "T-wait-approval")).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/chat/threads/T-wait-approval/state").principal(authenticatedPrincipal("1001")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("WAITING_APPROVAL"))
                .andExpect(jsonPath("$.data.phase").value("WAITING_APPROVAL"))
                .andExpect(jsonPath("$.data.canResume").value(true))
                .andExpect(jsonPath("$.data.nextAction.actionType").value("RESUME_SSE"))
                .andExpect(jsonPath("$.data.nextAction.endpoint").value("/api/chat/resume_sse"));
    }

    @Test
    void shouldPreferTerminalHistorySnapshotWhenCheckpointStillShowsWaitingApproval() throws Exception {
        when(checkpointSaver.get(any(RunnableConfig.class))).thenReturn(Optional.of(Checkpoint.builder()
                .id("cp-stale")
                .state(Map.of(
                        AssistantStateKeys.ASSISTANT_UID, "1001",
                        AssistantStateKeys.ROLE_PACKAGE_CODE, "digital-admin",
                        AssistantStateKeys.ROLE_PACKAGE_VERSION, "v1",
                        AssistantStateKeys.ROLE_SCENARIO_CODE, "leave-approval",
                        AssistantStateKeys.FRONTEND_THREAD_STATE, Map.of(
                                "status", "WAITING_APPROVAL",
                                "phase", "WAITING_APPROVAL",
                                "unfinished", true,
                                "canResume", true,
                                "toolCode", "gougu_oa.platform_approval_probe",
                                "pendingCardType", "TASK_CARD")))
                .nodeId("node")
                .nextNodeId("next")
                .build()));
        when(chatTaskService.listThreadTasks("1001", "T-stale", 10)).thenReturn(List.of(
                new ChatTaskListItemData(
                        "TASK-DONE-1",
                        "T-stale",
                        "ARTIFACT_EXECUTION",
                        "平台审批探针",
                        "COMPLETED",
                        "ARTIFACT_EXECUTION",
                        "gougu_oa.platform_approval_probe",
                        100,
                        true,
                        true,
                        "2026-03-15T23:00:00Z",
                        "2026-03-15T23:01:00Z",
                        "审批已通过",
                        "审批已通过，任务已完成",
                        false,
                        false,
                        Map.of("showInChat", true, "showResultPreview", true))));
        when(chatNotificationService.listThreadNotifications("1001", "T-stale", 10)).thenReturn(List.of());
        when(chatConversationHistoryService.findThreadStateSnapshot("1001", "T-stale")).thenReturn(Optional.of(Map.of(
                "status", "COMPLETED",
                "phase", "DONE",
                "unfinished", false,
                "canResume", false,
                "toolCode", "gougu_oa.platform_approval_probe",
                "pendingCardType", "RESULT_CARD",
                "lastMessage", "审批已通过，任务已完成")));

        mockMvc.perform(get("/api/chat/threads/T-stale/state").principal(authenticatedPrincipal("1001")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("COMPLETED"))
                .andExpect(jsonPath("$.data.phase").value("DONE"))
                .andExpect(jsonPath("$.data.canResume").value(false))
                .andExpect(jsonPath("$.data.pendingCardType").value("RESULT_CARD"));
    }

    @Test
    void shouldRejectThreadStateFromAnotherUser() throws Exception {
        when(checkpointSaver.get(any(RunnableConfig.class))).thenReturn(Optional.of(Checkpoint.builder()
                .id("cp-3")
                .state(Map.of(AssistantStateKeys.ASSISTANT_UID, "2002"))
                .nodeId("node")
                .nextNodeId("next")
                .build()));

        mockMvc.perform(get("/api/chat/threads/T-403/state").principal(authenticatedPrincipal("1001")))
                .andExpect(status().isForbidden());
    }

    private Principal authenticatedPrincipal(String userId) {
        return new UsernamePasswordAuthenticationToken(authenticatedUser(userId), "token-chat", List.of());
    }

    private AuthenticatedUserContext authenticatedUser(String userId) {
        return new AuthenticatedUserContext(
                userId,
                1L,
                "gougu_oa",
                "assistant-ui",
                "token-chat",
                "admin",
                "管理员",
                List.of("assistant_user"),
                List.of("assistant:chat"));
    }
}







