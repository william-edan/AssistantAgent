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

import com.alibaba.assistant.agent.common.tools.CodeactTool;
import com.alibaba.assistant.agent.common.tools.CodeactToolMetadata;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 已发布业务工具查询服务。
 */
@Service
public class ArtifactPublicationLookupService {

    private final List<ToolPublicationProvider> toolPublicationProviders;

    private final PublicationScopeResolver publicationScopeResolver;

    private final ToolPublicationProviderSelector toolPublicationProviderSelector;

    public ArtifactPublicationLookupService(
            List<ToolPublicationProvider> toolPublicationProviders,
            PublicationScopeResolver publicationScopeResolver,
            ToolPublicationProviderSelector toolPublicationProviderSelector) {
        this.toolPublicationProviders = toolPublicationProviders != null ? List.copyOf(toolPublicationProviders) : List.of();
        this.publicationScopeResolver = publicationScopeResolver;
        this.toolPublicationProviderSelector = toolPublicationProviderSelector;
    }

    /**
     * 列出面向用户目录可见的业务工具。
     */
    public List<PublishedToolDescriptor> listPublishedArtifacts(Map<String, Object> attributes) {
        return listPublishedArtifacts(publicationScopeResolver.resolve(attributes));
    }

    /**
     * 列出面向用户目录可见的业务工具。
     */
    public List<PublishedToolDescriptor> listPublishedArtifacts(@Nullable ToolContext toolContext) {
        return listPublishedArtifacts(publicationScopeResolver.resolve(toolContext));
    }

    /**
     * 按工具编码解析单个已发布工具。内部依赖工具也允许被解析。
     */
    public Optional<PublishedToolDescriptor> findPublishedArtifact(String artifactCode, @Nullable ToolContext toolContext) {
        if (!StringUtils.hasText(artifactCode)) {
            return Optional.empty();
        }
        String normalizedCode = artifactCode.trim();
        ToolPublicationProvider.PublicationScope scope = publicationScopeResolver.resolve(toolContext);
        List<PublishedToolDescriptor> descriptors = listSelectedPublications(scope);
        return descriptors.stream()
                .filter(this::isArtifactPublication)
                .filter(descriptor -> matchesArtifactCode(descriptor, normalizedCode))
                .findFirst()
                .or(() -> descriptors.stream()
                        .filter(this::isDirectToolPublication)
                        .filter(descriptor -> matchesDirectToolCode(descriptor, normalizedCode))
                        .findFirst());
    }

    private List<PublishedToolDescriptor> listPublishedArtifacts(ToolPublicationProvider.PublicationScope scope) {
        List<PublishedToolDescriptor> artifacts = new ArrayList<>();
        for (PublishedToolDescriptor descriptor : listSelectedPublications(scope)) {
            if (isUserVisibleArtifact(descriptor)) {
                artifacts.add(descriptor);
            }
        }
        return List.copyOf(artifacts);
    }

    private List<PublishedToolDescriptor> listSelectedPublications(ToolPublicationProvider.PublicationScope scope) {
        List<ToolPublicationProvider> selectedProviders = toolPublicationProviderSelector
                .selectProviders(scope, toolPublicationProviders);
        return listPublications(scope, selectedProviders);
    }

    private List<PublishedToolDescriptor> listPublications(
            ToolPublicationProvider.PublicationScope scope,
            List<ToolPublicationProvider> providers) {
        List<PublishedToolDescriptor> descriptors = new ArrayList<>();
        for (ToolPublicationProvider provider : providers) {
            if (provider == null) {
                continue;
            }
            List<PublishedToolDescriptor> providerDescriptors = provider.listPublishedTools(scope);
            if (providerDescriptors == null || providerDescriptors.isEmpty()) {
                continue;
            }
            for (PublishedToolDescriptor descriptor : providerDescriptors) {
                if (descriptor != null) {
                    descriptors.add(descriptor);
                }
            }
        }
        return List.copyOf(descriptors);
    }

    private boolean isUserVisibleArtifact(PublishedToolDescriptor descriptor) {
        return isArtifactPublication(descriptor) && descriptor.isUserVisible();
    }

    private boolean isArtifactPublication(PublishedToolDescriptor descriptor) {
        return descriptor != null
                && descriptor.isArtifactPublication()
                && descriptor.artifact() != null
                && StringUtils.hasText(descriptor.artifact().getArtifactCode());
    }

    private boolean isDirectToolPublication(PublishedToolDescriptor descriptor) {
        return descriptor != null
                && descriptor.isDirectToolPublication()
                && descriptor.directTool() != null;
    }

    private boolean matchesArtifactCode(PublishedToolDescriptor descriptor, String artifactCode) {
        if (descriptor == null || descriptor.artifact() == null) {
            return false;
        }
        if (artifactCode.equalsIgnoreCase(descriptor.artifact().getArtifactCode())) {
            return true;
        }
        return StringUtils.hasText(descriptor.publicationKey())
                && descriptor.publicationKey().toLowerCase().endsWith(artifactCode.toLowerCase());
    }

    private boolean matchesDirectToolCode(PublishedToolDescriptor descriptor, String artifactCode) {
        if (descriptor == null || descriptor.directTool() == null || !StringUtils.hasText(artifactCode)) {
            return false;
        }
        CodeactTool directTool = descriptor.directTool();
        CodeactToolMetadata metadata = directTool.getCodeactMetadata();
        if (metadata != null) {
            for (String alias : metadata.aliases()) {
                if (StringUtils.hasText(alias) && artifactCode.equalsIgnoreCase(alias.trim())) {
                    return true;
                }
            }
        }
        ToolDefinition definition = directTool.getToolDefinition();
        return definition != null
                && StringUtils.hasText(definition.name())
                && artifactCode.equalsIgnoreCase(definition.name().trim());
    }
}
