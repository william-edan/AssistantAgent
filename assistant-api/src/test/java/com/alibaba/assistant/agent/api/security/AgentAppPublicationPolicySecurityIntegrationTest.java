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

import com.alibaba.assistant.agent.api.controller.AgentAppPublicationPolicyController;
import com.alibaba.assistant.agent.controlplane.agentapp.AgentAppPublicationPolicyService;
import com.alibaba.assistant.agent.controlplane.agentapp.AgentAppPublicationSourcePolicy;
import com.alibaba.assistant.agent.controlplane.agentapp.ResolvedAgentAppPublicationSourcePolicy;
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
@ContextConfiguration(classes = AgentAppPublicationPolicySecurityIntegrationTest.TestConfig.class)
@WebAppConfiguration
@ActiveProfiles("migration")
class AgentAppPublicationPolicySecurityIntegrationTest {

    private static final String PATH = "/api/controlplane/spaces/enterprise-default/agent-apps/finance-agent/publication-source-policy";

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    @Qualifier("springSecurityFilterChain")
    private Filter springSecurityFilterChain;

    @Autowired
    private AgentAppPublicationPolicyService publicationPolicyService;

    @Autowired
    private MigrationAuthService migrationAuthService;

    @Autowired
    private MigrationControlPlaneAuthorizationService authorizationService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        Mockito.reset(publicationPolicyService, migrationAuthService, authorizationService);
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .addFilters(springSecurityFilterChain)
                .build();
    }

    @Test
    void shouldReturnUnauthorizedWhenBearerTokenMissing() throws Exception {
        mockMvc.perform(get(PATH))
                .andExpect(status().isUnauthorized());

        verify(migrationAuthService, never()).introspect(anyString());
        verify(authorizationService, never()).canManageAgentAppPublicationPolicy(any(), anyString(), anyString(), anyString());
        verify(publicationPolicyService, never()).getPublicationSourcePolicy(anyString(), anyString(), anyString());
    }

    @Test
    void shouldReturnForbiddenWhenAuthenticatedUserLacksControlplanePermission() throws Exception {
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

        verify(authorizationService, never()).canManageAgentAppPublicationPolicy(any(), anyString(), anyString(), anyString());
        verify(publicationPolicyService, never()).getPublicationSourcePolicy(anyString(), anyString(), anyString());
    }

    @Test
    void shouldReturnForbiddenWhenScopedControlplaneAdminLacksTargetScope() throws Exception {
        AuthenticatedUserContext user = controlPlaneUser();
        when(migrationAuthService.introspect("token-app-admin")).thenReturn(Optional.of(user));
        when(authorizationService.canManageAgentAppPublicationPolicy(any(AuthenticatedUserContext.class), eq("enterprise-default"), eq("prod"), eq("finance-agent")))
                .thenReturn(false);

        mockMvc.perform(get(PATH).header(AUTHORIZATION, "Bearer token-app-admin"))
                .andExpect(status().isForbidden());

        verify(publicationPolicyService, never()).getPublicationSourcePolicy(anyString(), anyString(), anyString());
    }

    @Test
    void shouldAllowAccessWhenScopedControlplaneAdminHasTargetScope() throws Exception {
        AuthenticatedUserContext user = controlPlaneUser();
        when(migrationAuthService.introspect("token-app-admin")).thenReturn(Optional.of(user));
        when(authorizationService.canManageAgentAppPublicationPolicy(any(AuthenticatedUserContext.class), eq("enterprise-default"), eq("prod"), eq("finance-agent")))
                .thenReturn(true);
        when(publicationPolicyService.getPublicationSourcePolicy(eq("enterprise-default"), eq("prod"), eq("finance-agent")))
                .thenReturn(Optional.of(new ResolvedAgentAppPublicationSourcePolicy(
                        9L,
                        "enterprise-default",
                        "prod",
                        7L,
                        "finance-agent",
                        new AgentAppPublicationSourcePolicy("exclusive", List.of("tool-meta-catalog"), List.of("legacy-bridge")))));

        mockMvc.perform(get(PATH).header(AUTHORIZATION, "Bearer token-app-admin"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.sourceSelectionMode").value("EXCLUSIVE"));
    }

    private AuthenticatedUserContext controlPlaneUser() {
        return new AuthenticatedUserContext(
                "1001",
                1L,
                "gougu_oa",
                "assistant-ui",
                "token-app-admin",
                "admin",
                "管理员",
                List.of("assistant_user", "assistant_agent_app_admin"),
                List.of("assistant:chat"));
    }

    @Configuration
    @EnableWebMvc
    @EnableWebSecurity
    @Import(SecurityConfig.class)
    static class TestConfig {

        @Bean
        AgentAppPublicationPolicyService publicationPolicyService() {
            return mock(AgentAppPublicationPolicyService.class);
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
        AgentAppPublicationPolicyController agentAppPublicationPolicyController(
                AgentAppPublicationPolicyService publicationPolicyService,
                MigrationControlPlaneAuthorizationService authorizationService) {
            return new AgentAppPublicationPolicyController(publicationPolicyService, authorizationService);
        }

        @Bean
        TokenIntrospectionAuthenticationFilter tokenIntrospectionAuthenticationFilter(
                MigrationAuthService migrationAuthService) {
            return new TokenIntrospectionAuthenticationFilter(migrationAuthService);
        }
    }
}



