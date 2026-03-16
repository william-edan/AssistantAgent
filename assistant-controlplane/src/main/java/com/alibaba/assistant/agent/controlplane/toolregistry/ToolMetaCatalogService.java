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
package com.alibaba.assistant.agent.controlplane.toolregistry;

import com.alibaba.assistant.agent.controlplane.connector.Connector;
import com.alibaba.assistant.agent.controlplane.connector.ConnectorService;
import com.alibaba.assistant.agent.controlplane.space.PlatformSpace;
import com.alibaba.assistant.agent.controlplane.space.PlatformSpaceService;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Read facade for canonical tool catalog pages.
 */
@Service
public class ToolMetaCatalogService {

    private static final String DEFAULT_ENVIRONMENT = "prod";
    private static final String DEFAULT_TENANT = "default";
    private static final String STATUS_ACTIVE = "active";

    private final PlatformSpaceService platformSpaceService;
    private final ConnectorService connectorService;
    private final ToolMetaService toolMetaService;
    private final ToolMetaControlPlaneMapper mapper;

    public ToolMetaCatalogService(
            PlatformSpaceService platformSpaceService,
            ConnectorService connectorService,
            ToolMetaService toolMetaService,
            ToolMetaControlPlaneMapper mapper) {
        this.platformSpaceService = platformSpaceService;
        this.connectorService = connectorService;
        this.toolMetaService = toolMetaService;
        this.mapper = mapper;
    }

    public Optional<ResolvedToolMetaDetailView> getToolDetail(String spaceCode, String environment, String toolCode) {
        if (!StringUtils.hasText(spaceCode) || !StringUtils.hasText(toolCode)) {
            return Optional.empty();
        }
        String normalizedEnvironment = normalizeEnvironment(environment);
        Optional<PlatformSpace> space = platformSpaceService.findActiveByCode(spaceCode.trim(), normalizedEnvironment);
        if (space.isEmpty()) {
            return Optional.empty();
        }
        Set<String> systemCodes = connectorSystems(space.get().getId(), normalizedEnvironment);
        if (systemCodes.isEmpty()) {
            return Optional.empty();
        }
        return toolMetaService.findLatestEnabledByToolCode(DEFAULT_TENANT, toolCode.trim())
                .filter(toolMeta -> StringUtils.hasText(toolMeta.getSystemCode()) && systemCodes.contains(toolMeta.getSystemCode().trim()))
                .map(toolMeta -> mapper.toDetailView(space.get().getSpaceCode(), normalizedEnvironment, toolMeta));
    }

    public List<ResolvedToolMetaSummaryView> listSpaceTools(Long spaceId, String environment, String keyword) {
        Set<String> systemCodes = connectorSystems(spaceId, normalizeEnvironment(environment));
        if (systemCodes.isEmpty()) {
            return List.of();
        }
        return toolMetaService.listEnabledByTenant(DEFAULT_TENANT).stream()
                .filter(toolMeta -> StringUtils.hasText(toolMeta.getSystemCode()) && systemCodes.contains(toolMeta.getSystemCode().trim()))
                .filter(toolMeta -> mapper.matchesKeyword(toolMeta, keyword))
                .map(mapper::toSummaryView)
                .toList();
    }

    private Set<String> connectorSystems(Long spaceId, String environment) {
        if (spaceId == null) {
            return Set.of();
        }
        return connectorService.lambdaQuery()
                .eq(Connector::getSpaceId, spaceId)
                .eq(Connector::getStatus, STATUS_ACTIVE)
                .list()
                .stream()
                .filter(connector -> !StringUtils.hasText(connector.getEnvironment())
                        || connector.getEnvironment().trim().equalsIgnoreCase(environment))
                .map(Connector::getSystemCode)
                .filter(StringUtils::hasText)
                .map(String::trim)
                .collect(Collectors.toSet());
    }

    private String normalizeEnvironment(String environment) {
        return StringUtils.hasText(environment) ? environment.trim() : DEFAULT_ENVIRONMENT;
    }
}
