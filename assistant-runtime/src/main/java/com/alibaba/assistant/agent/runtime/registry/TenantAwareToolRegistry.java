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

import com.alibaba.assistant.agent.common.enums.Language;
import com.alibaba.assistant.agent.common.tools.CodeactTool;
import com.alibaba.assistant.agent.common.tools.definition.CodeactToolDefinition;
import com.alibaba.assistant.agent.common.tools.definition.ReturnSchema;
import com.alibaba.assistant.agent.core.tool.CodeactToolRegistry;
import com.alibaba.assistant.agent.core.tool.DefaultCodeactToolRegistry;
import com.alibaba.assistant.agent.core.tool.ToolContextScopedCodeactToolRegistry;
import com.alibaba.assistant.agent.core.tool.schema.ReturnSchemaRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tenant-aware CodeactToolRegistry with snapshot cache.
 * Uses provider-driven publication snapshots and supports event-driven invalidation.
 *
 * @author Assistant Agent Team
 * @since 1.0.0
 */
@Component
@Profile("migration")
public class TenantAwareToolRegistry implements ToolContextScopedCodeactToolRegistry {

    private static final Logger logger = LoggerFactory.getLogger(TenantAwareToolRegistry.class);

    private static final Duration SNAPSHOT_TTL = Duration.ofMinutes(5);

    private final List<ToolPublicationProvider> publicationProviders;

    private final ToolPublicationMaterializer toolPublicationMaterializer;

    private final PublicationScopeResolver publicationScopeResolver;

    private final ToolPublicationProviderSelector toolPublicationProviderSelector;

    private final Map<String, SnapshotEntry> snapshotCache = new ConcurrentHashMap<>();

    private volatile DefaultCodeactToolRegistry delegate;

    public TenantAwareToolRegistry(
            List<ToolPublicationProvider> publicationProviders,
            ToolPublicationMaterializer toolPublicationMaterializer,
            PublicationScopeResolver publicationScopeResolver,
            ToolPublicationProviderSelector toolPublicationProviderSelector) {
        this.publicationProviders = publicationProviders != null ? List.copyOf(publicationProviders) : List.of();
        this.toolPublicationMaterializer = toolPublicationMaterializer;
        this.publicationScopeResolver = publicationScopeResolver;
        this.toolPublicationProviderSelector = toolPublicationProviderSelector;
        this.delegate = createSessionRegistry("default");
    }

    TenantAwareToolRegistry(
            List<ToolPublicationProvider> publicationProviders,
            ToolPublicationMaterializer toolPublicationMaterializer,
            PublicationScopeResolver publicationScopeResolver) {
        this(publicationProviders, toolPublicationMaterializer, publicationScopeResolver,
                new ToolPublicationProviderSelector());
    }

    TenantAwareToolRegistry(
            List<ToolPublicationProvider> publicationProviders,
            ToolPublicationMaterializer toolPublicationMaterializer) {
        this(publicationProviders, toolPublicationMaterializer, null, new ToolPublicationProviderSelector());
    }

    /**
     * Build tenant-scoped immutable snapshot registry.
     */
    public DefaultCodeactToolRegistry createSessionRegistry(String tenantId) {
        return createSessionRegistry(new ToolPublicationProvider.PublicationScope(normalizeTenant(tenantId), null, null, null));
    }

    /**
     * Build scoped immutable snapshot registry.
     */
    public DefaultCodeactToolRegistry createSessionRegistry(ToolPublicationProvider.PublicationScope scope) {
        ToolPublicationProvider.PublicationScope effectiveScope = normalizeScope(scope);
        SnapshotEntry snapshot = resolveSnapshot(effectiveScope);

        DefaultCodeactToolRegistry registry = new DefaultCodeactToolRegistry();
        for (CodeactTool tool : snapshot.tools()) {
            registry.register(tool);
        }
        return registry;
    }

    public List<CodeactTool> getReactAccessibleTools() {
        return resolveSnapshot(new ToolPublicationProvider.PublicationScope("default", null, null, null))
                .reactAccessibleTools();
    }

    @Override
    public CodeactToolRegistry scope(ToolContext toolContext) {
        if (publicationScopeResolver == null) {
            return this;
        }
        return createSessionRegistry(publicationScopeResolver.resolve(toolContext));
    }

    @EventListener
    public void onToolPublished(ToolPublishedEvent event) {
        String tenantId = normalizeTenant(event != null ? event.tenantId() : null);
        snapshotCache.keySet().removeIf(key -> key.startsWith(tenantId + "|"));
        if ("default".equals(tenantId)) {
            delegate = createSessionRegistry("default");
        }
        logger.info("TenantAwareToolRegistry#onToolPublished - tenantId={} cache invalidated", tenantId);
    }

    @Override
    public void register(CodeactTool tool) {
        delegate.register(tool);
    }

    @Override
    public Optional<CodeactTool> getTool(String name) {
        return delegate.getTool(name);
    }

    @Override
    public Optional<CodeactTool> getToolByAlias(String alias) {
        return delegate.getToolByAlias(alias);
    }

    @Override
    public List<CodeactTool> getAllTools() {
        return delegate.getAllTools();
    }

    @Override
    public List<CodeactTool> getToolsForLanguage(Language language) {
        return delegate.getToolsForLanguage(language);
    }

    @Override
    public Optional<CodeactToolDefinition> getToolDefinition(String toolName) {
        return delegate.getToolDefinition(toolName);
    }

    @Override
    public Optional<ReturnSchema> getReturnSchema(String toolName) {
        return delegate.getReturnSchema(toolName);
    }

    @Override
    public String generateStructuredToolPrompt(Language language) {
        return delegate.generateStructuredToolPrompt(language);
    }

    @Override
    @Deprecated
    public String generateToolDescriptionPrompt(Language language) {
        return delegate.generateToolDescriptionPrompt(language);
    }

    @Override
    public ReturnSchemaRegistry getReturnSchemaRegistry() {
        return delegate.getReturnSchemaRegistry();
    }

    private SnapshotEntry resolveSnapshot(ToolPublicationProvider.PublicationScope scope) {
        ToolPublicationProvider.PublicationScope effectiveScope = normalizeScope(scope);
        String cacheKey = cacheKey(effectiveScope);
        return snapshotCache.compute(cacheKey, (key, existing) -> {
            if (existing != null && !existing.isExpired()) {
                return existing;
            }
            List<PublishedToolDescriptor> descriptors = new ArrayList<>();
            List<ToolPublicationProvider> selectedProviders = toolPublicationProviderSelector
                    .selectProviders(effectiveScope, publicationProviders);
            for (ToolPublicationProvider provider : selectedProviders) {
                if (provider == null) {
                    continue;
                }
                descriptors.addAll(provider.listPublishedTools(effectiveScope));
            }
            List<CodeactTool> tools = toolPublicationMaterializer.materialize(descriptors);
            return new SnapshotEntry(Instant.now(), descriptors, tools);
        });
    }

    private static boolean isReactAccessiblePublication(PublishedToolDescriptor descriptor) {
        return descriptor != null
                && descriptor.isDirectToolPublication()
                && descriptor.directTool() != null
                && !"legacy-bridge".equalsIgnoreCase(descriptor.sourceType());
    }

    private ToolPublicationProvider.PublicationScope normalizeScope(ToolPublicationProvider.PublicationScope scope) {
        if (scope == null) {
            return new ToolPublicationProvider.PublicationScope("default", null, null, null);
        }
        return new ToolPublicationProvider.PublicationScope(
                normalizeTenant(scope.tenantId()),
                scope.spaceId(),
                StringUtils.hasText(scope.environment()) ? scope.environment().trim() : null,
                StringUtils.hasText(scope.agentAppCode()) ? scope.agentAppCode().trim() : null,
                scope.sourceSelectionMode(),
                scope.requestedSourceIds(),
                scope.blockedSourceIds());
    }

    private String normalizeTenant(String tenantId) {
        return StringUtils.hasText(tenantId) ? tenantId : "default";
    }

    private String cacheKey(ToolPublicationProvider.PublicationScope scope) {
        return normalizeTenant(scope.tenantId()) + "|"
                + Objects.toString(scope.spaceId(), "_") + "|"
                + Objects.toString(scope.environment(), "_") + "|"
                + Objects.toString(scope.agentAppCode(), "_") + "|"
                + scope.sourceSelectionMode().name() + "|"
                + String.join(",", scope.requestedSourceIds()) + "|"
                + String.join(",", scope.blockedSourceIds());
    }

    private record SnapshotEntry(Instant loadedAt, List<PublishedToolDescriptor> descriptors, List<CodeactTool> tools) {
        private SnapshotEntry(Instant loadedAt, List<PublishedToolDescriptor> descriptors, List<CodeactTool> tools) {
            this.loadedAt = loadedAt;
            this.descriptors = descriptors != null ? List.copyOf(new ArrayList<>(descriptors)) : List.of();
            this.tools = tools != null ? List.copyOf(new ArrayList<>(tools)) : List.of();
        }

        private List<CodeactTool> reactAccessibleTools() {
            List<CodeactTool> accessibleTools = new ArrayList<>();
            for (PublishedToolDescriptor descriptor : descriptors) {
                if (!isReactAccessiblePublication(descriptor)) {
                    continue;
                }
                accessibleTools.add(descriptor.directTool());
            }
            return List.copyOf(accessibleTools);
        }

        private boolean isExpired() {
            return loadedAt.plus(SNAPSHOT_TTL).isBefore(Instant.now());
        }
    }

    /**
     * Tool publish event used for cache invalidation.
     */
    public record ToolPublishedEvent(String tenantId) {
    }
}

