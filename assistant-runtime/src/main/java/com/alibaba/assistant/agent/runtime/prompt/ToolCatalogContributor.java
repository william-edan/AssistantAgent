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
package com.alibaba.assistant.agent.runtime.prompt;

import com.alibaba.assistant.agent.controlplane.toolregistry.ToolMeta;
import com.alibaba.assistant.agent.controlplane.toolregistry.ToolMetaService;
import com.alibaba.assistant.agent.prompt.PromptContribution;
import com.alibaba.assistant.agent.prompt.PromptContributor;
import com.alibaba.assistant.agent.prompt.PromptContributorContext;
import com.alibaba.assistant.agent.runtime.config.RuntimeConfigCompatibilityAdapter;
import com.alibaba.assistant.agent.runtime.registry.ArtifactPublicationLookupService;
import com.alibaba.assistant.agent.runtime.registry.LegacyCompatibilityLogHelper;
import com.alibaba.assistant.agent.runtime.registry.PublicationScopeResolver;
import com.alibaba.assistant.agent.runtime.registry.PublishedToolDescriptor;
import com.alibaba.assistant.agent.runtime.registry.ToolPublicationProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Injects scope-aware tool catalog into prompt context.
 */
@Component
@Profile("migration")
public class ToolCatalogContributor implements PromptContributor {

    private static final Logger logger = LoggerFactory.getLogger(ToolCatalogContributor.class);

    private static final String DEFAULT_TENANT = "default";

    private static final String LEGACY_BRIDGE_SOURCE_ID = "legacy-bridge";

    private final ArtifactPublicationLookupService artifactPublicationLookupService;

    private final ToolMetaService toolMetaService;

    private final RuntimeConfigCompatibilityAdapter compatibilityAdapter;

    private final PublicationScopeResolver publicationScopeResolver;

    public ToolCatalogContributor(
            ArtifactPublicationLookupService artifactPublicationLookupService,
            ToolMetaService toolMetaService,
            RuntimeConfigCompatibilityAdapter compatibilityAdapter,
            PublicationScopeResolver publicationScopeResolver) {
        this.artifactPublicationLookupService = artifactPublicationLookupService;
        this.toolMetaService = toolMetaService;
        this.compatibilityAdapter = compatibilityAdapter;
        this.publicationScopeResolver = publicationScopeResolver;
    }

    @Override
    public String getName() {
        return "tool-catalog";
    }

    @Override
    public int getPriority() {
        return 200;
    }

    @Override
    public boolean shouldContribute(PromptContributorContext context) {
        return compatibilityAdapter.promptDynamicEnabled();
    }

    @Override
    public PromptContribution contribute(PromptContributorContext context) {
        List<PublishedToolDescriptor> artifacts = artifactPublicationLookupService
                .listPublishedArtifacts(context.getAttributes());
        if (artifacts != null && !artifacts.isEmpty()) {
            int limit = Math.max(1, compatibilityAdapter.promptMaxToolsInPrompt());
            boolean truncated = artifacts.size() > limit;
            List<PublishedToolDescriptor> limited = new ArrayList<>(artifacts.subList(0, Math.min(limit, artifacts.size())));
            return PromptContribution.builder()
                    .append(new UserMessage(renderArtifactCatalogText(limited, truncated, artifacts.size())))
                    .build();
        }

        ToolPublicationProvider.PublicationScope scope = resolvePublicationScope(context);
        if (!shouldFallbackToLegacyCatalog(scope)) {
            return PromptContribution.empty();
        }

        String tenantId = resolveTenantId(context);
        String systemCode = resolveSystemCode(context);
        List<ToolMeta> source = toolMetaService.listEnabledByTenantAndSystem(tenantId, systemCode);
        if (source == null || source.isEmpty()) {
            return PromptContribution.empty();
        }

        LegacyCompatibilityLogHelper.logFallback(
                logger,
                "ToolCatalogContributor#contribute",
                "legacy prompt catalog",
                scope,
                tenantId,
                source.get(0).getToolCode());

        int limit = Math.max(1, compatibilityAdapter.promptMaxToolsInPrompt());
        boolean truncated = source.size() > limit;
        List<ToolMeta> tools = new ArrayList<>(source.subList(0, Math.min(limit, source.size())));
        return PromptContribution.builder()
                .append(new UserMessage(renderLegacyCatalogText(tools, truncated, source.size())))
                .build();
    }

    private ToolPublicationProvider.PublicationScope resolvePublicationScope(PromptContributorContext context) {
        return publicationScopeResolver.resolve(context.getAttributes());
    }

    private boolean shouldFallbackToLegacyCatalog(ToolPublicationProvider.PublicationScope scope) {
        if (scope == null) {
            return true;
        }
        boolean scopedAgentAppCall = scope.spaceId() != null && StringUtils.hasText(scope.agentAppCode());
        if (!scopedAgentAppCall) {
            return true;
        }
        if (containsLegacyBridge(scope.blockedSourceIds())) {
            return false;
        }
        if (scope.sourceSelectionMode() == ToolPublicationProvider.SourceSelectionMode.EXCLUSIVE) {
            return containsLegacyBridge(scope.requestedSourceIds());
        }
        return true;
    }

    private boolean containsLegacyBridge(List<String> sourceIds) {
        if (sourceIds == null || sourceIds.isEmpty()) {
            return false;
        }
        for (String sourceId : sourceIds) {
            if (LEGACY_BRIDGE_SOURCE_ID.equalsIgnoreCase(sourceId)) {
                return true;
            }
        }
        return false;
    }

    private String resolveTenantId(PromptContributorContext context) {
        Map<String, Object> attrs = context.getAttributes();
        String tenantId = firstNonBlank(
                asText(attrs.get("tenant_id")),
                asText(attrs.get("tenantId")));
        return StringUtils.hasText(tenantId) ? tenantId : DEFAULT_TENANT;
    }

    private String resolveSystemCode(PromptContributorContext context) {
        Map<String, Object> attrs = context.getAttributes();
        return firstNonBlank(
                asText(attrs.get("system_code")),
                asText(attrs.get("systemCode")));
    }

    private String renderArtifactCatalogText(List<PublishedToolDescriptor> descriptors, boolean truncated, int originalSize) {
        StringBuilder sb = new StringBuilder();
        sb.append("【可用业务工具目录】\n");
        sb.append("调用 slot_collect 时，toolCode 必须使用下列值，不要编造；确认后调用 artifact_execute，并复用相同 toolCode。\n");
        for (PublishedToolDescriptor descriptor : descriptors) {
            if (descriptor == null || descriptor.artifact() == null || !StringUtils.hasText(descriptor.artifact().getArtifactCode())) {
                continue;
            }
            sb.append("- toolCode=\"").append(descriptor.artifact().getArtifactCode()).append("\"");
            if (StringUtils.hasText(descriptor.displayName())) {
                sb.append(" (").append(descriptor.displayName()).append(")");
            }
            sb.append("\n");
        }
        if (truncated) {
            sb.append("（工具目录已按上限截断，当前显示 ")
                    .append(descriptors.size())
                    .append("/")
                    .append(originalSize)
                    .append("）\n");
        }
        return sb.toString().trim();
    }

    private String renderLegacyCatalogText(List<ToolMeta> tools, boolean truncated, int originalSize) {
        StringBuilder sb = new StringBuilder();
        sb.append("【可用业务工具目录】\n");
        sb.append("调用 slot_collect 时，toolCode 必须使用下列值，不要编造：\n");
        for (ToolMeta tool : tools) {
            if (!StringUtils.hasText(tool.getToolCode())) {
                continue;
            }
            sb.append("- toolCode=\"").append(tool.getToolCode()).append("\"");
            if (StringUtils.hasText(tool.getToolName())) {
                sb.append(" (").append(tool.getToolName()).append(")");
            }
            if (StringUtils.hasText(tool.getDescription())) {
                sb.append("：").append(tool.getDescription());
            }
            sb.append("\n");
        }
        if (truncated) {
            sb.append("（工具目录已按上限截断，当前显示 ")
                    .append(tools.size())
                    .append("/")
                    .append(originalSize)
                    .append("）\n");
        }
        return sb.toString().trim();
    }

    private String asText(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return StringUtils.hasText(text) ? text : null;
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

