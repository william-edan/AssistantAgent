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

import com.alibaba.assistant.agent.api.controller.dto.LocalUserControlPlaneAccessPolicyData;
import com.alibaba.assistant.agent.api.controller.dto.LocalUserControlPlaneAccessPolicyRequest;
import com.alibaba.assistant.agent.api.controller.dto.LocalUserControlPlaneAccessPolicyResponse;
import com.alibaba.assistant.agent.api.security.AuthenticatedUserContext;
import com.alibaba.assistant.agent.api.security.MigrationControlPlaneAuthorizationService;
import com.alibaba.assistant.agent.controlplane.identity.LocalUserControlPlaneAccessPolicyService;
import com.alibaba.assistant.agent.controlplane.identity.ResolvedLocalUserControlPlaneAccessPolicy;
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
 * 迁移模式下本地用户控制面权限策略管理入口。
 */
@RestController
@Profile("migration")
@RequestMapping("/api/controlplane/spaces/{spaceCode}/local-users/{localUserId}/controlplane-access-policy")
public class LocalUserControlPlaneAccessPolicyController {

    private static final String DEFAULT_ENVIRONMENT = "prod";

    private final LocalUserControlPlaneAccessPolicyService policyService;

    private final MigrationControlPlaneAuthorizationService authorizationService;

    public LocalUserControlPlaneAccessPolicyController(
            LocalUserControlPlaneAccessPolicyService policyService,
            MigrationControlPlaneAuthorizationService authorizationService) {
        this.policyService = policyService;
        this.authorizationService = authorizationService;
    }

    @GetMapping
    public ResponseEntity<LocalUserControlPlaneAccessPolicyResponse> getPolicy(
            @PathVariable String spaceCode,
            @PathVariable Long localUserId,
            @RequestParam(value = "environment", required = false) String environment,
            Principal principal) {
        String normalizedEnvironment = normalizeEnvironment(environment);
        AuthenticatedUserContext authenticatedUser = requireAuthenticatedUser(principal);
        requireManageGrantAccess(authenticatedUser, spaceCode, normalizedEnvironment);
        ResolvedLocalUserControlPlaneAccessPolicy resolved = policyService
                .getPolicy(spaceCode, normalizedEnvironment, localUserId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "local_user_or_space_not_found"));
        return ResponseEntity.ok(LocalUserControlPlaneAccessPolicyResponse.ok(LocalUserControlPlaneAccessPolicyData.from(resolved)));
    }

    @PutMapping
    public ResponseEntity<LocalUserControlPlaneAccessPolicyResponse> replacePolicy(
            @PathVariable String spaceCode,
            @PathVariable Long localUserId,
            @RequestParam(value = "environment", required = false) String environment,
            @RequestBody(required = false) LocalUserControlPlaneAccessPolicyRequest request,
            Principal principal) {
        String normalizedEnvironment = normalizeEnvironment(environment);
        AuthenticatedUserContext authenticatedUser = requireAuthenticatedUser(principal);
        requireManageGrantAccess(authenticatedUser, spaceCode, normalizedEnvironment);
        ResolvedLocalUserControlPlaneAccessPolicy resolved = policyService
                .replacePolicy(
                        spaceCode,
                        normalizedEnvironment,
                        localUserId,
                        request == null ? new LocalUserControlPlaneAccessPolicyRequest(false, null).toPolicy() : request.toPolicy())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "local_user_or_space_not_found"));
        return ResponseEntity.ok(LocalUserControlPlaneAccessPolicyResponse.ok(LocalUserControlPlaneAccessPolicyData.from(resolved)));
    }

    private AuthenticatedUserContext requireAuthenticatedUser(Principal principal) {
        if (principal instanceof Authentication authentication
                && authentication.getPrincipal() instanceof AuthenticatedUserContext authenticatedUser) {
            return authenticatedUser;
        }
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "unauthenticated_user");
    }

    private void requireManageGrantAccess(AuthenticatedUserContext authenticatedUser, String spaceCode, String environment) {
        if (!authorizationService.canManageLocalUserControlPlaneAccessPolicy(authenticatedUser, spaceCode, environment)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "controlplane_scope_denied");
        }
    }

    private String normalizeEnvironment(String environment) {
        return StringUtils.hasText(environment) ? environment.trim() : DEFAULT_ENVIRONMENT;
    }
}
