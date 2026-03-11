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

import com.alibaba.assistant.agent.api.controlplane.ControlPlaneExecutionOverview;
import com.alibaba.assistant.agent.api.controlplane.ControlPlaneExecutionOverviewService;
import com.alibaba.assistant.agent.api.controller.dto.ControlPlaneCatalogOverviewData;
import com.alibaba.assistant.agent.api.controller.dto.ControlPlaneCatalogOverviewResponse;
import com.alibaba.assistant.agent.api.controller.dto.ControlPlaneExecutionOverviewData;
import com.alibaba.assistant.agent.api.controller.dto.ControlPlaneExecutionOverviewResponse;
import com.alibaba.assistant.agent.api.controller.dto.ControlPlaneSpaceListData;
import com.alibaba.assistant.agent.api.controller.dto.ControlPlaneSpaceListResponse;
import com.alibaba.assistant.agent.api.security.AuthenticatedUserContext;
import com.alibaba.assistant.agent.api.security.MigrationControlPlaneAuthorizationService;
import com.alibaba.assistant.agent.controlplane.catalog.ControlPlaneCatalogOverview;
import com.alibaba.assistant.agent.controlplane.catalog.ControlPlaneCatalogService;
import com.alibaba.assistant.agent.controlplane.catalog.ResolvedPlatformSpaceView;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.security.Principal;
import java.util.List;

/**
 * Control-plane navigation API for operator-facing catalog pages.
 */
@RestController
@Profile("migration")
@RequestMapping("/api/controlplane")
public class ControlPlaneCatalogController {

    private static final String DEFAULT_ENVIRONMENT = "prod";

    private final ControlPlaneCatalogService controlPlaneCatalogService;

    private final ControlPlaneExecutionOverviewService controlPlaneExecutionOverviewService;

    private final MigrationControlPlaneAuthorizationService authorizationService;

    public ControlPlaneCatalogController(
            ControlPlaneCatalogService controlPlaneCatalogService,
            ControlPlaneExecutionOverviewService controlPlaneExecutionOverviewService,
            MigrationControlPlaneAuthorizationService authorizationService) {
        this.controlPlaneCatalogService = controlPlaneCatalogService;
        this.controlPlaneExecutionOverviewService = controlPlaneExecutionOverviewService;
        this.authorizationService = authorizationService;
    }

    @GetMapping("/spaces")
    public ResponseEntity<ControlPlaneSpaceListResponse> listSpaces(
            @RequestParam(value = "environment", required = false) String environment,
            @RequestParam(value = "keyword", required = false) String keyword,
            Principal principal) {
        String normalizedEnvironment = normalizeEnvironment(environment);
        AuthenticatedUserContext authenticatedUser = requireAuthenticatedUser(principal);
        List<ResolvedPlatformSpaceView> authorizedSpaces = controlPlaneCatalogService.listSpaces(normalizedEnvironment, keyword).stream()
                .filter(space -> authorizationService.canViewSpaceCatalog(
                        authenticatedUser,
                        space.spaceCode(),
                        normalizedEnvironment))
                .toList();
        return ResponseEntity.ok(ControlPlaneSpaceListResponse.ok(ControlPlaneSpaceListData.from(authorizedSpaces)));
    }

    @GetMapping("/spaces/{spaceCode}/catalog-overview")
    public ResponseEntity<ControlPlaneCatalogOverviewResponse> getCatalogOverview(
            @PathVariable String spaceCode,
            @RequestParam(value = "environment", required = false) String environment,
            @RequestParam(value = "keyword", required = false) String keyword,
            Principal principal) {
        String normalizedEnvironment = normalizeEnvironment(environment);
        AuthenticatedUserContext authenticatedUser = requireAuthenticatedUser(principal);
        requireCatalogAccess(authenticatedUser, spaceCode, normalizedEnvironment);
        ControlPlaneCatalogOverview overview = controlPlaneCatalogService.getOverview(spaceCode, normalizedEnvironment, keyword)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "space_catalog_not_found"));
        return ResponseEntity.ok(ControlPlaneCatalogOverviewResponse.ok(ControlPlaneCatalogOverviewData.from(overview)));
    }

    @GetMapping("/spaces/{spaceCode}/execution-overview")
    public ResponseEntity<ControlPlaneExecutionOverviewResponse> getExecutionOverview(
            @PathVariable String spaceCode,
            @RequestParam(value = "environment", required = false) String environment,
            @RequestParam(value = "recentRunLimit", required = false) Integer recentRunLimit,
            @RequestParam(value = "pendingApprovalLimit", required = false) Integer pendingApprovalLimit,
            Principal principal) {
        String normalizedEnvironment = normalizeEnvironment(environment);
        AuthenticatedUserContext authenticatedUser = requireAuthenticatedUser(principal);
        requireCatalogAccess(authenticatedUser, spaceCode, normalizedEnvironment);
        boolean approvalAccess = authorizationService.canManageSpaceExecutionApprovals(
                authenticatedUser,
                spaceCode,
                normalizedEnvironment);
        ControlPlaneExecutionOverview overview = controlPlaneExecutionOverviewService
                .getOverview(spaceCode, normalizedEnvironment, recentRunLimit, pendingApprovalLimit, approvalAccess)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "space_execution_overview_not_found"));
        return ResponseEntity.ok(ControlPlaneExecutionOverviewResponse.ok(ControlPlaneExecutionOverviewData.from(overview)));
    }

    private AuthenticatedUserContext requireAuthenticatedUser(Principal principal) {
        if (principal instanceof Authentication authentication
                && authentication.getPrincipal() instanceof AuthenticatedUserContext authenticatedUser) {
            return authenticatedUser;
        }
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "unauthenticated_user");
    }

    private void requireCatalogAccess(AuthenticatedUserContext authenticatedUser, String spaceCode, String environment) {
        if (!authorizationService.canViewSpaceCatalog(authenticatedUser, spaceCode, environment)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "controlplane_scope_denied");
        }
    }

    private String normalizeEnvironment(String environment) {
        return StringUtils.hasText(environment) ? environment.trim() : DEFAULT_ENVIRONMENT;
    }
}
