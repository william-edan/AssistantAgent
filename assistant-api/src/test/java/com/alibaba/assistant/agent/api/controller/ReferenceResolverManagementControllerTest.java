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
import com.alibaba.assistant.agent.controlplane.query.ReferenceResolverManagementService;
import com.alibaba.assistant.agent.controlplane.query.ReferenceResolverUpsertCommand;
import com.alibaba.assistant.agent.controlplane.query.ResolvedReferenceResolverManagementView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.security.Principal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class ReferenceResolverManagementControllerTest {

    private MockMvc mockMvc;

    @Mock
    private ReferenceResolverManagementService referenceResolverManagementService;

    @Mock
    private MigrationControlPlaneAuthorizationService authorizationService;

    @BeforeEach
    void setUp() {
        ReferenceResolverManagementController controller =
                new ReferenceResolverManagementController(referenceResolverManagementService, authorizationService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void shouldListManagedResolvers() throws Exception {
        when(authorizationService.canManageSpaceCatalog(any(AuthenticatedUserContext.class), eq("enterprise-default"), eq("prod")))
                .thenReturn(true);
        when(referenceResolverManagementService.listResolvers("enterprise-default", "prod", "oa-core"))
                .thenReturn(List.of(new ResolvedReferenceResolverManagementView(
                        31L,
                        "enterprise-default",
                        "prod",
                        "oa-core",
                        "leave.types",
                        Map.of("method", "GET", "endpoint", "/leave/types"),
                        List.of("oa-user", "oa-service"),
                        Map.of("type", "object"),
                        Map.of("type", "array"),
                        Map.of("ttlSeconds", 300),
                        Map.of("mode", "allow_stale"),
                        "internal",
                        "enabled")));

        mockMvc.perform(get("/api/controlplane/spaces/enterprise-default/connectors/oa-core/reference-resolvers/manage")
                        .principal(authenticatedPrincipal()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.resolvers.length()").value(1))
                .andExpect(jsonPath("$.data.resolvers[0].resolverCode").value("leave.types"))
                .andExpect(jsonPath("$.data.resolvers[0].operationBinding.method").value("GET"))
                .andExpect(jsonPath("$.data.resolvers[0].visibility").value("INTERNAL"));
    }

    @Test
    void shouldUpsertManagedResolver() throws Exception {
        when(authorizationService.canManageSpaceCatalog(any(AuthenticatedUserContext.class), eq("enterprise-default"), eq("test")))
                .thenReturn(true);
        when(referenceResolverManagementService.upsertResolver(
                eq("enterprise-default"),
                eq("test"),
                eq("oa-core"),
                eq("leave.types"),
                any(ReferenceResolverUpsertCommand.class)))
                .thenReturn(Optional.of(new ResolvedReferenceResolverManagementView(
                        41L,
                        "enterprise-default",
                        "test",
                        "oa-core",
                        "leave.types",
                        Map.of("method", "GET", "endpoint", "/leave/types"),
                        List.of("oa-user"),
                        Map.of("type", "object"),
                        Map.of("type", "array"),
                        Map.of("ttlSeconds", 120),
                        Map.of("mode", "strict"),
                        "tenant",
                        "enabled")));

        mockMvc.perform(put("/api/controlplane/spaces/enterprise-default/connectors/oa-core/reference-resolvers/leave.types")
                        .param("environment", "test")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "operationBinding": {"method": "GET", "endpoint": "/leave/types"},
                                  "allowedAuthProfiles": ["oa-user"],
                                  "inputSchema": {"type": "object"},
                                  "outputSchema": {"type": "array"},
                                  "cachePolicy": {"ttlSeconds": 120},
                                  "stalenessPolicy": {"mode": "strict"},
                                  "visibility": "tenant",
                                  "status": "enabled"
                                }
                                """)
                        .principal(authenticatedPrincipal()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.resolverCode").value("leave.types"))
                .andExpect(jsonPath("$.data.status").value("ENABLED"))
                .andExpect(jsonPath("$.data.visibility").value("TENANT"));

        ArgumentCaptor<ReferenceResolverUpsertCommand> commandCaptor =
                ArgumentCaptor.forClass(ReferenceResolverUpsertCommand.class);
        verify(referenceResolverManagementService).upsertResolver(
                eq("enterprise-default"),
                eq("test"),
                eq("oa-core"),
                eq("leave.types"),
                commandCaptor.capture());
        assertEquals("GET", commandCaptor.getValue().operationBinding().get("method"));
        assertEquals(List.of("oa-user"), commandCaptor.getValue().allowedAuthProfiles());
    }

    @Test
    void shouldReturnForbiddenWhenManageScopeDenied() throws Exception {
        when(authorizationService.canManageSpaceCatalog(any(AuthenticatedUserContext.class), eq("enterprise-default"), eq("prod")))
                .thenReturn(false);

        mockMvc.perform(get("/api/controlplane/spaces/enterprise-default/connectors/oa-core/reference-resolvers/manage")
                        .principal(authenticatedPrincipal()))
                .andExpect(status().isForbidden());

        verify(referenceResolverManagementService, never()).listResolvers("enterprise-default", "prod", "oa-core");
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
