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

import com.alibaba.assistant.agent.api.controller.ExecutionRunListController;
import com.alibaba.assistant.agent.controlplane.space.PlatformSpace;
import com.alibaba.assistant.agent.controlplane.space.PlatformSpaceService;
import com.alibaba.assistant.agent.controlplane.space.mapper.PlatformSpaceMapper;
import com.alibaba.assistant.agent.execution.persistence.ExecutionHistoryService;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;
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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = ExecutionRunListControllerSecurityTest.TestConfig.class)
@WebAppConfiguration
@ActiveProfiles("migration")
class ExecutionRunListControllerSecurityTest {

    private static final String LIST_PATH = "/api/controlplane/spaces/finance-space/execution-runs";

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    @Qualifier("springSecurityFilterChain")
    private Filter springSecurityFilterChain;

    @Autowired
    private ExecutionHistoryService executionHistoryService;

    @Autowired
    private PlatformSpaceService platformSpaceService;

    @Autowired
    private MigrationAuthService migrationAuthService;

    @Autowired
    private MigrationControlPlaneAuthorizationService authorizationService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        Mockito.reset(executionHistoryService, platformSpaceService, migrationAuthService, authorizationService);
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .addFilters(springSecurityFilterChain)
                .build();
    }

    @Test
    void shouldReturnUnauthorizedWhenExecutionRunListBearerMissing() throws Exception {
        mockMvc.perform(get(LIST_PATH))
                .andExpect(status().isUnauthorized());

        verify(migrationAuthService, never()).introspect(anyString());
        verify(authorizationService, never()).canViewSpaceCatalog(any(), anyString(), anyString());
        verify(executionHistoryService, never()).listRuns(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void shouldReturnForbiddenWhenExecutionRunListUserLacksControlplanePermission() throws Exception {
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

        mockMvc.perform(get(LIST_PATH).header(AUTHORIZATION, "Bearer token-chat-only"))
                .andExpect(status().isForbidden());

        verify(authorizationService, never()).canViewSpaceCatalog(any(), anyString(), anyString());
    }

    @Test
    void shouldAllowExecutionRunListWhenScopedAdminHasCatalogAccess() throws Exception {
        PlatformSpace space = new PlatformSpace();
        space.setId(11L);
        space.setSpaceCode("finance-space");
        space.setEnvironment("prod");
        when(migrationAuthService.introspect("token-space-admin")).thenReturn(Optional.of(controlPlaneUser()));
        when(platformSpaceService.findActiveByCode("finance-space", "prod")).thenReturn(Optional.of(space));
        when(authorizationService.canViewSpaceCatalog(any(AuthenticatedUserContext.class), eq("finance-space"), eq("prod")))
                .thenReturn(true);
        when(executionHistoryService.listRuns(11L, null, null, null, null, null, 20)).thenReturn(List.of());

        mockMvc.perform(get(LIST_PATH).header(AUTHORIZATION, "Bearer token-space-admin"))
                .andExpect(status().isOk());
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
        ExecutionHistoryService executionHistoryService() {
            return mock(ExecutionHistoryService.class);
        }

        @Bean
        PlatformSpaceService platformSpaceService() {
            return mock(PlatformSpaceService.class);
        }

        @Bean
        PlatformSpaceMapper platformSpaceMapper() {
            return mock(PlatformSpaceMapper.class);
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
        ExecutionRunListController executionRunListController(
                ExecutionHistoryService executionHistoryService,
                PlatformSpaceService platformSpaceService,
                MigrationControlPlaneAuthorizationService authorizationService) {
            return new ExecutionRunListController(executionHistoryService, platformSpaceService, authorizationService);
        }

        @Bean
        TokenIntrospectionAuthenticationFilter tokenIntrospectionAuthenticationFilter(
                MigrationAuthService migrationAuthService) {
            return new TokenIntrospectionAuthenticationFilter(migrationAuthService);
        }
    }
}
