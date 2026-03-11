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

import com.alibaba.assistant.agent.api.controller.dto.ExecutionApprovalDecisionData;
import com.alibaba.assistant.agent.api.controller.dto.ExecutionApprovalDecisionResponse;
import com.alibaba.assistant.agent.api.controller.dto.ExecutionApprovalDetailData;
import com.alibaba.assistant.agent.api.controller.dto.ExecutionApprovalDetailResponse;
import com.alibaba.assistant.agent.api.controller.dto.ExecutionApprovalListData;
import com.alibaba.assistant.agent.api.controller.dto.ExecutionApprovalListResponse;
import com.alibaba.assistant.agent.api.controller.dto.ExecutionApprovalRequestData;
import com.alibaba.assistant.agent.api.security.AuthenticatedUserContext;
import com.alibaba.assistant.agent.api.security.MigrationControlPlaneAuthorizationService;
import com.alibaba.assistant.agent.runtime.execution.ExecutionApprovalDecisionView;
import com.alibaba.assistant.agent.runtime.execution.ExecutionApprovalDetailView;
import com.alibaba.assistant.agent.runtime.execution.ExecutionApprovalService;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.security.Principal;
import java.util.Optional;

/**
 * Control-plane API for approval queue listing and decisions.
 */
@RestController
@Profile("migration")
@RequestMapping("/api/controlplane/spaces/{spaceCode}/approval-requests")
public class ExecutionApprovalController {

    private final ExecutionApprovalService executionApprovalService;

    private final MigrationControlPlaneAuthorizationService authorizationService;

    public ExecutionApprovalController(
            ExecutionApprovalService executionApprovalService,
            MigrationControlPlaneAuthorizationService authorizationService) {
        this.executionApprovalService = executionApprovalService;
        this.authorizationService = authorizationService;
    }

    @GetMapping
    public ResponseEntity<ExecutionApprovalListResponse> listApprovalRequests(
            @PathVariable String spaceCode,
            @RequestParam(required = false) String environment,
            Principal principal) {
        AuthenticatedUserContext authenticatedUser = requireAuthenticatedUser(principal);
        String normalizedEnvironment = normalizeEnvironment(environment);
        requireApprovalAccess(authenticatedUser, spaceCode, normalizedEnvironment);
        return ResponseEntity.ok(ExecutionApprovalListResponse.ok(new ExecutionApprovalListData(
                executionApprovalService.listPendingRequests(spaceCode, normalizedEnvironment).stream()
                        .map(ExecutionApprovalRequestData::from)
                        .toList())));
    }

    @GetMapping("/{requestId}")
    public ResponseEntity<ExecutionApprovalDetailResponse> getApprovalRequest(
            @PathVariable String spaceCode,
            @PathVariable String requestId,
            @RequestParam(required = false) String environment,
            Principal principal) {
        AuthenticatedUserContext authenticatedUser = requireAuthenticatedUser(principal);
        String normalizedEnvironment = normalizeEnvironment(environment);
        requireApprovalAccess(authenticatedUser, spaceCode, normalizedEnvironment);
        return ResponseEntity.ok(ExecutionApprovalDetailResponse.ok(ExecutionApprovalDetailData.from(
                requireDetail(executionApprovalService.findRequest(spaceCode, normalizedEnvironment, requestId)))));
    }

    @PostMapping("/{requestId}/approve")
    public ResponseEntity<ExecutionApprovalDecisionResponse> approveRequest(
            @PathVariable String spaceCode,
            @PathVariable String requestId,
            @RequestParam(required = false) String environment,
            Principal principal) {
        AuthenticatedUserContext authenticatedUser = requireAuthenticatedUser(principal);
        String normalizedEnvironment = normalizeEnvironment(environment);
        requireApprovalAccess(authenticatedUser, spaceCode, normalizedEnvironment);
        return ResponseEntity.ok(ExecutionApprovalDecisionResponse.ok(ExecutionApprovalDecisionData.from(
                requireDecision(executionApprovalService.approveRequest(
                        spaceCode,
                        normalizedEnvironment,
                        requestId,
                        authenticatedUser.userId())))));
    }

    @PostMapping("/{requestId}/reject")
    public ResponseEntity<ExecutionApprovalDecisionResponse> rejectRequest(
            @PathVariable String spaceCode,
            @PathVariable String requestId,
            @RequestParam(required = false) String environment,
            Principal principal) {
        AuthenticatedUserContext authenticatedUser = requireAuthenticatedUser(principal);
        String normalizedEnvironment = normalizeEnvironment(environment);
        requireApprovalAccess(authenticatedUser, spaceCode, normalizedEnvironment);
        return ResponseEntity.ok(ExecutionApprovalDecisionResponse.ok(ExecutionApprovalDecisionData.from(
                requireDecision(executionApprovalService.rejectRequest(
                        spaceCode,
                        normalizedEnvironment,
                        requestId,
                        authenticatedUser.userId())))));
    }

    private ExecutionApprovalDetailView requireDetail(Optional<ExecutionApprovalDetailView> detailOptional) {
        return detailOptional.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "approval_request_not_found"));
    }

    private ExecutionApprovalDecisionView requireDecision(Optional<ExecutionApprovalDecisionView> decisionOptional) {
        return decisionOptional.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "approval_request_not_found"));
    }

    private AuthenticatedUserContext requireAuthenticatedUser(Principal principal) {
        if (principal instanceof Authentication authentication
                && authentication.getPrincipal() instanceof AuthenticatedUserContext authenticatedUser) {
            return authenticatedUser;
        }
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "unauthenticated_user");
    }

    private void requireApprovalAccess(AuthenticatedUserContext authenticatedUser, String spaceCode, String environment) {
        if (!authorizationService.canManageSpaceExecutionApprovals(authenticatedUser, spaceCode, environment)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "controlplane_scope_denied");
        }
    }

    private String normalizeEnvironment(String environment) {
        return StringUtils.hasText(environment) ? environment.trim() : "prod";
    }
}
