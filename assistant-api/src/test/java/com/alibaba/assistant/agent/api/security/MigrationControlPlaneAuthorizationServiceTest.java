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

import com.alibaba.assistant.agent.controlplane.identity.LocalUserGrantService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MigrationControlPlaneAuthorizationServiceTest {

    @Mock
    private LocalUserGrantService localUserGrantService;

    private MigrationControlPlaneAuthorizationService authorizationService;

    @BeforeEach
    void setUp() {
        authorizationService = new MigrationControlPlaneAuthorizationService(localUserGrantService);
    }

    @Test
    void shouldAllowGlobalControlplaneAdminGrant() {
        when(localUserGrantService.hasGrant(eq(1001L), eq("permission"), eq("assistant:controlplane")))
                .thenReturn(true);

        boolean allowed = authorizationService.canManageAgentAppPublicationPolicy(
                authenticatedUser("1001"),
                "enterprise-default",
                "prod",
                "finance-agent");

        assertTrue(allowed);
    }

    @Test
    void shouldAllowSpaceScopedControlplaneAdminGrant() {
        when(localUserGrantService.hasGrant(eq(1001L), eq("permission"), eq("assistant:controlplane")))
                .thenReturn(false);
        when(localUserGrantService.hasGrant(eq(1001L), eq("role"), eq("assistant_controlplane_admin")))
                .thenReturn(false);
        when(localUserGrantService.hasGrant(eq(1001L), eq("permission"), eq("assistant:controlplane"), eq("space"), eq("enterprise-default")))
                .thenReturn(true);

        boolean allowed = authorizationService.canManageAgentAppPublicationPolicy(
                authenticatedUser("1001"),
                "enterprise-default",
                "prod",
                "finance-agent");

        assertTrue(allowed);
    }

    @Test
    void shouldAllowAgentAppScopedAdminRole() {
        when(localUserGrantService.hasGrant(eq(1001L), eq("permission"), eq("assistant:controlplane")))
                .thenReturn(false);
        when(localUserGrantService.hasGrant(eq(1001L), eq("role"), eq("assistant_controlplane_admin")))
                .thenReturn(false);
        when(localUserGrantService.hasGrant(eq(1001L), eq("permission"), eq("assistant:controlplane"), eq("space"), eq("enterprise-default")))
                .thenReturn(false);
        when(localUserGrantService.hasGrant(eq(1001L), eq("role"), eq("assistant_space_admin"), eq("space"), eq("enterprise-default")))
                .thenReturn(false);
        when(localUserGrantService.hasGrant(
                eq(1001L),
                eq("permission"),
                eq("assistant:controlplane"),
                eq("agent_app"),
                eq("enterprise-default/prod/finance-agent")))
                .thenReturn(false);
        when(localUserGrantService.hasGrant(
                eq(1001L),
                eq("role"),
                eq("assistant_agent_app_admin"),
                eq("agent_app"),
                eq("enterprise-default/prod/finance-agent")))
                .thenReturn(true);

        boolean allowed = authorizationService.canManageAgentAppPublicationPolicy(
                authenticatedUser("1001"),
                "enterprise-default",
                "prod",
                "finance-agent");

        assertTrue(allowed);
    }

    @Test
    void shouldDenyWhenNoMatchingGrantScopeExists() {
        when(localUserGrantService.hasGrant(eq(1001L), eq("permission"), eq("assistant:controlplane")))
                .thenReturn(false);
        when(localUserGrantService.hasGrant(eq(1001L), eq("role"), eq("assistant_controlplane_admin")))
                .thenReturn(false);
        when(localUserGrantService.hasGrant(eq(1001L), eq("permission"), eq("assistant:controlplane"), eq("space"), eq("enterprise-default")))
                .thenReturn(false);
        when(localUserGrantService.hasGrant(eq(1001L), eq("role"), eq("assistant_space_admin"), eq("space"), eq("enterprise-default")))
                .thenReturn(false);
        when(localUserGrantService.hasGrant(
                eq(1001L),
                eq("permission"),
                eq("assistant:controlplane"),
                eq("agent_app"),
                eq("enterprise-default/prod/finance-agent")))
                .thenReturn(false);
        when(localUserGrantService.hasGrant(
                eq(1001L),
                eq("role"),
                eq("assistant_agent_app_admin"),
                eq("agent_app"),
                eq("enterprise-default/prod/finance-agent")))
                .thenReturn(false);

        boolean allowed = authorizationService.canManageAgentAppPublicationPolicy(
                authenticatedUser("1001"),
                "enterprise-default",
                "prod",
                "finance-agent");

        assertFalse(allowed);
    }


    @Test
    void shouldAllowSpaceAdminToManageLocalUserControlPlaneAccessPolicy() {
        when(localUserGrantService.hasGrant(eq(1001L), eq("permission"), eq("assistant:controlplane")))
                .thenReturn(false);
        when(localUserGrantService.hasGrant(eq(1001L), eq("role"), eq("assistant_controlplane_admin")))
                .thenReturn(false);
        when(localUserGrantService.hasGrant(eq(1001L), eq("permission"), eq("assistant:controlplane"), eq("space"), eq("enterprise-default")))
                .thenReturn(false);
        when(localUserGrantService.hasGrant(eq(1001L), eq("role"), eq("assistant_space_admin"), eq("space"), eq("enterprise-default")))
                .thenReturn(true);

        boolean allowed = authorizationService.canManageLocalUserControlPlaneAccessPolicy(
                authenticatedUser("1001"),
                "enterprise-default",
                "prod");

        assertTrue(allowed);
    }

    @Test
    void shouldDenyAgentAppAdminWhenManagingLocalUserControlPlaneAccessPolicy() {
        when(localUserGrantService.hasGrant(eq(1001L), eq("permission"), eq("assistant:controlplane")))
                .thenReturn(false);
        when(localUserGrantService.hasGrant(eq(1001L), eq("role"), eq("assistant_controlplane_admin")))
                .thenReturn(false);
        when(localUserGrantService.hasGrant(eq(1001L), eq("permission"), eq("assistant:controlplane"), eq("space"), eq("enterprise-default")))
                .thenReturn(false);
        when(localUserGrantService.hasGrant(eq(1001L), eq("role"), eq("assistant_space_admin"), eq("space"), eq("enterprise-default")))
                .thenReturn(false);

        boolean allowed = authorizationService.canManageLocalUserControlPlaneAccessPolicy(
                authenticatedUser("1001"),
                "enterprise-default",
                "prod");

        assertFalse(allowed);
    }
    private AuthenticatedUserContext authenticatedUser(String userId) {
        return new AuthenticatedUserContext(
                userId,
                1L,
                "gougu_oa",
                "assistant-ui",
                "token-controlplane",
                "admin",
                "管理员",
                List.of("assistant_user"),
                List.of("assistant:chat", "assistant:controlplane"));
    }
}


