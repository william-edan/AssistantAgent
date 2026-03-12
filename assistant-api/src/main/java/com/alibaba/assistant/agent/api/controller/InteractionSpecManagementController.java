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

import com.alibaba.assistant.agent.api.controller.dto.ManagedInteractionSpecData;
import com.alibaba.assistant.agent.api.controller.dto.ManagedInteractionSpecListData;
import com.alibaba.assistant.agent.api.controller.dto.ManagedInteractionSpecListResponse;
import com.alibaba.assistant.agent.api.controller.dto.ManagedInteractionSpecRequest;
import com.alibaba.assistant.agent.api.controller.dto.ManagedInteractionSpecResponse;
import com.alibaba.assistant.agent.api.security.AuthenticatedUserContext;
import com.alibaba.assistant.agent.api.security.MigrationControlPlaneAuthorizationService;
import com.alibaba.assistant.agent.controlplane.interaction.InteractionSpecManagementService;
import com.alibaba.assistant.agent.controlplane.interaction.ResolvedInteractionSpecManagementView;
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
 * Control-plane management API for interaction specs.
 */
@RestController
@Profile("migration")
@RequestMapping("/api/controlplane/spaces/{spaceCode}/interactions")
public class InteractionSpecManagementController {

    private static final String DEFAULT_ENVIRONMENT = "prod";

    private final InteractionSpecManagementService interactionSpecManagementService;

    private final MigrationControlPlaneAuthorizationService authorizationService;

    public InteractionSpecManagementController(
            InteractionSpecManagementService interactionSpecManagementService,
            MigrationControlPlaneAuthorizationService authorizationService) {
        this.interactionSpecManagementService = interactionSpecManagementService;
        this.authorizationService = authorizationService;
    }

    @GetMapping("/manage")
    public ResponseEntity<ManagedInteractionSpecListResponse> listInteractions(
            @PathVariable String spaceCode,
            @RequestParam(value = "environment", required = false) String environment,
            @RequestParam(value = "keyword", required = false) String keyword,
            Principal principal) {
        String normalizedEnvironment = normalizeEnvironment(environment);
        AuthenticatedUserContext authenticatedUser = requireAuthenticatedUser(principal);
        requireManageAccess(authenticatedUser, spaceCode, normalizedEnvironment);
        return ResponseEntity.ok(ManagedInteractionSpecListResponse.ok(
                ManagedInteractionSpecListData.from(
                        spaceCode,
                        normalizedEnvironment,
                        interactionSpecManagementService.listInteractions(spaceCode, normalizedEnvironment, keyword))));
    }

    @GetMapping("/{interactionCode}")
    public ResponseEntity<ManagedInteractionSpecResponse> getInteraction(
            @PathVariable String spaceCode,
            @PathVariable String interactionCode,
            @RequestParam(value = "environment", required = false) String environment,
            Principal principal) {
        String normalizedEnvironment = normalizeEnvironment(environment);
        AuthenticatedUserContext authenticatedUser = requireAuthenticatedUser(principal);
        requireManageAccess(authenticatedUser, spaceCode, normalizedEnvironment);
        ResolvedInteractionSpecManagementView resolved = interactionSpecManagementService
                .getInteraction(spaceCode, normalizedEnvironment, interactionCode)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "interaction_not_found"));
        return ResponseEntity.ok(ManagedInteractionSpecResponse.ok(ManagedInteractionSpecData.from(resolved)));
    }
    @PutMapping("/{interactionCode}")
    public ResponseEntity<ManagedInteractionSpecResponse> upsertInteraction(
            @PathVariable String spaceCode,
            @PathVariable String interactionCode,
            @RequestParam(value = "environment", required = false) String environment,
            @RequestBody(required = false) ManagedInteractionSpecRequest request,
            Principal principal) {
        String normalizedEnvironment = normalizeEnvironment(environment);
        AuthenticatedUserContext authenticatedUser = requireAuthenticatedUser(principal);
        requireManageAccess(authenticatedUser, spaceCode, normalizedEnvironment);
        ResolvedInteractionSpecManagementView resolved = interactionSpecManagementService
                .upsertInteraction(
                        spaceCode,
                        normalizedEnvironment,
                        interactionCode,
                        request == null
                                ? new ManagedInteractionSpecRequest(null, null, null, null, null, null, null).toCommand()
                                : request.toCommand())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "space_not_found"));
        return ResponseEntity.ok(ManagedInteractionSpecResponse.ok(ManagedInteractionSpecData.from(resolved)));
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



