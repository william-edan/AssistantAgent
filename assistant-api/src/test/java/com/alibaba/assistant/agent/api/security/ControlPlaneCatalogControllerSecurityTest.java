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
package com.alibaba.assistant.agent.api.security;

import com.alibaba.assistant.agent.api.controlplane.ControlPlaneExecutionOverview;
import com.alibaba.assistant.agent.api.controlplane.ControlPlaneExecutionOverviewService;
import com.alibaba.assistant.agent.api.controller.ControlPlaneCatalogController;
import com.alibaba.assistant.agent.controlplane.catalog.ControlPlaneCatalogService;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = ControlPlaneCatalogControllerSecurityTest.TestConfig.class)
@WebAppConfiguration
@ActiveProfiles("migration")
class ControlPlaneCatalogControllerSecurityTest {

    private static final String OVERVIEW_PATH = "/api/controlplane/spaces/finance-space/execution-overview";

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    @Qualifier("springSecurityFilterChain")
    private Filter springSecurityFilterChain;

    @Autowired
    private ControlPlaneExecutionOverviewService controlPlaneExecutionOverviewService;

    @Autowired
    private MigrationAuthService migrationAuthService;

    @Autowired
    private MigrationControlPlaneAuthorizationService authorizationService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        reset(controlPlaneExecutionOverviewService, migrationAuthService, authorizationService);
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .addFilters(springSecurityFilterChain)
                .build();
    }

    @Test
    void shouldReturnUnauthorizedWhenExecutionOverviewBearerMissing() throws Exception {
        mockMvc.perform(get(OVERVIEW_PATH))
                .andExpect(status().isUnauthorized());

        verify(migrationAuthService, never()).introspect(anyString());
        verify(authorizationService, never()).canViewSpaceCatalog(any(), anyString(), anyString());
        verify(controlPlaneExecutionOverviewService, never()).getOverview(anyString(), anyString(), any(), any(), anyBoolean());
    }

    @Test
    void shouldReturnForbiddenWhenExecutionOverviewUserLacksControlplanePermission() throws Exception {
        when(migrationAuthService.introspect("token-chat-only"))
                .thenReturn(Optional.of(new AuthenticatedUserContext(
                        "1001",
                        1L,
                        "gougu_oa",
                        "assistant-ui",
                        "token-chat-only",
                        "admin",
                        "管理员",
                        List.of("assistant_user"),
                        List.of("assistant:chat"))));

        mockMvc.perform(get(OVERVIEW_PATH).header(AUTHORIZATION, "Bearer token-chat-only"))
                .andExpect(status().isForbidden());

        verify(authorizationService, never()).canViewSpaceCatalog(any(), anyString(), anyString());
        verify(controlPlaneExecutionOverviewService, never()).getOverview(anyString(), anyString(), any(), any(), anyBoolean());
    }

    @Test
    void shouldAllowExecutionOverviewWhenScopedAdminHasCatalogAccess() throws Exception {
        when(migrationAuthService.introspect("token-space-admin")).thenReturn(Optional.of(controlPlaneUser()));
        when(authorizationService.canViewSpaceCatalog(any(AuthenticatedUserContext.class), eq("finance-space"), eq("prod")))
                .thenReturn(true);
        when(authorizationService.canManageSpaceExecutionApprovals(any(AuthenticatedUserContext.class), eq("finance-space"), eq("prod")))
                .thenReturn(false);
        when(controlPlaneExecutionOverviewService.getOverview("finance-space", "prod", null, null, false))
                .thenReturn(Optional.of(new ControlPlaneExecutionOverview(
                        "finance-space",
                        "prod",
                        new ControlPlaneExecutionOverview.Summary(0, 0, false),
                        List.of(),
                        List.of())));

        mockMvc.perform(get(OVERVIEW_PATH).header(AUTHORIZATION, "Bearer token-space-admin"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.summary.approvalAccess").value(false));
    }

    private AuthenticatedUserContext controlPlaneUser() {
        return new AuthenticatedUserContext(
                "1001",
                1L,
                "gougu_oa",
                "assistant-ui",
                "token-space-admin",
                "admin",
                "管理员",
                List.of("assistant_user", "assistant_space_admin"),
                List.of("assistant:chat"));
    }

    @Configuration
    @EnableWebMvc
    @EnableWebSecurity
    @Import(SecurityConfig.class)
    static class TestConfig {

        @Bean
        ControlPlaneCatalogService controlPlaneCatalogService() {
            return mock(ControlPlaneCatalogService.class);
        }

        @Bean
        ControlPlaneExecutionOverviewService controlPlaneExecutionOverviewService() {
            return mock(ControlPlaneExecutionOverviewService.class);
        }

        @Bean
        MigrationAuthService migrationAuthService() {
            return mock(MigrationAuthService.class);
        }

        @Bean
        MigrationControlPlaneAuthorizationService migrationControlPlaneAuthorizationService() {
            return mock(MigrationControlPlaneAuthorizationService.class);
        }

        @Bean
        ControlPlaneCatalogController controlPlaneCatalogController(
                ControlPlaneCatalogService controlPlaneCatalogService,
                ControlPlaneExecutionOverviewService controlPlaneExecutionOverviewService,
                MigrationControlPlaneAuthorizationService authorizationService) {
            return new ControlPlaneCatalogController(
                    controlPlaneCatalogService,
                    controlPlaneExecutionOverviewService,
                    authorizationService);
        }

        @Bean
        TokenIntrospectionAuthenticationFilter tokenIntrospectionAuthenticationFilter(MigrationAuthService migrationAuthService) {
            return new TokenIntrospectionAuthenticationFilter(migrationAuthService);
        }
    }
}
