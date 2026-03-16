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

import com.alibaba.assistant.agent.api.controller.dto.ManagedToolMetaData;
import com.alibaba.assistant.agent.api.controller.dto.ManagedToolMetaListData;
import com.alibaba.assistant.agent.api.controller.dto.ManagedToolMetaListResponse;
import com.alibaba.assistant.agent.api.controller.dto.ManagedToolMetaRequest;
import com.alibaba.assistant.agent.api.controller.dto.ManagedToolMetaResponse;
import com.alibaba.assistant.agent.api.security.AuthenticatedUserContext;
import com.alibaba.assistant.agent.api.security.MigrationControlPlaneAuthorizationService;
import com.alibaba.assistant.agent.controlplane.toolregistry.ResolvedToolMetaManagementView;
import com.alibaba.assistant.agent.controlplane.toolregistry.ToolMetaManagementService;
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
 * 工具定义管理入口。
 */
@RestController
@Profile("migration")
@RequestMapping("/api/controlplane/spaces/{spaceCode}/connectors/{connectorCode}/tools")
public class ToolMetaManagementController {

    private static final String DEFAULT_ENVIRONMENT = "prod";

    private final ToolMetaManagementService toolMetaManagementService;

    private final MigrationControlPlaneAuthorizationService authorizationService;

    public ToolMetaManagementController(
            ToolMetaManagementService toolMetaManagementService,
            MigrationControlPlaneAuthorizationService authorizationService) {
        this.toolMetaManagementService = toolMetaManagementService;
        this.authorizationService = authorizationService;
    }

    @GetMapping("/manage")
    public ResponseEntity<ManagedToolMetaListResponse> listTools(
            @PathVariable String spaceCode,
            @PathVariable String connectorCode,
            @RequestParam(value = "environment", required = false) String environment,
            @RequestParam(value = "keyword", required = false) String keyword,
            Principal principal) {
        String normalizedEnvironment = normalizeEnvironment(environment);
        AuthenticatedUserContext authenticatedUser = requireAuthenticatedUser(principal);
        requireManageAccess(authenticatedUser, spaceCode, normalizedEnvironment);
        return ResponseEntity.ok(ManagedToolMetaListResponse.ok(
                ManagedToolMetaListData.from(
                        spaceCode,
                        normalizedEnvironment,
                        connectorCode,
                        toolMetaManagementService.listTools(spaceCode, normalizedEnvironment, connectorCode, keyword))));
    }

    @GetMapping("/{toolCode}")
    public ResponseEntity<ManagedToolMetaResponse> getTool(
            @PathVariable String spaceCode,
            @PathVariable String connectorCode,
            @PathVariable String toolCode,
            @RequestParam(value = "environment", required = false) String environment,
            Principal principal) {
        String normalizedEnvironment = normalizeEnvironment(environment);
        AuthenticatedUserContext authenticatedUser = requireAuthenticatedUser(principal);
        requireManageAccess(authenticatedUser, spaceCode, normalizedEnvironment);
        ResolvedToolMetaManagementView resolved = toolMetaManagementService
                .getTool(spaceCode, normalizedEnvironment, connectorCode, toolCode)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "tool_not_found"));
        return ResponseEntity.ok(ManagedToolMetaResponse.ok(ManagedToolMetaData.from(resolved)));
    }

    @PutMapping("/{toolCode}")
    public ResponseEntity<ManagedToolMetaResponse> upsertTool(
            @PathVariable String spaceCode,
            @PathVariable String connectorCode,
            @PathVariable String toolCode,
            @RequestParam(value = "environment", required = false) String environment,
            @RequestBody(required = false) ManagedToolMetaRequest request,
            Principal principal) {
        String normalizedEnvironment = normalizeEnvironment(environment);
        AuthenticatedUserContext authenticatedUser = requireAuthenticatedUser(principal);
        requireManageAccess(authenticatedUser, spaceCode, normalizedEnvironment);
        ResolvedToolMetaManagementView resolved = toolMetaManagementService
                .upsertTool(
                        spaceCode,
                        normalizedEnvironment,
                        connectorCode,
                        toolCode,
                        request == null ? ManagedToolMetaRequest.empty().toCommand() : request.toCommand())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "connector_not_found"));
        return ResponseEntity.ok(ManagedToolMetaResponse.ok(ManagedToolMetaData.from(resolved)));
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
