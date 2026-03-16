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

import com.alibaba.assistant.agent.api.controller.dto.ExecutionEventTimelineData;
import com.alibaba.assistant.agent.api.controller.dto.ExecutionEventTimelineResponse;
import com.alibaba.assistant.agent.api.security.AuthenticatedUserContext;
import com.alibaba.assistant.agent.api.security.MigrationControlPlaneAuthorizationService;
import com.alibaba.assistant.agent.controlplane.space.PlatformSpace;
import com.alibaba.assistant.agent.controlplane.space.PlatformSpaceService;
import com.alibaba.assistant.agent.execution.persistence.ExecutionEventTimelineService;
import com.alibaba.assistant.agent.execution.persistence.ExecutionEventTimelineView;
import org.springframework.context.annotation.Profile;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.security.Principal;
import java.time.LocalDateTime;

/**
 * 持久化执行事件时间线查询入口。
 */
@RestController
@Profile("migration")
public class ExecutionEventTimelineController {

    private final ExecutionEventTimelineService executionEventTimelineService;

    private final PlatformSpaceService platformSpaceService;

    private final MigrationControlPlaneAuthorizationService authorizationService;

    public ExecutionEventTimelineController(
            ExecutionEventTimelineService executionEventTimelineService,
            PlatformSpaceService platformSpaceService,
            MigrationControlPlaneAuthorizationService authorizationService) {
        this.executionEventTimelineService = executionEventTimelineService;
        this.platformSpaceService = platformSpaceService;
        this.authorizationService = authorizationService;
    }

    @GetMapping("/api/controlplane/spaces/{spaceCode}/execution-runs/{runId}/events")
    public ResponseEntity<ExecutionEventTimelineResponse> getExecutionTimeline(
            @PathVariable String spaceCode,
            @PathVariable String runId,
            @RequestParam(required = false) String environment,
            @RequestParam(required = false) String stepId,
            @RequestParam(required = false) String eventType,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime occurredAfter,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime occurredBefore,
            @RequestParam(required = false) Integer limit,
            Principal principal) {
        AuthenticatedUserContext authenticatedUser = requireAuthenticatedUser(principal);
        ExecutionEventTimelineView timelineView = executionEventTimelineService.findTimeline(
                        runId,
                        normalizeOptional(stepId),
                        normalizeOptional(eventType),
                        occurredAfter,
                        occurredBefore,
                        limit)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "execution_run_not_found"));
        PlatformSpace space = requireSpace(timelineView.spaceId());
        requireScopedMatch(space, spaceCode, environment);
        requireCatalogAccess(authenticatedUser, space);
        return ResponseEntity.ok(ExecutionEventTimelineResponse.ok(ExecutionEventTimelineData.from(
                timelineView,
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

    private void requireScopedMatch(PlatformSpace space, String requestedSpaceCode, String requestedEnvironment) {
        String normalizedRequestedSpaceCode = requestedSpaceCode.trim();
        String actualSpaceCode = space.getSpaceCode().trim();
        String normalizedRequestedEnvironment = normalizeEnvironment(requestedEnvironment);
        String actualEnvironment = normalizeEnvironment(space.getEnvironment());
        if (!normalizedRequestedSpaceCode.equals(actualSpaceCode)
                || !normalizedRequestedEnvironment.equals(actualEnvironment)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "execution_run_not_found");
        }
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

    private String normalizeOptional(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }
}

