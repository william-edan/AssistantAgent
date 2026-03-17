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
import com.alibaba.assistant.agent.runtime.role.RoleToolScopeFilter;
import org.springframework.beans.factory.annotation.Autowired;
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
 * Lookup service for published business tools.
 */
@Service
public class ArtifactPublicationLookupService {

    private final List<ToolPublicationProvider> toolPublicationProviders;

    private final PublicationScopeResolver publicationScopeResolver;

    private final ToolPublicationProviderSelector toolPublicationProviderSelector;

    @Nullable
    private final RoleToolScopeFilter roleToolScopeFilter;

    @Autowired
    public ArtifactPublicationLookupService(
            List<ToolPublicationProvider> toolPublicationProviders,
            PublicationScopeResolver publicationScopeResolver,
            ToolPublicationProviderSelector toolPublicationProviderSelector,
            @Nullable RoleToolScopeFilter roleToolScopeFilter) {
        this.toolPublicationProviders = toolPublicationProviders != null ? List.copyOf(toolPublicationProviders) : List.of();
        this.publicationScopeResolver = publicationScopeResolver;
        this.toolPublicationProviderSelector = toolPublicationProviderSelector;
        this.roleToolScopeFilter = roleToolScopeFilter;
    }
    /**
     * List user-visible published artifacts.
     */
    public List<PublishedToolDescriptor> listPublishedArtifacts(Map<String, Object> attributes) {
        return listPublishedArtifacts(publicationScopeResolver.resolve(attributes), attributes);
    }

    /**
     * List user-visible published artifacts.
     */
    public List<PublishedToolDescriptor> listPublishedArtifacts(@Nullable ToolContext toolContext) {
        Map<String, Object> attributes = toolContext != null && toolContext.getContext() != null
                ? toolContext.getContext()
                : Map.of();
        return listPublishedArtifacts(publicationScopeResolver.resolve(toolContext), attributes);
    }

    /**
     * Resolve a single published tool. Internal dependency tools remain resolvable.
     */
    public Optional<PublishedToolDescriptor> findPublishedArtifact(String artifactCode, @Nullable ToolContext toolContext) {
        if (!StringUtils.hasText(artifactCode)) {
            return Optional.empty();
        }
        String normalizedCode = artifactCode.trim();
        Map<String, Object> attributes = toolContext != null && toolContext.getContext() != null
                ? toolContext.getContext()
                : Map.of();
        ToolPublicationProvider.PublicationScope scope = publicationScopeResolver.resolve(toolContext);
        List<PublishedToolDescriptor> descriptors = listSelectedPublications(scope, attributes);
        return descriptors.stream()
                .filter(this::isArtifactPublication)
                .filter(descriptor -> matchesArtifactCode(descriptor, normalizedCode))
                .findFirst()
                .or(() -> descriptors.stream()
                        .filter(this::isDirectToolPublication)
                        .filter(descriptor -> matchesDirectToolCode(descriptor, normalizedCode))
                        .findFirst());
    }

    private List<PublishedToolDescriptor> listPublishedArtifacts(
            ToolPublicationProvider.PublicationScope scope,
            Map<String, Object> attributes) {
        List<PublishedToolDescriptor> artifacts = new ArrayList<>();
        for (PublishedToolDescriptor descriptor : listSelectedPublications(scope, attributes)) {
            if (isUserVisibleArtifact(descriptor)) {
                artifacts.add(descriptor);
            }
        }
        return List.copyOf(artifacts);
    }

    private List<PublishedToolDescriptor> listSelectedPublications(
            ToolPublicationProvider.PublicationScope scope,
            Map<String, Object> attributes) {
        List<ToolPublicationProvider> selectedProviders = toolPublicationProviderSelector
                .selectProviders(scope, toolPublicationProviders);
        return listPublications(scope, selectedProviders, attributes);
    }

    private List<PublishedToolDescriptor> listPublications(
            ToolPublicationProvider.PublicationScope scope,
            List<ToolPublicationProvider> providers,
            Map<String, Object> attributes) {
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
        return roleToolScopeFilter != null ? roleToolScopeFilter.filter(attributes, descriptors) : List.copyOf(descriptors);
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

