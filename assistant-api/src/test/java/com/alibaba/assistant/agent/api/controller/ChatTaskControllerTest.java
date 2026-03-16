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

import com.alibaba.assistant.agent.api.controller.dto.ChatTaskData;
import com.alibaba.assistant.agent.api.controller.dto.ChatTaskEventItemData;
import com.alibaba.assistant.agent.api.controller.dto.ChatTaskEventListData;
import com.alibaba.assistant.agent.api.controller.dto.ChatTaskListData;
import com.alibaba.assistant.agent.api.controller.dto.ChatTaskListItemData;
import com.alibaba.assistant.agent.api.security.AuthenticatedUserContext;
import com.alibaba.assistant.agent.api.service.ChatTaskService;
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

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class ChatTaskControllerTest {

    private MockMvc mockMvc;

    @Mock
    private ChatTaskService chatTaskService;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new ChatTaskController(chatTaskService)).build();
    }

    @Test
    void shouldListTasksForCurrentUser() throws Exception {
        when(chatTaskService.listTasks("1001", "thread-1", "RUNNING", 10)).thenReturn(new ChatTaskListData(List.of(
                new ChatTaskListItemData(
                        "TASK-1",
                        "thread-1",
                        "ARTIFACT_EXECUTION",
                        "数据分析任务",
                        "RUNNING",
                        "mcp:data-agent",
                        "data.agent.report",
                        45,
                        true,
                        false,
                        "2026-03-13T10:00:00Z",
                        null,
                        "分析到第 3 批数据",
                        "分析到第 3 批数据 (45%)",
                        true,
                        true,
                        java.util.Map.of("showInChat", true, "collapsedByDefault", true))
        )));

        mockMvc.perform(get("/api/chat/tasks")
                        .param("threadId", "thread-1")
                        .param("status", "RUNNING")
                        .param("limit", "10")
                        .principal(authenticatedPrincipal("1001")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.tasks[0].taskId").value("TASK-1"))
                .andExpect(jsonPath("$.data.tasks[0].status").value("RUNNING"))
                .andExpect(jsonPath("$.data.tasks[0].progressPercent").value(45))
                .andExpect(jsonPath("$.data.tasks[0].summaryText").value("分析到第 3 批数据 (45%)"))
                .andExpect(jsonPath("$.data.tasks[0].background").value(true));
    }

    @Test
    void shouldReturnTaskDetailForCurrentUser() throws Exception {
        when(chatTaskService.getTask("1001", "TASK-1")).thenReturn(new ChatTaskData(
                "TASK-1",
                "thread-1",
                "ARTIFACT_EXECUTION",
                "数据分析任务",
                "COMPLETED",
                "mcp:data-agent",
                "data.agent.report",
                100,
                true,
                true,
                "2026-03-13T10:00:00Z",
                "2026-03-13T10:05:00Z",
                "分析完成",
                "分析完成",
                true,
                true,
                java.util.Map.of("showInTaskCenter", true, "showResultPreview", true),
                List.of(),
                java.util.Map.of("reportId", "R-1", "summary", "分析完成"),
                java.util.Map.of("type", "TASK_DETAIL", "targetId", "TASK-1")));

        mockMvc.perform(get("/api/chat/tasks/TASK-1").principal(authenticatedPrincipal("1001")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.taskId").value("TASK-1"))
                .andExpect(jsonPath("$.data.resultReady").value(true))
                .andExpect(jsonPath("$.data.resultPreview.reportId").value("R-1"))
                .andExpect(jsonPath("$.data.summaryText").value("分析完成"))
                .andExpect(jsonPath("$.data.background").value(true));
    }

    @Test
    void shouldReturnTaskTimelineForCurrentUser() throws Exception {
        when(chatTaskService.listTaskEvents("1001", "TASK-1", 20)).thenReturn(new ChatTaskEventListData(List.of(
                new ChatTaskEventItemData(
                        "EVT-1",
                        "TASK-1",
                        "PROGRESS",
                        "RUNNING",
                        1L,
                        "2026-03-13T10:00:00Z",
                        java.util.Map.of("text", "开始拉取数据")),
                new ChatTaskEventItemData(
                        "EVT-2",
                        "TASK-1",
                        "RESULT",
                        "COMPLETED",
                        2L,
                        "2026-03-13T10:05:00Z",
                        java.util.Map.of("text", "分析完成"))
        )));

        mockMvc.perform(get("/api/chat/tasks/TASK-1/events")
                        .param("limit", "20")
                        .principal(authenticatedPrincipal("1001")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.events[0].eventType").value("PROGRESS"))
                .andExpect(jsonPath("$.data.events[1].status").value("COMPLETED"));
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


