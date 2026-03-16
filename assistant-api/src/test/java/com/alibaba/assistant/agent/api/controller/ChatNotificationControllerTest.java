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
import com.alibaba.assistant.agent.api.controller.dto.ChatNotificationListData;
import com.alibaba.assistant.agent.api.controller.dto.ChatNotificationReadData;
import com.alibaba.assistant.agent.api.security.AuthenticatedUserContext;
import com.alibaba.assistant.agent.api.service.ChatNotificationService;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class ChatNotificationControllerTest {

    private MockMvc mockMvc;

    @Mock
    private ChatNotificationService chatNotificationService;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new ChatNotificationController(chatNotificationService)).build();
    }

    @Test
    void shouldListNotificationsForCurrentUser() throws Exception {
        when(chatNotificationService.listNotifications("1001", "UNREAD", 10)).thenReturn(new ChatNotificationListData(List.of(
                new ChatNotificationData(
                        "N-1",
                        "TASK-1",
                        "UNREAD",
                        "任务已完成",
                        "点击查看数据分析结果",
                        "2026-03-13T10:05:00Z",
                        java.util.Map.of("type", "TASK_DETAIL", "targetId", "TASK-1"))
        )));

        mockMvc.perform(get("/api/chat/notifications")
                        .param("status", "UNREAD")
                        .param("limit", "10")
                        .principal(authenticatedPrincipal("1001")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.notifications[0].notificationId").value("N-1"))
                .andExpect(jsonPath("$.data.notifications[0].action.targetId").value("TASK-1"));
    }

    @Test
    void shouldMarkNotificationAsReadForCurrentUser() throws Exception {
        when(chatNotificationService.markRead("1001", "N-1")).thenReturn(new ChatNotificationReadData(
                "N-1",
                "TASK-1",
                "READ",
                "2026-03-13T10:06:00Z"));

        mockMvc.perform(post("/api/chat/notifications/N-1/read").principal(authenticatedPrincipal("1001")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.notificationId").value("N-1"))
                .andExpect(jsonPath("$.data.status").value("READ"));
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
