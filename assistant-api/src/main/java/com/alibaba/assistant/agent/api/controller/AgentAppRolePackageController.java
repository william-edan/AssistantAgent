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

import com.alibaba.assistant.agent.api.controller.dto.RolePackageListResponse;
import com.alibaba.assistant.agent.api.controller.dto.RolePackageResponse;
import com.alibaba.assistant.agent.api.controller.dto.RolePackageUpsertRequest;
import com.alibaba.assistant.agent.api.security.AuthenticatedUserContext;
import com.alibaba.assistant.agent.api.security.MigrationControlPlaneAuthorizationService;
import com.alibaba.assistant.agent.controlplane.rolepackage.ResolvedRolePackageManagementView;
import com.alibaba.assistant.agent.controlplane.rolepackage.RolePackageManagementService;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.security.Principal;
import java.util.List;

/**
 * Agent-app scoped management API for role packages.
 */
@RestController
@Profile("migration")
@RequestMapping("/api/controlplane/spaces/{spaceCode}/agent-apps/{agentAppCode}/role-packages")
public class AgentAppRolePackageController {

    private static final String DEFAULT_ENVIRONMENT = "prod";

    private final RolePackageManagementService rolePackageManagementService;

    private final MigrationControlPlaneAuthorizationService authorizationService;

    public AgentAppRolePackageController(
            RolePackageManagementService rolePackageManagementService,
            MigrationControlPlaneAuthorizationService authorizationService) {
        this.rolePackageManagementService = rolePackageManagementService;
        this.authorizationService = authorizationService;
    }

    @GetMapping
    public ResponseEntity<RolePackageListResponse> listRolePackages(
            @PathVariable String spaceCode,
            @PathVariable String agentAppCode,
            @RequestParam(value = "environment", required = false) String environment,
            Principal principal) {
        String normalizedEnvironment = normalizeEnvironment(environment);
        AuthenticatedUserContext authenticatedUser = requireAuthenticatedUser(principal);
        requireManageAccess(authenticatedUser, spaceCode, normalizedEnvironment, agentAppCode);
        List<ResolvedRolePackageManagementView> rolePackages = rolePackageManagementService
                .listRolePackages(spaceCode, normalizedEnvironment, agentAppCode);
        return ResponseEntity.ok(RolePackageListResponse.ok(spaceCode, normalizedEnvironment, agentAppCode, rolePackages));
    }

    @GetMapping("/{roleCode}")
    public ResponseEntity<RolePackageResponse> getRolePackage(
            @PathVariable String spaceCode,
            @PathVariable String agentAppCode,
            @PathVariable String roleCode,
            @RequestParam(value = "environment", required = false) String environment,
            @RequestParam(value = "version", required = false) String version,
            Principal principal) {
        String normalizedEnvironment = normalizeEnvironment(environment);
        AuthenticatedUserContext authenticatedUser = requireAuthenticatedUser(principal);
        requireManageAccess(authenticatedUser, spaceCode, normalizedEnvironment, agentAppCode);
        ResolvedRolePackageManagementView resolved = rolePackageManagementService
                .getRolePackage(spaceCode, normalizedEnvironment, agentAppCode, roleCode, version)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "role_package_not_found"));
        return ResponseEntity.ok(RolePackageResponse.ok(resolved));
    }

    @PutMapping("/{roleCode}")
    public ResponseEntity<RolePackageResponse> upsertRolePackage(
            @PathVariable String spaceCode,
            @PathVariable String agentAppCode,
            @PathVariable String roleCode,
            @RequestParam(value = "environment", required = false) String environment,
            @RequestBody(required = false) RolePackageUpsertRequest request,
            Principal principal) {
        String normalizedEnvironment = normalizeEnvironment(environment);
        AuthenticatedUserContext authenticatedUser = requireAuthenticatedUser(principal);
        requireManageAccess(authenticatedUser, spaceCode, normalizedEnvironment, agentAppCode);
        ResolvedRolePackageManagementView resolved = rolePackageManagementService
                .upsertRolePackage(
                        spaceCode,
                        normalizedEnvironment,
                        agentAppCode,
                        roleCode,
                        request == null ? new RolePackageUpsertRequest(null, null, null, null, List.of(), List.of(), List.of(), List.of()).toCommand(roleCode) : request.toCommand(roleCode))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "role_package_not_found"));
        return ResponseEntity.ok(RolePackageResponse.ok(resolved));
    }

    @PostMapping("/{roleCode}/publish")
    public ResponseEntity<RolePackageResponse> publishRolePackage(
            @PathVariable String spaceCode,
            @PathVariable String agentAppCode,
            @PathVariable String roleCode,
            @RequestParam(value = "environment", required = false) String environment,
            @RequestParam("version") String version,
            Principal principal) {
        String normalizedEnvironment = normalizeEnvironment(environment);
        AuthenticatedUserContext authenticatedUser = requireAuthenticatedUser(principal);
        requireManageAccess(authenticatedUser, spaceCode, normalizedEnvironment, agentAppCode);
        ResolvedRolePackageManagementView resolved = rolePackageManagementService
                .publishRolePackage(spaceCode, normalizedEnvironment, agentAppCode, roleCode, version)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "role_package_not_found"));
        return ResponseEntity.ok(RolePackageResponse.ok(resolved));
    }

    private AuthenticatedUserContext requireAuthenticatedUser(Principal principal) {
        if (principal instanceof Authentication authentication
                && authentication.getPrincipal() instanceof AuthenticatedUserContext authenticatedUser) {
            return authenticatedUser;
        }
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "unauthenticated_user");
    }

    private void requireManageAccess(
            AuthenticatedUserContext authenticatedUser,
            String spaceCode,
            String environment,
            String agentAppCode) {
        if (!authorizationService.canManageAgentAppRolePackages(authenticatedUser, spaceCode, environment, agentAppCode)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "controlplane_scope_denied");
        }
    }

    private String normalizeEnvironment(String environment) {
        return StringUtils.hasText(environment) ? environment.trim() : DEFAULT_ENVIRONMENT;
    }
}
