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

import com.alibaba.assistant.agent.api.controller.dto.RoleScenarioSlaSummaryData;
import com.alibaba.assistant.agent.api.controller.dto.RoleScenarioSlaSummaryResponse;
import com.alibaba.assistant.agent.api.security.AuthenticatedUserContext;
import com.alibaba.assistant.agent.api.security.MigrationControlPlaneAuthorizationService;
import com.alibaba.assistant.agent.controlplane.space.PlatformSpace;
import com.alibaba.assistant.agent.controlplane.space.PlatformSpaceService;
import com.alibaba.assistant.agent.runtime.observability.RoleKpiAggregator;
import com.alibaba.assistant.agent.runtime.observability.RoleScenarioSlaSummary;
import org.springframework.context.annotation.Profile;
import org.springframework.format.annotation.DateTimeFormat;
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
import java.time.LocalDateTime;

/**
 * Role-level observability query entrypoints.
 */
@RestController
@Profile("migration")
@RequestMapping("/api/controlplane/spaces/{spaceCode}/observability")
public class ObservabilityController {

    private final RoleKpiAggregator roleKpiAggregator;

    private final PlatformSpaceService platformSpaceService;

    private final MigrationControlPlaneAuthorizationService authorizationService;

    public ObservabilityController(
            RoleKpiAggregator roleKpiAggregator,
            PlatformSpaceService platformSpaceService,
            MigrationControlPlaneAuthorizationService authorizationService) {
        this.roleKpiAggregator = roleKpiAggregator;
        this.platformSpaceService = platformSpaceService;
        this.authorizationService = authorizationService;
    }

    @GetMapping("/role-scenarios/sla-summary")
    public ResponseEntity<RoleScenarioSlaSummaryResponse> getRoleScenarioSlaSummary(
            @PathVariable String spaceCode,
            @RequestParam(required = false) String environment,
            @RequestParam String agentAppCode,
            @RequestParam String rolePackageCode,
            @RequestParam String scenarioCode,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startedAfter,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startedBefore,
            Principal principal) {
        AuthenticatedUserContext authenticatedUser = requireAuthenticatedUser(principal);
        String normalizedEnvironment = normalizeEnvironment(environment);
        PlatformSpace space = platformSpaceService.findActiveByCode(spaceCode, normalizedEnvironment)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "controlplane_space_not_found"));
        if (!authorizationService.canViewSpaceCatalog(authenticatedUser, space.getSpaceCode(), normalizedEnvironment)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "controlplane_scope_denied");
        }
        RoleScenarioSlaSummary summary = roleKpiAggregator.summarizeRoleScenario(
                        space.getId(),
                        agentAppCode.trim(),
                        rolePackageCode.trim(),
                        scenarioCode.trim(),
                        startedAfter,
                        startedBefore)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "role_scenario_observability_not_found"));
        return ResponseEntity.ok(RoleScenarioSlaSummaryResponse.ok(
                RoleScenarioSlaSummaryData.from(summary, space.getSpaceCode(), normalizedEnvironment)));
    }

    private AuthenticatedUserContext requireAuthenticatedUser(Principal principal) {
        if (principal instanceof Authentication authentication
                && authentication.getPrincipal() instanceof AuthenticatedUserContext authenticatedUser) {
            return authenticatedUser;
        }
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "unauthenticated_user");
    }

    private String normalizeEnvironment(String environment) {
        return StringUtils.hasText(environment) ? environment.trim() : "prod";
    }
}
