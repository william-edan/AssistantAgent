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
package com.alibaba.assistant.agent.controlplane.connector;

import com.alibaba.assistant.agent.controlplane.space.PlatformSpace;
import com.alibaba.assistant.agent.controlplane.space.PlatformSpaceService;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Optional;

/**
 * Read facade for connector and auth-profile catalog views.
 */
@Service
public class ConnectorCatalogService {

    private static final String DEFAULT_ENVIRONMENT = "prod";

    private final PlatformSpaceService platformSpaceService;

    private final ConnectorService connectorService;

    private final AuthProfileService authProfileService;

    public ConnectorCatalogService(
            PlatformSpaceService platformSpaceService,
            ConnectorService connectorService,
            AuthProfileService authProfileService) {
        this.platformSpaceService = platformSpaceService;
        this.connectorService = connectorService;
        this.authProfileService = authProfileService;
    }

    public Optional<ResolvedConnectorView> getConnector(String spaceCode, String environment, String connectorCode) {
        if (!StringUtils.hasText(spaceCode) || !StringUtils.hasText(connectorCode)) {
            return Optional.empty();
        }
        String normalizedEnvironment = normalizeEnvironment(environment);
        Optional<PlatformSpace> space = platformSpaceService.findActiveByCode(spaceCode.trim(), normalizedEnvironment);
        if (space.isEmpty()) {
            return Optional.empty();
        }
        return connectorService.findLatestActiveByCode(space.get().getId(), connectorCode.trim())
                .filter(connector -> matchesEnvironment(normalizedEnvironment, connector.getEnvironment()))
                .map(connector -> new ResolvedConnectorView(
                        connector.getId(),
                        space.get().getSpaceCode(),
                        StringUtils.hasText(connector.getEnvironment()) ? connector.getEnvironment() : normalizedEnvironment,
                        connector.getConnectorCode(),
                        connector.getSystemCode(),
                        connector.getDisplayName(),
                        connector.getProtocolType(),
                        connector.getBaseUrl(),
                        connector.getStatus(),
                        connector.getVersion()));
    }

    public List<ResolvedAuthProfileView> listAuthProfiles(String spaceCode, String environment, String connectorCode) {
        Optional<ResolvedConnectorView> connector = getConnector(spaceCode, environment, connectorCode);
        if (connector.isEmpty()) {
            return List.of();
        }
        return authProfileService.listActiveByConnector(connector.get().connectorId()).stream()
                .map(profile -> new ResolvedAuthProfileView(
                        profile.getId(),
                        connector.get().spaceCode(),
                        connector.get().environment(),
                        connector.get().connectorCode(),
                        profile.getAuthProfileCode(),
                        profile.getAuthType(),
                        profile.getUsagePolicy(),
                        profile.getTokenHeaderName(),
                        profile.getTokenHeaderPrefix(),
                        profile.getStatus()))
                .toList();
    }

    private boolean matchesEnvironment(String requestedEnvironment, String connectorEnvironment) {
        if (!StringUtils.hasText(connectorEnvironment)) {
            return true;
        }
        return connectorEnvironment.trim().equalsIgnoreCase(requestedEnvironment);
    }

    private String normalizeEnvironment(String environment) {
        return StringUtils.hasText(environment) ? environment.trim() : DEFAULT_ENVIRONMENT;
    }
}
