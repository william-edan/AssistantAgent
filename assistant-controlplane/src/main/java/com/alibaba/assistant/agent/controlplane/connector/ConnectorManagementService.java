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
import com.alibaba.assistant.agent.controlplane.support.ManagementKeywordMatcher;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Typed control-plane write/read facade for connector management.
 */
@Service
public class ConnectorManagementService {

    private static final String DEFAULT_ENVIRONMENT = "prod";

    private static final String DEFAULT_STATUS = "active";

    private final PlatformSpaceService platformSpaceService;

    private final ConnectorService connectorService;

    public ConnectorManagementService(
            PlatformSpaceService platformSpaceService,
            ConnectorService connectorService) {
        this.platformSpaceService = platformSpaceService;
        this.connectorService = connectorService;
    }

    public List<ResolvedConnectorView> listConnectors(String spaceCode, String environment, String keyword) {
        String normalizedEnvironment = normalizeEnvironment(environment);
        Optional<PlatformSpace> space = platformSpaceService.findActiveByCode(normalize(spaceCode), normalizedEnvironment);
        if (space.isEmpty()) {
            return List.of();
        }
        String normalizedKeyword = ManagementKeywordMatcher.normalizeKeyword(keyword);
        return connectorService.listLatestActiveBySpace(space.get().getId(), normalizedEnvironment).stream()
                .map(connector -> toResolved(space.get().getSpaceCode(), normalizedEnvironment, connector))
                .filter(view -> ManagementKeywordMatcher.matches(
                        normalizedKeyword,
                        view.connectorCode(),
                        view.displayName(),
                        view.systemCode()))
                .toList();
    }

    public Optional<ResolvedConnectorView> getConnector(String spaceCode, String environment, String connectorCode) {
        String normalizedEnvironment = normalizeEnvironment(environment);
        String normalizedConnectorCode = normalize(connectorCode);
        if (!StringUtils.hasText(normalizedConnectorCode)) {
            return Optional.empty();
        }
        Optional<PlatformSpace> space = platformSpaceService.findActiveByCode(normalize(spaceCode), normalizedEnvironment);
        if (space.isEmpty()) {
            return Optional.empty();
        }
        return connectorService.findLatestActiveByCodeAndEnvironment(space.get().getId(), normalizedEnvironment, normalizedConnectorCode)
                .map(connector -> toResolved(space.get().getSpaceCode(), normalizedEnvironment, connector));
    }
    public Optional<ResolvedConnectorView> upsertConnector(
            String spaceCode,
            String environment,
            String connectorCode,
            ConnectorUpsertCommand command) {
        String normalizedSpaceCode = normalize(spaceCode);
        String normalizedEnvironment = normalizeEnvironment(environment);
        String normalizedConnectorCode = normalize(connectorCode);
        if (!StringUtils.hasText(normalizedSpaceCode) || !StringUtils.hasText(normalizedConnectorCode) || command == null) {
            return Optional.empty();
        }
        Optional<PlatformSpace> space = platformSpaceService.findActiveByCode(normalizedSpaceCode, normalizedEnvironment);
        if (space.isEmpty()) {
            return Optional.empty();
        }
        Optional<Connector> existing = connectorService.findLatestActiveByCodeAndEnvironment(
                space.get().getId(), normalizedEnvironment, normalizedConnectorCode);
        Connector target = existing.orElseGet(Connector::new);
        LocalDateTime now = LocalDateTime.now();
        if (target.getId() == null) {
            target.setSpaceId(space.get().getId());
            target.setConnectorCode(normalizedConnectorCode);
            target.setEnvironment(normalizedEnvironment);
            target.setCreatedAt(now);
            target.setVersion(1);
        }
        else {
            target.setVersion(Math.max(1, target.getVersion() == null ? 1 : target.getVersion() + 1));
        }
        target.setSystemCode(normalize(command.systemCode()));
        target.setDisplayName(normalize(command.displayName()));
        target.setProtocolType(normalizeLower(command.protocolType()));
        target.setNetworkZone(normalizeLower(command.networkZone()));
        target.setBaseUrl(normalize(command.baseUrl()));
        target.setStatus(normalizeLower(StringUtils.hasText(command.status()) ? command.status() : DEFAULT_STATUS));
        target.setUpdatedAt(now);

        boolean persisted = target.getId() == null ? connectorService.save(target) : connectorService.updateById(target);
        if (!persisted) {
            return Optional.empty();
        }
        return Optional.of(toResolved(space.get().getSpaceCode(), normalizedEnvironment, target));
    }

    private ResolvedConnectorView toResolved(String spaceCode, String environment, Connector connector) {
        return new ResolvedConnectorView(
                connector.getId(),
                spaceCode,
                environment,
                connector.getConnectorCode(),
                connector.getSystemCode(),
                connector.getDisplayName(),
                connector.getProtocolType(),
                connector.getNetworkZone(),
                connector.getBaseUrl(),
                connector.getStatus(),
                connector.getVersion());
    }

    private String normalizeEnvironment(String environment) {
        return StringUtils.hasText(environment) ? environment.trim() : DEFAULT_ENVIRONMENT;
    }

    private String normalize(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private String normalizeLower(String value) {
        return StringUtils.hasText(value) ? value.trim().toLowerCase() : null;
    }
}




