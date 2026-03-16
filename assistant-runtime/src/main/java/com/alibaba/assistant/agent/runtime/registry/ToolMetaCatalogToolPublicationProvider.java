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

import com.alibaba.assistant.agent.controlplane.toolregistry.ToolMeta;
import com.alibaba.assistant.agent.controlplane.toolregistry.ToolMetaControlPlaneMapper;
import com.alibaba.assistant.agent.controlplane.toolregistry.ToolMetaService;
import com.alibaba.assistant.agent.runtime.compiler.RuntimeArtifact;
import com.alibaba.assistant.agent.runtime.compiler.ToolMetaRuntimeArtifactCompiler;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * 基于 ToolMeta 的主发布源。
 */
@Component
@Profile("migration")
public class ToolMetaCatalogToolPublicationProvider implements ToolPublicationProvider {

    private static final String DEFAULT_TENANT = "default";

    private final ToolMetaService toolMetaService;

    private final ToolMetaRuntimeArtifactCompiler toolMetaRuntimeArtifactCompiler;

    private final ToolMetaControlPlaneMapper toolMetaControlPlaneMapper;

    public ToolMetaCatalogToolPublicationProvider(
            ToolMetaService toolMetaService,
            ToolMetaRuntimeArtifactCompiler toolMetaRuntimeArtifactCompiler,
            ToolMetaControlPlaneMapper toolMetaControlPlaneMapper) {
        this.toolMetaService = toolMetaService;
        this.toolMetaRuntimeArtifactCompiler = toolMetaRuntimeArtifactCompiler;
        this.toolMetaControlPlaneMapper = toolMetaControlPlaneMapper;
    }

    @Override
    public String providerId() {
        return "tool-meta-catalog";
    }

    @Override
    public List<PublishedToolDescriptor> listPublishedTools(PublicationScope scope) {
        String tenantId = scope != null && StringUtils.hasText(scope.tenantId()) ? scope.tenantId() : DEFAULT_TENANT;
        List<ToolMeta> toolMetas = toolMetaService.listEnabledByTenant(tenantId);
        if (toolMetas == null || toolMetas.isEmpty()) {
            return List.of();
        }

        List<PublishedToolDescriptor> descriptors = new ArrayList<>(toolMetas.size());
        for (ToolMeta toolMeta : toolMetas) {
            if (toolMeta == null || !StringUtils.hasText(toolMeta.getToolCode())) {
                continue;
            }
            RuntimeArtifact artifact = toolMetaRuntimeArtifactCompiler.compile(toolMeta);
            descriptors.add(PublishedToolDescriptor.forArtifact(
                    providerId(),
                    "tool:" + toolMeta.getToolCode(),
                    firstNonBlank(toolMeta.getToolName(), artifact.getDisplayName(), toolMeta.getToolCode()),
                    null,
                    toolMeta.getDescription(),
                    false,
                    toolMeta.getSystemCode(),
                    toolMetaControlPlaneMapper.inferToolType(toolMeta),
                    toolMetaControlPlaneMapper.inferVisibility(toolMeta),
                    toolMetaControlPlaneMapper.inferInvocationPolicy(toolMeta),
                    artifact));
        }
        return List.copyOf(descriptors);
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value;
            }
        }
        return null;
    }
}
