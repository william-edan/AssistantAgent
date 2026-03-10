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

import com.alibaba.assistant.agent.api.controller.dto.ExecutionHistoryDetailData;
import com.alibaba.assistant.agent.api.controller.dto.ExecutionHistoryDetailResponse;
import com.alibaba.assistant.agent.api.security.AuthenticatedUserContext;
import com.alibaba.assistant.agent.api.security.MigrationControlPlaneAuthorizationService;
import com.alibaba.assistant.agent.controlplane.space.PlatformSpace;
import com.alibaba.assistant.agent.controlplane.space.PlatformSpaceService;
import com.alibaba.assistant.agent.execution.persistence.ExecutionHistoryDetailView;
import com.alibaba.assistant.agent.execution.persistence.ExecutionHistoryService;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.security.Principal;

/**
 * Control-plane read API for persisted execution history.
 */
@RestController
@Profile("migration")
@RequestMapping("/api/controlplane/execution-runs")
public class ExecutionHistoryController {

    private final ExecutionHistoryService executionHistoryService;

    private final PlatformSpaceService platformSpaceService;

    private final MigrationControlPlaneAuthorizationService authorizationService;

    public ExecutionHistoryController(
            ExecutionHistoryService executionHistoryService,
            PlatformSpaceService platformSpaceService,
            MigrationControlPlaneAuthorizationService authorizationService) {
        this.executionHistoryService = executionHistoryService;
        this.platformSpaceService = platformSpaceService;
        this.authorizationService = authorizationService;
    }

    @GetMapping("/{runId}")
    public ResponseEntity<ExecutionHistoryDetailResponse> getExecutionRun(
            @PathVariable String runId,
            Principal principal) {
        AuthenticatedUserContext authenticatedUser = requireAuthenticatedUser(principal);
        ExecutionHistoryDetailView detailView = executionHistoryService.findDetailByRunId(runId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "execution_run_not_found"));
        PlatformSpace space = requireSpace(detailView.spaceId());
        requireCatalogAccess(authenticatedUser, space);
        return ResponseEntity.ok(ExecutionHistoryDetailResponse.ok(ExecutionHistoryDetailData.from(
                detailView,
                space.getSpaceCode(),
                normalizeEnvironment(space.getEnvironment()))));
    }

    private AuthenticatedUserContext requireAuthenticatedUser(Principal principal) {
        if (principal instanceof Authentication authentication
                && authentication.getPrincipal() instanceof AuthenticatedUserContext authenticatedUser) {
            return authenticatedUser;
        }
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "unauthenticated_user");
    }

    private PlatformSpace requireSpace(Long spaceId) {
        PlatformSpace space = spaceId != null ? platformSpaceService.getById(spaceId) : null;
        if (space == null || !StringUtils.hasText(space.getSpaceCode())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "execution_space_not_found");
        }
        return space;
    }

    private void requireCatalogAccess(AuthenticatedUserContext authenticatedUser, PlatformSpace space) {
        String environment = normalizeEnvironment(space.getEnvironment());
        if (!authorizationService.canViewSpaceCatalog(authenticatedUser, space.getSpaceCode(), environment)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "controlplane_scope_denied");
        }
    }

    private String normalizeEnvironment(String environment) {
        return StringUtils.hasText(environment) ? environment.trim() : "prod";
    }
}
