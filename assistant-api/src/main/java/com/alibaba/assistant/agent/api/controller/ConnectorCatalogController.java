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

import com.alibaba.assistant.agent.api.controller.dto.ConnectorAuthProfileListData;
import com.alibaba.assistant.agent.api.controller.dto.ConnectorAuthProfileListResponse;
import com.alibaba.assistant.agent.api.controller.dto.ConnectorData;
import com.alibaba.assistant.agent.api.controller.dto.ConnectorResponse;
import com.alibaba.assistant.agent.api.security.AuthenticatedUserContext;
import com.alibaba.assistant.agent.api.security.MigrationControlPlaneAuthorizationService;
import com.alibaba.assistant.agent.controlplane.connector.ConnectorCatalogService;
import com.alibaba.assistant.agent.controlplane.connector.ResolvedConnectorView;
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

/**
 * 连接器目录只读查询入口。
 */
@RestController
@Profile("migration")
@RequestMapping("/api/controlplane/spaces/{spaceCode}/connectors/{connectorCode}")
public class ConnectorCatalogController {

    private static final String DEFAULT_ENVIRONMENT = "prod";

    private final ConnectorCatalogService connectorCatalogService;

    private final MigrationControlPlaneAuthorizationService authorizationService;

    public ConnectorCatalogController(
            ConnectorCatalogService connectorCatalogService,
            MigrationControlPlaneAuthorizationService authorizationService) {
        this.connectorCatalogService = connectorCatalogService;
        this.authorizationService = authorizationService;
    }

    @GetMapping
    public ResponseEntity<ConnectorResponse> getConnector(
            @PathVariable String spaceCode,
            @PathVariable String connectorCode,
            @RequestParam(value = "environment", required = false) String environment,
            Principal principal) {
        String normalizedEnvironment = normalizeEnvironment(environment);
        AuthenticatedUserContext authenticatedUser = requireAuthenticatedUser(principal);
        requireCatalogAccess(authenticatedUser, spaceCode, normalizedEnvironment);
        ResolvedConnectorView resolved = connectorCatalogService.getConnector(spaceCode, normalizedEnvironment, connectorCode)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "connector_not_found"));
        return ResponseEntity.ok(ConnectorResponse.ok(ConnectorData.from(resolved)));
    }

    @GetMapping("/auth-profiles")
    public ResponseEntity<ConnectorAuthProfileListResponse> listAuthProfiles(
            @PathVariable String spaceCode,
            @PathVariable String connectorCode,
            @RequestParam(value = "environment", required = false) String environment,
            Principal principal) {
        String normalizedEnvironment = normalizeEnvironment(environment);
        AuthenticatedUserContext authenticatedUser = requireAuthenticatedUser(principal);
        requireCatalogAccess(authenticatedUser, spaceCode, normalizedEnvironment);
        return ResponseEntity.ok(ConnectorAuthProfileListResponse.ok(
                ConnectorAuthProfileListData.from(
                        spaceCode,
                        normalizedEnvironment,
                        connectorCode,
                        connectorCatalogService.listAuthProfiles(spaceCode, normalizedEnvironment, connectorCode))));
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
