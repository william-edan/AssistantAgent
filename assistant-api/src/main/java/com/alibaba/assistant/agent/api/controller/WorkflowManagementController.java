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

import com.alibaba.assistant.agent.api.controller.dto.ManagedWorkflowData;
import com.alibaba.assistant.agent.api.controller.dto.ManagedWorkflowListData;
import com.alibaba.assistant.agent.api.controller.dto.ManagedWorkflowListResponse;
import com.alibaba.assistant.agent.api.controller.dto.ManagedWorkflowRequest;
import com.alibaba.assistant.agent.api.controller.dto.ManagedWorkflowResponse;
import com.alibaba.assistant.agent.api.security.AuthenticatedUserContext;
import com.alibaba.assistant.agent.api.security.MigrationControlPlaneAuthorizationService;
import com.alibaba.assistant.agent.controlplane.workflow.ResolvedWorkflowManagementView;
import com.alibaba.assistant.agent.controlplane.workflow.WorkflowManagementService;
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
import java.util.List;

/**
 * Control-plane management API for workflows.
 */
@RestController
@Profile("migration")
@RequestMapping("/api/controlplane/spaces/{spaceCode}/workflows")
public class WorkflowManagementController {

    private static final String DEFAULT_ENVIRONMENT = "prod";

    private final WorkflowManagementService workflowManagementService;

    private final MigrationControlPlaneAuthorizationService authorizationService;

    public WorkflowManagementController(
            WorkflowManagementService workflowManagementService,
            MigrationControlPlaneAuthorizationService authorizationService) {
        this.workflowManagementService = workflowManagementService;
        this.authorizationService = authorizationService;
    }

    @GetMapping("/manage")
    public ResponseEntity<ManagedWorkflowListResponse> listWorkflows(
            @PathVariable String spaceCode,
            @RequestParam(value = "environment", required = false) String environment,
            Principal principal) {
        String normalizedEnvironment = normalizeEnvironment(environment);
        AuthenticatedUserContext authenticatedUser = requireAuthenticatedUser(principal);
        requireManageAccess(authenticatedUser, spaceCode, normalizedEnvironment);
        return ResponseEntity.ok(ManagedWorkflowListResponse.ok(
                ManagedWorkflowListData.from(
                        spaceCode,
                        normalizedEnvironment,
                        workflowManagementService.listWorkflows(spaceCode, normalizedEnvironment))));
    }

    @PutMapping("/{workflowCode}")
    public ResponseEntity<ManagedWorkflowResponse> upsertWorkflow(
            @PathVariable String spaceCode,
            @PathVariable String workflowCode,
            @RequestParam(value = "environment", required = false) String environment,
            @RequestBody(required = false) ManagedWorkflowRequest request,
            Principal principal) {
        String normalizedEnvironment = normalizeEnvironment(environment);
        AuthenticatedUserContext authenticatedUser = requireAuthenticatedUser(principal);
        requireManageAccess(authenticatedUser, spaceCode, normalizedEnvironment);
        ResolvedWorkflowManagementView resolved = workflowManagementService
                .upsertWorkflow(
                        spaceCode,
                        normalizedEnvironment,
                        workflowCode,
                        request == null
                                ? new ManagedWorkflowRequest(null, null, null, null, null, null, List.of(), null).toCommand()
                                : request.toCommand())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "workflow_reference_not_found"));
        return ResponseEntity.ok(ManagedWorkflowResponse.ok(ManagedWorkflowData.from(resolved)));
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


