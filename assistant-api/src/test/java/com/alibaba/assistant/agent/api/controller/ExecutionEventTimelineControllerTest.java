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

import com.alibaba.assistant.agent.api.security.AuthenticatedUserContext;
import com.alibaba.assistant.agent.api.security.MigrationControlPlaneAuthorizationService;
import com.alibaba.assistant.agent.controlplane.space.PlatformSpace;
import com.alibaba.assistant.agent.controlplane.space.PlatformSpaceService;
import com.alibaba.assistant.agent.execution.persistence.ExecutionEventTimelineItemView;
import com.alibaba.assistant.agent.execution.persistence.ExecutionEventTimelineService;
import com.alibaba.assistant.agent.execution.persistence.ExecutionEventTimelineView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.security.Principal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class ExecutionEventTimelineControllerTest {

    private MockMvc mockMvc;

    @Mock
    private ExecutionEventTimelineService executionEventTimelineService;

    @Mock
    private PlatformSpaceService platformSpaceService;

    @Mock
    private MigrationControlPlaneAuthorizationService authorizationService;

    @BeforeEach
    void setUp() {
        ExecutionEventTimelineController controller = new ExecutionEventTimelineController(
                executionEventTimelineService,
                platformSpaceService,
                authorizationService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void shouldGetExecutionTimelineWhenAuthorized() throws Exception {
        PlatformSpace space = space(11L, "finance-space", "test");
        when(executionEventTimelineService.findTimeline("RUN-1", "submit_approval", 5))
                .thenReturn(Optional.of(timelineView(11L)));
        when(platformSpaceService.getById(11L)).thenReturn(space);
        when(authorizationService.canViewSpaceCatalog(any(AuthenticatedUserContext.class), eq("finance-space"), eq("test")))
                .thenReturn(true);

        mockMvc.perform(get("/api/controlplane/spaces/finance-space/execution-runs/RUN-1/events")
                        .queryParam("environment", "test")
                        .queryParam("stepId", "submit_approval")
                        .queryParam("limit", "5")
                        .principal(authenticatedPrincipal()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.runId").value("RUN-1"))
                .andExpect(jsonPath("$.data.spaceCode").value("finance-space"))
                .andExpect(jsonPath("$.data.environment").value("test"))
                .andExpect(jsonPath("$.data.events.length()").value(1))
                .andExpect(jsonPath("$.data.events[0].eventId").value("RUN-1:5"))
                .andExpect(jsonPath("$.data.events[0].eventType").value("STEP_WAITING_APPROVAL"))
                .andExpect(jsonPath("$.data.events[0].payloadJson").value("{\"approvalRequestId\":\"RUN-1:submit_approval\"}"));
    }

    @Test
    void shouldReturnNotFoundWhenScopedTimelineTargetsDifferentSpace() throws Exception {
        PlatformSpace space = space(11L, "finance-space", "prod");
        when(executionEventTimelineService.findTimeline("RUN-1", null, null))
                .thenReturn(Optional.of(timelineView(11L)));
        when(platformSpaceService.getById(11L)).thenReturn(space);

        mockMvc.perform(get("/api/controlplane/spaces/hr-space/execution-runs/RUN-1/events")
                        .principal(authenticatedPrincipal()))
                .andExpect(status().isNotFound());

        verify(authorizationService, never()).canViewSpaceCatalog(any(AuthenticatedUserContext.class), any(), any());
    }

    @Test
    void shouldReturnForbiddenWhenTimelineScopeDenied() throws Exception {
        PlatformSpace space = space(11L, "finance-space", "prod");
        when(executionEventTimelineService.findTimeline("RUN-1", null, null))
                .thenReturn(Optional.of(timelineView(11L)));
        when(platformSpaceService.getById(11L)).thenReturn(space);
        when(authorizationService.canViewSpaceCatalog(any(AuthenticatedUserContext.class), eq("finance-space"), eq("prod")))
                .thenReturn(false);

        mockMvc.perform(get("/api/controlplane/spaces/finance-space/execution-runs/RUN-1/events")
                        .principal(authenticatedPrincipal()))
                .andExpect(status().isForbidden());
    }

    @Test
    void shouldReturnNotFoundWhenTimelineMissing() throws Exception {
        when(executionEventTimelineService.findTimeline("RUN-404", null, null)).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/controlplane/spaces/finance-space/execution-runs/RUN-404/events")
                        .principal(authenticatedPrincipal()))
                .andExpect(status().isNotFound());

        verify(platformSpaceService, never()).getById(any());
    }

    private ExecutionEventTimelineView timelineView(Long spaceId) {
        return new ExecutionEventTimelineView(
                "RUN-1",
                "oa.leave.apply",
                "WORKFLOW",
                spaceId,
                List.of(new ExecutionEventTimelineItemView(
                        "RUN-1:5",
                        "RUN-1",
                        "submit_approval",
                        "STEP_WAITING_APPROVAL",
                        "WAITING_APPROVAL",
                        "oa.leave.apply",
                        null,
                        "{\"approvalRequestId\":\"RUN-1:submit_approval\"}",
                        LocalDateTime.of(2026, 3, 11, 13, 0))));
    }

    private PlatformSpace space(Long id, String spaceCode, String environment) {
        PlatformSpace space = new PlatformSpace();
        space.setId(id);
        space.setSpaceCode(spaceCode);
        space.setEnvironment(environment);
        return space;
    }

    private Principal authenticatedPrincipal() {
        return new UsernamePasswordAuthenticationToken(authenticatedUser(), "token-controlplane", List.of());
    }

    private AuthenticatedUserContext authenticatedUser() {
        return new AuthenticatedUserContext(
                "1001",
                1L,
                "gougu_oa",
                "assistant-ui",
                "token-controlplane",
                "admin",
                "管理员",
                List.of("assistant_user", "assistant_controlplane_admin"),
                List.of("assistant:chat", "assistant:controlplane"));
    }
}
