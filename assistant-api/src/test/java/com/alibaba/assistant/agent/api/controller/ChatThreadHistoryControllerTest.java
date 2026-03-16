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

import com.alibaba.assistant.agent.api.controller.dto.ChatMessageData;
import com.alibaba.assistant.agent.api.controller.dto.ChatMessageListData;
import com.alibaba.assistant.agent.api.controller.dto.ChatThreadListData;
import com.alibaba.assistant.agent.api.controller.dto.ChatThreadSummaryData;
import com.alibaba.assistant.agent.api.security.AuthenticatedUserContext;
import com.alibaba.assistant.agent.api.service.ChatConversationHistoryService;
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

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class ChatThreadHistoryControllerTest {

    private MockMvc mockMvc;

    @Mock
    private ChatConversationHistoryService chatConversationHistoryService;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(new ChatThreadHistoryController(chatConversationHistoryService))
                .build();
    }

    @Test
    void shouldListChatThreadsForCurrentUser() throws Exception {
        when(chatConversationHistoryService.listThreads("1001", 20)).thenReturn(new ChatThreadListData(List.of(
                new ChatThreadSummaryData(
                        "thread-confirm",
                        "请假申请",
                        "WAITING_CONFIRMATION",
                        "CONFIRMING",
                        true,
                        true,
                        "gougu_oa.leave_application",
                        "FORM_CARD",
                        0,
                        0,
                        "请确认审批人与请假时间",
                        "FORM_STATE",
                        "2026-03-13T12:00:00Z",
                        Map.of(
                                "actionType", "RUN_SSE",
                                "endpoint", "/api/chat/run_sse",
                                "suggestedMessage", "确认")),
                new ChatThreadSummaryData(
                        "thread-result",
                        "数据分析",
                        "COMPLETED",
                        "DONE",
                        false,
                        false,
                        "data.agent.report",
                        "RESULT_CARD",
                        1,
                        1,
                        "报告已生成",
                        "RESULT",
                        "2026-03-13T12:10:00Z",
                        Map.of())
        )));

        mockMvc.perform(get("/api/chat/threads")
                        .param("limit", "20")
                        .principal(authenticatedPrincipal("1001")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.threads[0].threadId").value("thread-confirm"))
                .andExpect(jsonPath("$.data.threads[0].status").value("WAITING_CONFIRMATION"))
                .andExpect(jsonPath("$.data.threads[0].unfinished").value(true))
                .andExpect(jsonPath("$.data.threads[0].canResume").value(true))
                .andExpect(jsonPath("$.data.threads[0].toolCode").value("gougu_oa.leave_application"))
                .andExpect(jsonPath("$.data.threads[0].pendingCardType").value("FORM_CARD"))
                .andExpect(jsonPath("$.data.threads[0].nextAction.endpoint").value("/api/chat/run_sse"))
                .andExpect(jsonPath("$.data.threads[1].phase").value("DONE"));
    }

    @Test
    void shouldListPersistedMessagesForCurrentUser() throws Exception {
        when(chatConversationHistoryService.listMessages("1001", "thread-confirm", 100))
                .thenReturn(new ChatMessageListData(List.of(
                        new ChatMessageData(
                                "msg-user",
                                "thread-confirm",
                                "turn-1",
                                "USER_MESSAGE",
                                "USER_MESSAGE",
                                "UNDERSTANDING",
                                "COMPLETED",
                                "用户消息",
                                "明天请一天事假",
                                false,
                                1,
                                "2026-03-13T11:58:00Z",
                                "2026-03-13T11:58:00Z",
                                Map.of("text", "明天请一天事假"),
                                Map.of("messageType", "user")),
                        new ChatMessageData(
                                "msg-form",
                                "thread-confirm",
                                "turn-1",
                                "FORM_CARD",
                                "FORM_STATE",
                                "CONFIRMING",
                                "WAITING_CONFIRMATION",
                                "请确认请假信息",
                                "审批人",
                                false,
                                2,
                                "2026-03-13T12:00:00Z",
                                "2026-03-13T12:00:05Z",
                                Map.of(
                                        "mode", "CONFIRM",
                                        "toolCode", "gougu_oa.leave_application",
                                        "values", Map.of("types", 1, "check_uids", "4")),
                                Map.of(
                                        "protocolVersion", "2026-03-13",
                                        "eventId", "evt-form")),
                        new ChatMessageData(
                                "msg-task",
                                "thread-confirm",
                                "turn-2",
                                "TASK_CARD",
                                "TASK_STATE",
                                "EXECUTING",
                                "RUNNING",
                                "数据 Agent 分析中",
                                "已完成 2/3 批",
                                true,
                                3,
                                "2026-03-13T12:01:00Z",
                                "2026-03-13T12:03:00Z",
                                Map.of(
                                        "taskId", "TASK-1",
                                        "status", "RUNNING",
                                        "collapsible", true,
                                        "liveOutput", List.of(Map.of("text", "已完成 2/3 批"))),
                                Map.of(
                                        "protocolVersion", "2026-03-13",
                                        "eventId", "evt-task"))
                )));

        mockMvc.perform(get("/api/chat/threads/thread-confirm/messages")
                        .param("limit", "100")
                        .principal(authenticatedPrincipal("1001")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.messages[0].messageType").value("USER_MESSAGE"))
                .andExpect(jsonPath("$.data.messages[0].payload.text").value("明天请一天事假"))
                .andExpect(jsonPath("$.data.messages[1].status").value("WAITING_CONFIRMATION"))
                .andExpect(jsonPath("$.data.messages[1].payload.mode").value("CONFIRM"))
                .andExpect(jsonPath("$.data.messages[1].meta.eventId").value("evt-form"))
                .andExpect(jsonPath("$.data.messages[2].collapsed").value(true))
                .andExpect(jsonPath("$.data.messages[2].payload.taskId").value("TASK-1"));
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

