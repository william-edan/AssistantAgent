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
package com.alibaba.assistant.agent.controlplane.query;

import com.alibaba.assistant.agent.controlplane.connector.Connector;
import com.alibaba.assistant.agent.controlplane.connector.ConnectorService;
import com.alibaba.assistant.agent.controlplane.space.PlatformSpace;
import com.alibaba.assistant.agent.controlplane.space.PlatformSpaceService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Typed control-plane facade for reference-resolver management.
 */
@Service
public class ReferenceResolverManagementService {

    private static final String DEFAULT_ENVIRONMENT = "prod";

    private static final String DEFAULT_STATUS = "enabled";

    private final PlatformSpaceService platformSpaceService;

    private final ConnectorService connectorService;

    private final ReferenceResolverService referenceResolverService;

    private final ObjectMapper objectMapper;

    public ReferenceResolverManagementService(
            PlatformSpaceService platformSpaceService,
            ConnectorService connectorService,
            ReferenceResolverService referenceResolverService,
            ObjectMapper objectMapper) {
        this.platformSpaceService = platformSpaceService;
        this.connectorService = connectorService;
        this.referenceResolverService = referenceResolverService;
        this.objectMapper = objectMapper;
    }

    public List<ResolvedReferenceResolverManagementView> listResolvers(
            String spaceCode,
            String environment,
            String connectorCode) {
        Optional<ConnectorResolution> resolution = resolveConnector(spaceCode, environment, connectorCode);
        if (resolution.isEmpty()) {
            return List.of();
        }
        return referenceResolverService.listEnabledByConnector(resolution.get().connector().getId()).stream()
                .map(resolver -> toResolved(resolution.get(), resolver))
                .toList();
    }

    public Optional<ResolvedReferenceResolverManagementView> upsertResolver(
            String spaceCode,
            String environment,
            String connectorCode,
            String resolverCode,
            ReferenceResolverUpsertCommand command) {
        if (!StringUtils.hasText(resolverCode) || command == null) {
            return Optional.empty();
        }
        Optional<ConnectorResolution> resolution = resolveConnector(spaceCode, environment, connectorCode);
        if (resolution.isEmpty()) {
            return Optional.empty();
        }
        ConnectorResolution connectorResolution = resolution.get();
        Optional<ReferenceResolver> existing = referenceResolverService.findLatestEnabledByCode(
                connectorResolution.space().getId(), resolverCode.trim());
        ReferenceResolver target = existing.orElseGet(ReferenceResolver::new);
        LocalDateTime now = LocalDateTime.now();
        if (target.getId() == null) {
            target.setSpaceId(connectorResolution.space().getId());
            target.setConnectorId(connectorResolution.connector().getId());
            target.setResolverCode(resolverCode.trim());
            target.setCreatedAt(now);
            target.setVersion(1);
        }
        else {
            target.setVersion(Math.max(1, target.getVersion() == null ? 1 : target.getVersion() + 1));
        }
        target.setOperationBindingJson(serializeJson(command.operationBinding()));
        target.setAllowedAuthProfilesJson(serializeJson(command.allowedAuthProfiles()));
        target.setInputSchemaJson(serializeJson(command.inputSchema()));
        target.setOutputSchemaJson(serializeJson(command.outputSchema()));
        target.setCachePolicyJson(serializeJson(command.cachePolicy()));
        target.setStalenessPolicyJson(serializeJson(command.stalenessPolicy()));
        target.setVisibility(normalizeLower(command.visibility()));
        target.setStatus(normalizeLower(StringUtils.hasText(command.status()) ? command.status() : DEFAULT_STATUS));
        target.setUpdatedAt(now);

        boolean persisted = target.getId() == null
                ? referenceResolverService.save(target)
                : referenceResolverService.updateById(target);
        if (!persisted) {
            return Optional.empty();
        }
        return Optional.of(toResolved(connectorResolution, target));
    }

    private Optional<ConnectorResolution> resolveConnector(String spaceCode, String environment, String connectorCode) {
        String normalizedSpaceCode = normalize(spaceCode);
        String normalizedEnvironment = normalizeEnvironment(environment);
        String normalizedConnectorCode = normalize(connectorCode);
        if (!StringUtils.hasText(normalizedSpaceCode) || !StringUtils.hasText(normalizedConnectorCode)) {
            return Optional.empty();
        }
        Optional<PlatformSpace> space = platformSpaceService.findActiveByCode(normalizedSpaceCode, normalizedEnvironment);
        if (space.isEmpty()) {
            return Optional.empty();
        }
        return connectorService.findLatestActiveByCodeAndEnvironment(
                        space.get().getId(), normalizedEnvironment, normalizedConnectorCode)
                .map(connector -> new ConnectorResolution(space.get(), connector, normalizedEnvironment));
    }

    private ResolvedReferenceResolverManagementView toResolved(
            ConnectorResolution resolution,
            ReferenceResolver resolver) {
        return new ResolvedReferenceResolverManagementView(
                resolver.getId(),
                resolution.space().getSpaceCode(),
                resolution.environment(),
                resolution.connector().getConnectorCode(),
                resolver.getResolverCode(),
                parseMap(resolver.getOperationBindingJson()),
                parseList(resolver.getAllowedAuthProfilesJson()),
                parseMap(resolver.getInputSchemaJson()),
                parseMap(resolver.getOutputSchemaJson()),
                parseMap(resolver.getCachePolicyJson()),
                parseMap(resolver.getStalenessPolicyJson()),
                resolver.getVisibility(),
                resolver.getStatus());
    }

    private List<String> parseList(String json) {
        if (!StringUtils.hasText(json)) {
            return List.of();
        }
        try {
            List<String> parsed = objectMapper.readValue(json, new TypeReference<List<String>>() {
            });
            return parsed != null ? parsed : List.of();
        }
        catch (Exception ignored) {
            return List.of();
        }
    }

    private Map<String, Object> parseMap(String json) {
        if (!StringUtils.hasText(json)) {
            return Map.of();
        }
        try {
            Map<String, Object> parsed = objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {
            });
            return parsed != null ? parsed : Map.of();
        }
        catch (Exception ignored) {
            return Map.of();
        }
    }

    private String serializeJson(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof List<?> list && list.isEmpty()) {
            return null;
        }
        if (value instanceof Map<?, ?> map && map.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        }
        catch (Exception ignored) {
            return null;
        }
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

    private record ConnectorResolution(PlatformSpace space, Connector connector, String environment) {
    }
}
