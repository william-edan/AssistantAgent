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
package com.alibaba.assistant.agent.controlplane.agentapp;

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
 * Typed control-plane facade for agent-app management.
 */
@Service
public class AgentAppManagementService {

    private static final String DEFAULT_ENVIRONMENT = "prod";

    private static final String DEFAULT_STATUS = "active";

    private final PlatformSpaceService platformSpaceService;

    private final AgentAppService agentAppService;

    private final ObjectMapper objectMapper;

    public AgentAppManagementService(
            PlatformSpaceService platformSpaceService,
            AgentAppService agentAppService,
            ObjectMapper objectMapper) {
        this.platformSpaceService = platformSpaceService;
        this.agentAppService = agentAppService;
        this.objectMapper = objectMapper;
    }

    public List<ResolvedAgentAppManagementView> listAgentApps(String spaceCode, String environment) {
        Optional<SpaceResolution> resolution = resolveSpace(spaceCode, environment);
        if (resolution.isEmpty()) {
            return List.of();
        }
        return agentAppService.listActiveBySpace(resolution.get().space().getId()).stream()
                .map(app -> toResolved(resolution.get(), app))
                .toList();
    }

    public Optional<ResolvedAgentAppManagementView> upsertAgentApp(
            String spaceCode,
            String environment,
            String agentAppCode,
            AgentAppUpsertCommand command) {
        if (!StringUtils.hasText(agentAppCode) || command == null) {
            return Optional.empty();
        }
        Optional<SpaceResolution> resolution = resolveSpace(spaceCode, environment);
        if (resolution.isEmpty()) {
            return Optional.empty();
        }
        SpaceResolution spaceResolution = resolution.get();
        Optional<AgentApp> existing = agentAppService.findActiveByCode(spaceResolution.space().getId(), agentAppCode.trim());
        AgentApp target = existing.orElseGet(AgentApp::new);
        LocalDateTime now = LocalDateTime.now();
        if (target.getId() == null) {
            target.setSpaceId(spaceResolution.space().getId());
            target.setAgentAppCode(agentAppCode.trim());
            target.setCreatedAt(now);
        }
        target.setDisplayName(normalize(command.displayName()));
        target.setPromptPolicyJson(serializeJson(command.promptPolicy()));
        target.setMemoryPolicyJson(serializeJson(command.memoryPolicy()));
        target.setApprovalStrategyJson(serializeJson(command.approvalStrategy()));
        target.setStatus(normalizeLower(StringUtils.hasText(command.status()) ? command.status() : DEFAULT_STATUS));
        target.setUpdatedAt(now);

        boolean persisted = target.getId() == null ? agentAppService.save(target) : agentAppService.updateById(target);
        if (!persisted) {
            return Optional.empty();
        }
        return Optional.of(toResolved(spaceResolution, target));
    }

    private Optional<SpaceResolution> resolveSpace(String spaceCode, String environment) {
        String normalizedSpaceCode = normalize(spaceCode);
        String normalizedEnvironment = normalizeEnvironment(environment);
        if (!StringUtils.hasText(normalizedSpaceCode)) {
            return Optional.empty();
        }
        return platformSpaceService.findActiveByCode(normalizedSpaceCode, normalizedEnvironment)
                .map(space -> new SpaceResolution(space, normalizedEnvironment));
    }

    private ResolvedAgentAppManagementView toResolved(SpaceResolution resolution, AgentApp app) {
        return new ResolvedAgentAppManagementView(
                app.getId(),
                resolution.space().getSpaceCode(),
                resolution.environment(),
                app.getAgentAppCode(),
                app.getDisplayName(),
                parseMap(app.getPromptPolicyJson()),
                parseMap(app.getMemoryPolicyJson()),
                parseMap(app.getApprovalStrategyJson()),
                app.getStatus());
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

    private record SpaceResolution(PlatformSpace space, String environment) {
    }
}
