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
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Typed control-plane facade for auth-profile management.
 */
@Service
public class AuthProfileManagementService {

    private static final String DEFAULT_ENVIRONMENT = "prod";

    private static final String DEFAULT_STATUS = "active";

    private final PlatformSpaceService platformSpaceService;

    private final ConnectorService connectorService;

    private final AuthProfileService authProfileService;

    private final ObjectMapper objectMapper;

    public AuthProfileManagementService(
            PlatformSpaceService platformSpaceService,
            ConnectorService connectorService,
            AuthProfileService authProfileService,
            ObjectMapper objectMapper) {
        this.platformSpaceService = platformSpaceService;
        this.connectorService = connectorService;
        this.authProfileService = authProfileService;
        this.objectMapper = objectMapper;
    }

    public List<ResolvedAuthProfileManagementView> listAuthProfiles(String spaceCode, String environment, String connectorCode) {
        Optional<ConnectorResolution> resolution = resolveConnector(spaceCode, environment, connectorCode);
        if (resolution.isEmpty()) {
            return List.of();
        }
        return authProfileService.listActiveByConnector(resolution.get().connector().getId()).stream()
                .map(profile -> toResolved(resolution.get(), profile))
                .toList();
    }

    public Optional<ResolvedAuthProfileManagementView> upsertAuthProfile(
            String spaceCode,
            String environment,
            String connectorCode,
            String authProfileCode,
            AuthProfileUpsertCommand command) {
        if (!StringUtils.hasText(authProfileCode) || command == null) {
            return Optional.empty();
        }
        Optional<ConnectorResolution> resolution = resolveConnector(spaceCode, environment, connectorCode);
        if (resolution.isEmpty()) {
            return Optional.empty();
        }
        ConnectorResolution connectorResolution = resolution.get();
        Optional<AuthProfile> existing = authProfileService.findLatestActiveByCode(
                connectorResolution.connector().getId(), authProfileCode.trim());
        AuthProfile target = existing.orElseGet(AuthProfile::new);
        LocalDateTime now = LocalDateTime.now();
        if (target.getId() == null) {
            target.setSpaceId(connectorResolution.space().getId());
            target.setConnectorId(connectorResolution.connector().getId());
            target.setAuthProfileCode(authProfileCode.trim());
            target.setCreatedAt(now);
        }
        target.setAuthType(normalizeLower(command.authType()));
        target.setUsagePolicy(normalizeLower(command.usagePolicy()));
        target.setTokenEndpoint(normalize(command.tokenEndpoint()));
        target.setTokenHeaderName(normalize(command.tokenHeaderName()));
        target.setTokenHeaderPrefix(command.tokenHeaderPrefix());
        target.setAudience(normalize(command.audience()));
        target.setScopesJson(serializeJson(command.scopes()));
        target.setCredentialRef(normalize(command.credentialRef()));
        target.setRefreshPolicyJson(serializeJson(command.refreshPolicy()));
        target.setStatus(normalizeLower(StringUtils.hasText(command.status()) ? command.status() : DEFAULT_STATUS));
        target.setUpdatedAt(now);

        boolean persisted = target.getId() == null ? authProfileService.save(target) : authProfileService.updateById(target);
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
        return connectorService.findLatestActiveByCodeAndEnvironment(space.get().getId(), normalizedEnvironment, normalizedConnectorCode)
                .map(connector -> new ConnectorResolution(space.get(), connector, normalizedEnvironment));
    }

    private ResolvedAuthProfileManagementView toResolved(ConnectorResolution resolution, AuthProfile profile) {
        return new ResolvedAuthProfileManagementView(
                profile.getId(),
                resolution.space().getSpaceCode(),
                resolution.environment(),
                resolution.connector().getConnectorCode(),
                profile.getAuthProfileCode(),
                profile.getAuthType(),
                profile.getUsagePolicy(),
                profile.getTokenEndpoint(),
                profile.getTokenHeaderName(),
                profile.getTokenHeaderPrefix(),
                profile.getAudience(),
                parseList(profile.getScopesJson()),
                profile.getCredentialRef(),
                parseMap(profile.getRefreshPolicyJson()),
                profile.getStatus());
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
