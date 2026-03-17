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
import com.alibaba.assistant.agent.runtime.observability.RoleKpiAggregator;
import com.alibaba.assistant.agent.runtime.observability.RoleScenarioSlaSummary;
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
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class ObservabilityControllerTest {

    private MockMvc mockMvc;

    @Mock
    private RoleKpiAggregator roleKpiAggregator;

    @Mock
    private PlatformSpaceService platformSpaceService;

    @Mock
    private MigrationControlPlaneAuthorizationService authorizationService;

    @BeforeEach
    void setUp() {
        ObservabilityController controller = new ObservabilityController(
                roleKpiAggregator,
                platformSpaceService,
                authorizationService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void shouldQueryRoleScenarioSlaSummary() throws Exception {
        PlatformSpace space = new PlatformSpace();
        space.setId(11L);
        space.setSpaceCode("default");
        space.setEnvironment("prod");
        when(platformSpaceService.findActiveByCode("default", "prod")).thenReturn(Optional.of(space));
        when(authorizationService.canViewSpaceCatalog(any(AuthenticatedUserContext.class), eq("default"), eq("prod")))
                .thenReturn(true);
        when(roleKpiAggregator.summarizeRoleScenario(
                11L,
                "admin-agent",
                "digital-admin",
                "meeting_coordination",
                LocalDateTime.of(2026, 3, 17, 0, 0),
                LocalDateTime.of(2026, 3, 18, 0, 0)))
                .thenReturn(Optional.of(new RoleScenarioSlaSummary(
                        11L,
                        "admin-agent",
                        "digital-admin",
                        "meeting_coordination",
                        12,
                        11,
                        10,
                        0.8333d,
                        4200L,
                        0.2500d,
                        LocalDateTime.of(2026, 3, 17, 18, 0))));

        mockMvc.perform(get("/api/controlplane/spaces/default/observability/role-scenarios/sla-summary")
                        .param("agentAppCode", "admin-agent")
                        .param("rolePackageCode", "digital-admin")
                        .param("scenarioCode", "meeting_coordination")
                        .param("startedAfter", "2026-03-17T00:00:00")
                        .param("startedBefore", "2026-03-18T00:00:00")
                        .principal(authenticatedPrincipal()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.spaceCode").value("default"))
                .andExpect(jsonPath("$.data.agentAppCode").value("admin-agent"))
                .andExpect(jsonPath("$.data.rolePackageCode").value("digital-admin"))
                .andExpect(jsonPath("$.data.scenarioCode").value("meeting_coordination"))
                .andExpect(jsonPath("$.data.totalRuns").value(12))
                .andExpect(jsonPath("$.data.slaMetRuns").value(10))
                .andExpect(jsonPath("$.data.averageDurationMs").value(4200));
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
