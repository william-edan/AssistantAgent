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
package com.alibaba.assistant.agent.runtime.registry;

import com.alibaba.assistant.agent.controlplane.connector.Connector;
import com.alibaba.assistant.agent.controlplane.connector.ConnectorService;
import com.alibaba.assistant.agent.runtime.compiler.RuntimeArtifact;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * Mainline publication provider backed by the runtime artifact catalog.
 */
@Component
public class ArtifactCatalogToolPublicationProvider implements ToolPublicationProvider {

    private final RuntimeArtifactCatalogService runtimeArtifactCatalogService;

    private final ConnectorService connectorService;

    public ArtifactCatalogToolPublicationProvider(
            RuntimeArtifactCatalogService runtimeArtifactCatalogService,
            ConnectorService connectorService) {
        this.runtimeArtifactCatalogService = runtimeArtifactCatalogService;
        this.connectorService = connectorService;
    }

    @Override
    public String providerId() {
        return "artifact-catalog";
    }

    @Override
    public List<PublishedToolDescriptor> listPublishedTools(PublicationScope scope) {
        if (scope == null || scope.spaceId() == null || !StringUtils.hasText(scope.agentAppCode())) {
            return List.of();
        }
        List<RuntimeArtifact> artifacts = runtimeArtifactCatalogService.listGrantedArtifacts(scope.spaceId(), scope.agentAppCode());
        List<PublishedToolDescriptor> descriptors = new ArrayList<>(artifacts.size());
        for (RuntimeArtifact artifact : artifacts) {
            if (artifact == null) {
                continue;
            }
            descriptors.add(PublishedToolDescriptor.forArtifact(
                    providerId(),
                    artifact.getArtifactType().name().toLowerCase() + ":" + artifact.getArtifactCode(),
                    artifact.getDisplayName(),
                    null,
                    null,
                    false,
                    resolveExecutionSystemCode(artifact),
                    artifact));
        }
        return List.copyOf(descriptors);
    }

    private String resolveExecutionSystemCode(RuntimeArtifact artifact) {
        if (artifact == null) {
            return null;
        }
        Long connectorId = artifact.getSteps().values().stream()
                .filter(step -> step != null && step.connectorId() != null)
                .map(RuntimeArtifact.StepBinding::connectorId)
                .findFirst()
                .orElseGet(() -> artifact.getActions().values().stream()
                        .filter(action -> action != null && action.connectorId() != null)
                        .map(RuntimeArtifact.ActionBinding::connectorId)
                        .findFirst()
                        .orElse(null));
        if (connectorId == null) {
            return null;
        }
        Connector connector = connectorService.getById(connectorId);
        return connector != null ? connector.getSystemCode() : null;
    }
}
