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

import com.alibaba.assistant.agent.api.protocol.FrontendEvent;
import com.alibaba.assistant.agent.api.protocol.FrontendEventType;
import com.alibaba.assistant.agent.api.protocol.FrontendStage;
import com.alibaba.assistant.agent.api.service.ChatAsyncTaskUpdateCommand;
import com.alibaba.assistant.agent.api.service.ChatAsyncTaskUpdateService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class SystemInternalTaskEventControllerTest {

    private MockMvc mockMvc;

    @Mock
    private ChatAsyncTaskUpdateService chatAsyncTaskUpdateService;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new SystemInternalTaskEventController(
                chatAsyncTaskUpdateService,
                "callback-token-1")).build();
    }

    @Test
    void shouldRejectRequestWhenCallbackTokenMissing() throws Exception {
        mockMvc.perform(post("/system/internal/chat/tasks/events")
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void shouldAcceptAuthorizedTaskUpdateAndReturnPublishedEvents() throws Exception {
        when(chatAsyncTaskUpdateService.publishUpdate(any(ChatAsyncTaskUpdateCommand.class))).thenReturn(List.of(
                new FrontendEvent(
                        "2026-03-13",
                        "evt-callback-1",
                        "thread-callback-1",
                        "2026-03-15T11:30:00Z",
                        FrontendEventType.TASK_STATE,
                        FrontendStage.EXECUTING,
                        Map.of("taskId", "TASK-CB-1", "status", "RUNNING"))));

        mockMvc.perform(post("/system/internal/chat/tasks/events")
                        .header("X-Assistant-Callback-Token", "callback-token-1")
                        .contentType("application/json")
                        .content("""
                                {
                                  "threadId":"thread-callback-1",
                                  "assistantUid":"1001",
                                  "appName":"assistant-ui",
                                  "systemCode":"gougu_oa",
                                  "turnId":"turn-callback-1",
                                  "task":{
                                    "taskId":"TASK-CB-1",
                                    "status":"RUNNING",
                                    "title":"数据分析任务"
                                  }
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.events[0].eventType").value("TASK_STATE"))
                .andExpect(jsonPath("$.data.events[0].payload.taskId").value("TASK-CB-1"));
    }
}
