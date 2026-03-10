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

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Lookup service for artifact publications resolved through the provider backbone.
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
     * List artifact publications for prompt/runtime attribute maps.
     */
    public List<PublishedToolDescriptor> listPublishedArtifacts(Map<String, Object> attributes) {
        return listPublishedArtifacts(publicationScopeResolver.resolve(attributes));
    }

    /**
     * List artifact publications for a tool execution context.
     */
    public List<PublishedToolDescriptor> listPublishedArtifacts(@Nullable ToolContext toolContext) {
        return listPublishedArtifacts(publicationScopeResolver.resolve(toolContext));
    }

    /**
     * Resolve a single published artifact by artifact code.
     */
    public Optional<PublishedToolDescriptor> findPublishedArtifact(String artifactCode, @Nullable ToolContext toolContext) {
        if (!StringUtils.hasText(artifactCode)) {
            return Optional.empty();
        }
        String normalizedCode = artifactCode.trim();
        return listPublishedArtifacts(toolContext).stream()
                .filter(descriptor -> matchesArtifactCode(descriptor, normalizedCode))
                .findFirst();
    }

    private List<PublishedToolDescriptor> listPublishedArtifacts(ToolPublicationProvider.PublicationScope scope) {
        List<PublishedToolDescriptor> descriptors = new ArrayList<>();
        List<ToolPublicationProvider> selectedProviders = toolPublicationProviderSelector
                .selectProviders(scope, toolPublicationProviders);
        for (ToolPublicationProvider provider : selectedProviders) {
            if (provider == null) {
                continue;
            }
            for (PublishedToolDescriptor descriptor : provider.listPublishedTools(scope)) {
                if (descriptor != null && descriptor.isArtifactPublication() && descriptor.artifact() != null
                        && StringUtils.hasText(descriptor.artifact().getArtifactCode())) {
                    descriptors.add(descriptor);
                }
            }
        }
        return List.copyOf(descriptors);
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
}
