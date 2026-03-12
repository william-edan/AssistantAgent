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

import com.alibaba.assistant.agent.api.controller.LocalUserManagementController;
import com.alibaba.assistant.agent.controlplane.identity.LocalUserManagementService;
import com.alibaba.assistant.agent.controlplane.identity.ResolvedLocalUserManagementView;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = LocalUserManagementSecurityIntegrationTest.TestConfig.class)
@WebAppConfiguration
@ActiveProfiles("migration")
class LocalUserManagementSecurityIntegrationTest {

    private static final String PATH = "/api/controlplane/spaces/enterprise-default/local-users/admin?systemCode=gougu_oa";

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    @Qualifier("springSecurityFilterChain")
    private Filter springSecurityFilterChain;

    @Autowired
    private LocalUserManagementService localUserManagementService;

    @Autowired
    private MigrationAuthService migrationAuthService;

    @Autowired
    private MigrationControlPlaneAuthorizationService authorizationService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        Mockito.reset(localUserManagementService, migrationAuthService, authorizationService);
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .addFilters(springSecurityFilterChain)
                .build();
    }

    @Test
    void shouldReturnUnauthorizedWhenTokenMissing() throws Exception {
        mockMvc.perform(get(PATH))
                .andExpect(status().isUnauthorized());

        verify(migrationAuthService, never()).introspect(anyString());
        verify(authorizationService, never()).canManageLocalUserControlPlaneAccessPolicy(any(), anyString(), anyString());
        verify(localUserManagementService, never()).getLocalUser(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void shouldReturnForbiddenWhenCoarseControlplaneAuthorityMissing() throws Exception {
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

        mockMvc.perform(get(PATH).header(AUTHORIZATION, "Bearer token-chat-only"))
                .andExpect(status().isForbidden());

        verify(authorizationService, never()).canManageLocalUserControlPlaneAccessPolicy(any(), anyString(), anyString());
        verify(localUserManagementService, never()).getLocalUser(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void shouldReturnForbiddenWhenScopedSpaceAdminLacksManageGrant() throws Exception {
        AuthenticatedUserContext user = spaceAdminUser();
        when(migrationAuthService.introspect("token-space-admin")).thenReturn(Optional.of(user));
        when(authorizationService.canManageLocalUserControlPlaneAccessPolicy(any(AuthenticatedUserContext.class), eq("enterprise-default"), eq("prod")))
                .thenReturn(false);

        mockMvc.perform(get(PATH).header(AUTHORIZATION, "Bearer token-space-admin"))
                .andExpect(status().isForbidden());

        verify(localUserManagementService, never()).getLocalUser(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void shouldAllowScopedSpaceAdminWhenManageGrantMatchesTargetSpace() throws Exception {
        AuthenticatedUserContext user = spaceAdminUser();
        when(migrationAuthService.introspect("token-space-admin")).thenReturn(Optional.of(user));
        when(authorizationService.canManageLocalUserControlPlaneAccessPolicy(any(AuthenticatedUserContext.class), eq("enterprise-default"), eq("prod")))
                .thenReturn(true);
        when(localUserManagementService.getLocalUser("enterprise-default", "prod", "admin", "gougu_oa"))
                .thenReturn(Optional.of(new ResolvedLocalUserManagementView(
                        1001L,
                        "enterprise-default",
                        "prod",
                        "admin",
                        "管理员",
                        1L,
                        "gougu_oa",
                        "active")));

        mockMvc.perform(get(PATH).header(AUTHORIZATION, "Bearer token-space-admin"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.localUserId").value(1001))
                .andExpect(jsonPath("$.data.status").value("ACTIVE"));
    }

    private AuthenticatedUserContext spaceAdminUser() {
        return new AuthenticatedUserContext(
                "1001",
                1L,
                "gougu_oa",
                "assistant-ui",
                "token-space-admin",
                "admin",
                "管理员",
                List.of("assistant_space_admin"),
                List.of("assistant:chat"));
    }

    @Configuration
    @EnableWebMvc
    @EnableWebSecurity
    @Import(SecurityConfig.class)
    static class TestConfig {

        @Bean
        LocalUserManagementService localUserManagementService() {
            return mock(LocalUserManagementService.class);
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
        LocalUserManagementController localUserManagementController(
                LocalUserManagementService localUserManagementService,
                MigrationControlPlaneAuthorizationService authorizationService) {
            return new LocalUserManagementController(localUserManagementService, authorizationService);
        }

        @Bean
        TokenIntrospectionAuthenticationFilter tokenIntrospectionAuthenticationFilter(
                MigrationAuthService migrationAuthService) {
            return new TokenIntrospectionAuthenticationFilter(migrationAuthService);
        }
    }
}
