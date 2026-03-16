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

import com.alibaba.assistant.agent.api.controller.dto.ManagedLocalUserData;
import com.alibaba.assistant.agent.api.controller.dto.ManagedLocalUserListData;
import com.alibaba.assistant.agent.api.controller.dto.ManagedLocalUserListResponse;
import com.alibaba.assistant.agent.api.controller.dto.ManagedLocalUserRequest;
import com.alibaba.assistant.agent.api.controller.dto.ManagedLocalUserResponse;
import com.alibaba.assistant.agent.api.security.AuthenticatedUserContext;
import com.alibaba.assistant.agent.api.security.MigrationControlPlaneAuthorizationService;
import com.alibaba.assistant.agent.controlplane.identity.LocalUserManagementService;
import com.alibaba.assistant.agent.controlplane.identity.ResolvedLocalUserManagementView;
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
 * 迁移模式下本地用户管理入口。
 */
@RestController
@Profile("migration")
@RequestMapping("/api/controlplane/spaces/{spaceCode}/local-users")
public class LocalUserManagementController {

    private static final String DEFAULT_ENVIRONMENT = "prod";

    private final LocalUserManagementService localUserManagementService;

    private final MigrationControlPlaneAuthorizationService authorizationService;

    public LocalUserManagementController(
            LocalUserManagementService localUserManagementService,
            MigrationControlPlaneAuthorizationService authorizationService) {
        this.localUserManagementService = localUserManagementService;
        this.authorizationService = authorizationService;
    }

    @GetMapping
    public ResponseEntity<ManagedLocalUserListResponse> listLocalUsers(
            @PathVariable String spaceCode,
            @RequestParam(value = "environment", required = false) String environment,
            @RequestParam(value = "keyword", required = false) String keyword,
            Principal principal) {
        String normalizedEnvironment = normalizeEnvironment(environment);
        AuthenticatedUserContext authenticatedUser = requireAuthenticatedUser(principal);
        requireManageAccess(authenticatedUser, spaceCode, normalizedEnvironment);
        return ResponseEntity.ok(ManagedLocalUserListResponse.ok(
                ManagedLocalUserListData.from(
                        spaceCode,
                        normalizedEnvironment,
                        localUserManagementService.listLocalUsers(spaceCode, normalizedEnvironment, keyword))));
    }

    @GetMapping("/{username}")
    public ResponseEntity<ManagedLocalUserResponse> getLocalUser(
            @PathVariable String spaceCode,
            @PathVariable String username,
            @RequestParam(value = "environment", required = false) String environment,
            @RequestParam(value = "systemCode", required = false) String systemCode,
            Principal principal) {
        String normalizedEnvironment = normalizeEnvironment(environment);
        AuthenticatedUserContext authenticatedUser = requireAuthenticatedUser(principal);
        requireManageAccess(authenticatedUser, spaceCode, normalizedEnvironment);
        ResolvedLocalUserManagementView resolved = localUserManagementService
                .getLocalUser(spaceCode, normalizedEnvironment, username, systemCode)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "local_user_not_found"));
        return ResponseEntity.ok(ManagedLocalUserResponse.ok(ManagedLocalUserData.from(resolved)));
    }

    @PutMapping("/{username}")
    public ResponseEntity<ManagedLocalUserResponse> upsertLocalUser(
            @PathVariable String spaceCode,
            @PathVariable String username,
            @RequestParam(value = "environment", required = false) String environment,
            @RequestBody(required = false) ManagedLocalUserRequest request,
            Principal principal) {
        String normalizedEnvironment = normalizeEnvironment(environment);
        AuthenticatedUserContext authenticatedUser = requireAuthenticatedUser(principal);
        requireManageAccess(authenticatedUser, spaceCode, normalizedEnvironment);
        ResolvedLocalUserManagementView resolved = localUserManagementService
                .upsertLocalUser(
                        spaceCode,
                        normalizedEnvironment,
                        username,
                        request == null ? new ManagedLocalUserRequest(null, null, null, null, null).toCommand() : request.toCommand())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "local_user_upsert_failed"));
        return ResponseEntity.ok(ManagedLocalUserResponse.ok(ManagedLocalUserData.from(resolved)));
    }

    private AuthenticatedUserContext requireAuthenticatedUser(Principal principal) {
        if (principal instanceof Authentication authentication
                && authentication.getPrincipal() instanceof AuthenticatedUserContext authenticatedUser) {
            return authenticatedUser;
        }
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "unauthenticated_user");
    }

    private void requireManageAccess(AuthenticatedUserContext authenticatedUser, String spaceCode, String environment) {
        if (!authorizationService.canManageLocalUserControlPlaneAccessPolicy(authenticatedUser, spaceCode, environment)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "controlplane_scope_denied");
        }
    }

    private String normalizeEnvironment(String environment) {
        return StringUtils.hasText(environment) ? environment.trim() : DEFAULT_ENVIRONMENT;
    }
}
