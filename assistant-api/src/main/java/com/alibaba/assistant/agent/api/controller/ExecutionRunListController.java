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

import com.alibaba.assistant.agent.api.controller.dto.ExecutionRunListData;
import com.alibaba.assistant.agent.api.controller.dto.ExecutionRunListResponse;
import com.alibaba.assistant.agent.api.controller.dto.ExecutionRunSummaryData;
import com.alibaba.assistant.agent.api.security.AuthenticatedUserContext;
import com.alibaba.assistant.agent.api.security.MigrationControlPlaneAuthorizationService;
import com.alibaba.assistant.agent.controlplane.space.PlatformSpace;
import com.alibaba.assistant.agent.controlplane.space.PlatformSpaceService;
import com.alibaba.assistant.agent.execution.persistence.ExecutionHistoryService;
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
 * 执行运行列表查询入口。
 */
@RestController
@Profile("migration")
@RequestMapping("/api/controlplane/spaces/{spaceCode}/execution-runs")
public class ExecutionRunListController {

    private final ExecutionHistoryService executionHistoryService;

    private final PlatformSpaceService platformSpaceService;

    private final MigrationControlPlaneAuthorizationService authorizationService;

    public ExecutionRunListController(
            ExecutionHistoryService executionHistoryService,
            PlatformSpaceService platformSpaceService,
            MigrationControlPlaneAuthorizationService authorizationService) {
        this.executionHistoryService = executionHistoryService;
        this.platformSpaceService = platformSpaceService;
        this.authorizationService = authorizationService;
    }

    @GetMapping
    public ResponseEntity<ExecutionRunListResponse> listExecutionRuns(
            @PathVariable String spaceCode,
            @RequestParam(required = false) String environment,
            @RequestParam(required = false) String runId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String artifactCode,
            @RequestParam(required = false) String platformPrincipalId,
            @RequestParam(required = false) String threadId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startedAfter,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startedBefore,
            @RequestParam(required = false) Integer limit,
            Principal principal) {
        AuthenticatedUserContext authenticatedUser = requireAuthenticatedUser(principal);
        String normalizedEnvironment = normalizeEnvironment(environment);
        PlatformSpace space = platformSpaceService.findActiveByCode(spaceCode, normalizedEnvironment)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "controlplane_space_not_found"));
        if (!authorizationService.canViewSpaceCatalog(authenticatedUser, space.getSpaceCode(), normalizedEnvironment)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "controlplane_scope_denied");
        }
        return ResponseEntity.ok(ExecutionRunListResponse.ok(new ExecutionRunListData(
                executionHistoryService.listRuns(
                                space.getId(),
                                normalizeOptional(runId),
                                normalizeOptional(status),
                                normalizeOptional(artifactCode),
                                normalizeOptional(platformPrincipalId),
                                normalizeOptional(threadId),
                                startedAfter,
                                startedBefore,
                                limit)
                        .stream()
                        .map(run -> ExecutionRunSummaryData.from(run, space.getSpaceCode(), normalizedEnvironment))
                        .toList())));
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

    private String normalizeOptional(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }
}
