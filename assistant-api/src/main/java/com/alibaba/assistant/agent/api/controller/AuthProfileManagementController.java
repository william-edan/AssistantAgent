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

import com.alibaba.assistant.agent.api.controller.dto.ConnectorManagedAuthProfileData;
import com.alibaba.assistant.agent.api.controller.dto.ConnectorManagedAuthProfileListData;
import com.alibaba.assistant.agent.api.controller.dto.ConnectorManagedAuthProfileListResponse;
import com.alibaba.assistant.agent.api.controller.dto.ConnectorManagedAuthProfileRequest;
import com.alibaba.assistant.agent.api.controller.dto.ConnectorManagedAuthProfileResponse;
import com.alibaba.assistant.agent.api.security.AuthenticatedUserContext;
import com.alibaba.assistant.agent.api.security.MigrationControlPlaneAuthorizationService;
import com.alibaba.assistant.agent.controlplane.connector.AuthProfileManagementService;
import com.alibaba.assistant.agent.controlplane.connector.ResolvedAuthProfileManagementView;
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
 * Control-plane management API for auth profiles.
 */
@RestController
@Profile("migration")
@RequestMapping("/api/controlplane/spaces/{spaceCode}/connectors/{connectorCode}/auth-profiles")
public class AuthProfileManagementController {

    private static final String DEFAULT_ENVIRONMENT = "prod";

    private final AuthProfileManagementService authProfileManagementService;

    private final MigrationControlPlaneAuthorizationService authorizationService;

    public AuthProfileManagementController(
            AuthProfileManagementService authProfileManagementService,
            MigrationControlPlaneAuthorizationService authorizationService) {
        this.authProfileManagementService = authProfileManagementService;
        this.authorizationService = authorizationService;
    }

    @GetMapping("/manage")
    public ResponseEntity<ConnectorManagedAuthProfileListResponse> listAuthProfiles(
            @PathVariable String spaceCode,
            @PathVariable String connectorCode,
            @RequestParam(value = "environment", required = false) String environment,
            @RequestParam(value = "keyword", required = false) String keyword,
            Principal principal) {
        String normalizedEnvironment = normalizeEnvironment(environment);
        AuthenticatedUserContext authenticatedUser = requireAuthenticatedUser(principal);
        requireManageAccess(authenticatedUser, spaceCode, normalizedEnvironment);
        return ResponseEntity.ok(ConnectorManagedAuthProfileListResponse.ok(
                ConnectorManagedAuthProfileListData.from(
                        spaceCode,
                        normalizedEnvironment,
                        connectorCode,
                        authProfileManagementService.listAuthProfiles(spaceCode, normalizedEnvironment, connectorCode, keyword))));
    }

    @GetMapping("/{authProfileCode}")
    public ResponseEntity<ConnectorManagedAuthProfileResponse> getAuthProfile(
            @PathVariable String spaceCode,
            @PathVariable String connectorCode,
            @PathVariable String authProfileCode,
            @RequestParam(value = "environment", required = false) String environment,
            Principal principal) {
        String normalizedEnvironment = normalizeEnvironment(environment);
        AuthenticatedUserContext authenticatedUser = requireAuthenticatedUser(principal);
        requireManageAccess(authenticatedUser, spaceCode, normalizedEnvironment);
        ResolvedAuthProfileManagementView resolved = authProfileManagementService
                .getAuthProfile(spaceCode, normalizedEnvironment, connectorCode, authProfileCode)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "auth_profile_not_found"));
        return ResponseEntity.ok(ConnectorManagedAuthProfileResponse.ok(ConnectorManagedAuthProfileData.from(resolved)));
    }
    @PutMapping("/{authProfileCode}")
    public ResponseEntity<ConnectorManagedAuthProfileResponse> upsertAuthProfile(
            @PathVariable String spaceCode,
            @PathVariable String connectorCode,
            @PathVariable String authProfileCode,
            @RequestParam(value = "environment", required = false) String environment,
            @RequestBody(required = false) ConnectorManagedAuthProfileRequest request,
            Principal principal) {
        String normalizedEnvironment = normalizeEnvironment(environment);
        AuthenticatedUserContext authenticatedUser = requireAuthenticatedUser(principal);
        requireManageAccess(authenticatedUser, spaceCode, normalizedEnvironment);
        ResolvedAuthProfileManagementView resolved = authProfileManagementService
                .upsertAuthProfile(
                        spaceCode,
                        normalizedEnvironment,
                        connectorCode,
                        authProfileCode,
                        request == null
                                ? new ConnectorManagedAuthProfileRequest(null, null, null, null, null, null, null, null, null, null).toCommand()
                                : request.toCommand())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "connector_not_found"));
        return ResponseEntity.ok(ConnectorManagedAuthProfileResponse.ok(ConnectorManagedAuthProfileData.from(resolved)));
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



