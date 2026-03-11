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
package com.alibaba.assistant.agent.controlplane.interaction;

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
 * Typed control-plane facade for interaction-spec management.
 */
@Service
public class InteractionSpecManagementService {

    private static final String DEFAULT_ENVIRONMENT = "prod";

    private static final String DEFAULT_STATUS = "enabled";

    private final PlatformSpaceService platformSpaceService;

    private final InteractionSpecService interactionSpecService;

    private final ObjectMapper objectMapper;

    public InteractionSpecManagementService(
            PlatformSpaceService platformSpaceService,
            InteractionSpecService interactionSpecService,
            ObjectMapper objectMapper) {
        this.platformSpaceService = platformSpaceService;
        this.interactionSpecService = interactionSpecService;
        this.objectMapper = objectMapper;
    }

    public List<ResolvedInteractionSpecManagementView> listInteractions(String spaceCode, String environment) {
        Optional<SpaceResolution> resolution = resolveSpace(spaceCode, environment);
        if (resolution.isEmpty()) {
            return List.of();
        }
        return interactionSpecService.listEnabledBySpace(resolution.get().space().getId()).stream()
                .map(interactionSpec -> toResolved(resolution.get(), interactionSpec))
                .toList();
    }

    public Optional<ResolvedInteractionSpecManagementView> upsertInteraction(
            String spaceCode,
            String environment,
            String interactionCode,
            InteractionSpecUpsertCommand command) {
        if (!StringUtils.hasText(interactionCode) || command == null) {
            return Optional.empty();
        }
        Optional<SpaceResolution> resolution = resolveSpace(spaceCode, environment);
        if (resolution.isEmpty()) {
            return Optional.empty();
        }
        SpaceResolution spaceResolution = resolution.get();
        Optional<InteractionSpec> existing = interactionSpecService.findLatestEnabledByCode(
                spaceResolution.space().getId(), interactionCode.trim());
        InteractionSpec target = existing.orElseGet(InteractionSpec::new);
        LocalDateTime now = LocalDateTime.now();
        if (target.getId() == null) {
            target.setSpaceId(spaceResolution.space().getId());
            target.setInteractionCode(interactionCode.trim());
            target.setCreatedAt(now);
            target.setVersion(1);
        }
        else {
            target.setVersion(Math.max(1, target.getVersion() == null ? 1 : target.getVersion() + 1));
        }
        target.setSlotSchemaJson(serializeJson(command.slotSchema()));
        target.setAskStrategyJson(serializeJson(command.askStrategy()));
        target.setAutoFillRulesJson(serializeJson(command.autoFillRules()));
        target.setSummaryLayoutJson(serializeJson(command.summaryLayout()));
        target.setConfirmationPolicyJson(serializeJson(command.confirmationPolicy()));
        target.setEditPolicyJson(serializeJson(command.editPolicy()));
        target.setStatus(normalizeLower(StringUtils.hasText(command.status()) ? command.status() : DEFAULT_STATUS));
        target.setUpdatedAt(now);

        boolean persisted = target.getId() == null ? interactionSpecService.save(target) : interactionSpecService.updateById(target);
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

    private ResolvedInteractionSpecManagementView toResolved(SpaceResolution resolution, InteractionSpec interactionSpec) {
        return new ResolvedInteractionSpecManagementView(
                interactionSpec.getId(),
                resolution.space().getSpaceCode(),
                resolution.environment(),
                interactionSpec.getInteractionCode(),
                parseMap(interactionSpec.getSlotSchemaJson()),
                parseMap(interactionSpec.getAskStrategyJson()),
                parseMap(interactionSpec.getAutoFillRulesJson()),
                parseMap(interactionSpec.getSummaryLayoutJson()),
                parseMap(interactionSpec.getConfirmationPolicyJson()),
                parseMap(interactionSpec.getEditPolicyJson()),
                interactionSpec.getStatus());
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
        if (value instanceof List<?> list && list.isEmpty()) {
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
