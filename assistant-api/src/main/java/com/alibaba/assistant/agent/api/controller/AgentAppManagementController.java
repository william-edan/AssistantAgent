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

import com.alibaba.assistant.agent.api.controller.dto.ManagedAgentAppData;
import com.alibaba.assistant.agent.api.controller.dto.ManagedAgentAppListData;
import com.alibaba.assistant.agent.api.controller.dto.ManagedAgentAppListResponse;
import com.alibaba.assistant.agent.api.controller.dto.ManagedAgentAppRequest;
import com.alibaba.assistant.agent.api.controller.dto.ManagedAgentAppResponse;
import com.alibaba.assistant.agent.api.security.AuthenticatedUserContext;
import com.alibaba.assistant.agent.api.security.MigrationControlPlaneAuthorizationService;
import com.alibaba.assistant.agent.controlplane.agentapp.AgentAppManagementService;
import com.alibaba.assistant.agent.controlplane.agentapp.ResolvedAgentAppManagementView;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.security.Principal;

/**
 * Control-plane management API for agent apps.
 */
@RestController
@Profile("migration")
@RequestMapping("/api/controlplane/spaces/{spaceCode}/agent-apps")
public class AgentAppManagementController {

    private static final String DEFAULT_ENVIRONMENT = "prod";

    private final AgentAppManagementService agentAppManagementService;

    private final MigrationControlPlaneAuthorizationService authorizationService;

    public AgentAppManagementController(
            AgentAppManagementService agentAppManagementService,
            MigrationControlPlaneAuthorizationService authorizationService) {
        this.agentAppManagementService = agentAppManagementService;
        this.authorizationService = authorizationService;
    }

    @GetMapping("/manage")
    public ResponseEntity<ManagedAgentAppListResponse> listAgentApps(
            @PathVariable String spaceCode,
            @RequestParam(value = "environment", required = false) String environment,
            @RequestParam(value = "keyword", required = false) String keyword,
            Principal principal) {
        String normalizedEnvironment = normalizeEnvironment(environment);
        AuthenticatedUserContext authenticatedUser = requireAuthenticatedUser(principal);
        requireManageAccess(authenticatedUser, spaceCode, normalizedEnvironment);
        return ResponseEntity.ok(ManagedAgentAppListResponse.ok(
                ManagedAgentAppListData.from(
                        spaceCode,
                        normalizedEnvironment,
                        agentAppManagementService.listAgentApps(spaceCode, normalizedEnvironment, keyword))));
    }

    @GetMapping("/{agentAppCode}")
    public ResponseEntity<ManagedAgentAppResponse> getAgentApp(
            @PathVariable String spaceCode,
            @PathVariable String agentAppCode,
            @RequestParam(value = "environment", required = false) String environment,
            Principal principal) {
        String normalizedEnvironment = normalizeEnvironment(environment);
        AuthenticatedUserContext authenticatedUser = requireAuthenticatedUser(principal);
        requireManageAccess(authenticatedUser, spaceCode, normalizedEnvironment);
        ResolvedAgentAppManagementView resolved = agentAppManagementService
                .getAgentApp(spaceCode, normalizedEnvironment, agentAppCode)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "agent_app_not_found"));
        return ResponseEntity.ok(ManagedAgentAppResponse.ok(ManagedAgentAppData.from(resolved)));
    }
    @PutMapping("/{agentAppCode}")
    public ResponseEntity<ManagedAgentAppResponse> upsertAgentApp(
            @PathVariable String spaceCode,
            @PathVariable String agentAppCode,
            @RequestParam(value = "environment", required = false) String environment,
            @RequestBody(required = false) ManagedAgentAppRequest request,
            Principal principal) {
        String normalizedEnvironment = normalizeEnvironment(environment);
        AuthenticatedUserContext authenticatedUser = requireAuthenticatedUser(principal);
        requireManageAccess(authenticatedUser, spaceCode, normalizedEnvironment);
        ResolvedAgentAppManagementView resolved = agentAppManagementService
                .upsertAgentApp(
                        spaceCode,
                        normalizedEnvironment,
                        agentAppCode,
                        request == null ? new ManagedAgentAppRequest(null, null, null, null, null).toCommand() : request.toCommand())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "space_not_found"));
        return ResponseEntity.ok(ManagedAgentAppResponse.ok(ManagedAgentAppData.from(resolved)));
    }

    private AuthenticatedUserContext requireAuthenticatedUser(Principal principal) {
        if (principal instanceof Authentication authentication
                && authentication.getPrincipal() instanceof AuthenticatedUserContext authenticatedUser) {
            return authenticatedUser;
        }
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "unauthenticated_user");
    }

    private void requireManageAccess(AuthenticatedUserContext authenticatedUser, String spaceCode, String environment) {
        if (!authorizationService.canManageSpaceCatalog(authenticatedUser, spaceCode, environment)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "controlplane_scope_denied");
        }
    }

    private String normalizeEnvironment(String environment) {
        return StringUtils.hasText(environment) ? environment.trim() : DEFAULT_ENVIRONMENT;
    }
}



