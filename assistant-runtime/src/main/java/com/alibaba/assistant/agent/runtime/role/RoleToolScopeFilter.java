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
package com.alibaba.assistant.agent.runtime.role;

import com.alibaba.assistant.agent.common.tools.CodeactToolMetadata;
import com.alibaba.assistant.agent.controlplane.rolepackage.ResolvedRolePackageManagementView;
import com.alibaba.assistant.agent.runtime.agent.AssistantStateKeys;
import com.alibaba.assistant.agent.runtime.registry.PublishedToolDescriptor;
import org.springframework.context.annotation.Profile;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Applies role-package tool-scope rules to published tools.
 */
@Component
@Profile("migration")
public class RoleToolScopeFilter {

    private final RoleContextResolver roleContextResolver;

    private final ScenarioRouter scenarioRouter;

    public RoleToolScopeFilter(RoleContextResolver roleContextResolver, ScenarioRouter scenarioRouter) {
        this.roleContextResolver = roleContextResolver;
        this.scenarioRouter = scenarioRouter;
    }

    public List<PublishedToolDescriptor> filter(
            @Nullable Map<String, Object> attributes,
            List<PublishedToolDescriptor> descriptors) {
        if (descriptors == null || descriptors.isEmpty()) {
            return List.of();
        }
        RoleContextResolver.RoleContext roleContext = roleContextResolver.resolve(attributes).orElse(null);
        if (roleContext == null || roleContext.toolScopes().isEmpty()) {
            return List.copyOf(descriptors);
        }

        String scenarioCode = resolveScenarioCode(attributes, roleContext);
        Set<String> allowedToolCodes = resolveAllowedToolCodes(roleContext, scenarioCode);
        List<PublishedToolDescriptor> filtered = new ArrayList<>();
        for (PublishedToolDescriptor descriptor : descriptors) {
            if (descriptor == null) {
                continue;
            }
            if (shouldKeepInternalDescriptor(descriptor) || matchesAllowedTool(descriptor, allowedToolCodes)) {
                filtered.add(descriptor);
            }
        }
        return List.copyOf(filtered);
    }

    private String resolveScenarioCode(@Nullable Map<String, Object> attributes, RoleContextResolver.RoleContext roleContext) {
        if (StringUtils.hasText(roleContext.activeScenarioCode())) {
            return roleContext.activeScenarioCode();
        }
        String input = attributes != null && attributes.get("input") != null ? String.valueOf(attributes.get("input")) : null;
        return scenarioRouter.resolveScenario(attributes, input).orElse(null);
    }

    private Set<String> resolveAllowedToolCodes(RoleContextResolver.RoleContext roleContext, String scenarioCode) {
        LinkedHashSet<String> allowed = new LinkedHashSet<>();
        for (ResolvedRolePackageManagementView.RoleToolScopeView toolScope : roleContext.toolScopes()) {
            if (toolScope == null || !StringUtils.hasText(toolScope.toolCode())) {
                continue;
            }
            if (!StringUtils.hasText(toolScope.scenarioCode())) {
                allowed.add(normalize(toolScope.toolCode()));
                continue;
            }
            if (StringUtils.hasText(scenarioCode) && scenarioCode.trim().equalsIgnoreCase(toolScope.scenarioCode().trim())) {
                allowed.add(normalize(toolScope.toolCode()));
            }
        }
        return allowed;
    }

    private boolean shouldKeepInternalDescriptor(PublishedToolDescriptor descriptor) {
        return "DEPENDENCY_ONLY".equalsIgnoreCase(descriptor.invocationPolicy())
                || "INTERNAL".equalsIgnoreCase(descriptor.visibility());
    }

    private boolean matchesAllowedTool(PublishedToolDescriptor descriptor, Set<String> allowedToolCodes) {
        if (allowedToolCodes.isEmpty()) {
            return false;
        }
        for (String candidate : candidateToolCodes(descriptor)) {
            if (allowedToolCodes.contains(normalize(candidate))) {
                return true;
            }
        }
        return false;
    }

    private Set<String> candidateToolCodes(PublishedToolDescriptor descriptor) {
        LinkedHashSet<String> codes = new LinkedHashSet<>();
        if (descriptor.artifact() != null && StringUtils.hasText(descriptor.artifact().getArtifactCode())) {
            codes.add(descriptor.artifact().getArtifactCode());
        }
        if (StringUtils.hasText(descriptor.publicationKey())) {
            codes.add(descriptor.publicationKey());
            int separatorIndex = descriptor.publicationKey().lastIndexOf(':');
            if (separatorIndex >= 0 && separatorIndex + 1 < descriptor.publicationKey().length()) {
                codes.add(descriptor.publicationKey().substring(separatorIndex + 1));
            }
        }
        if (descriptor.directTool() != null && descriptor.directTool().getToolDefinition() != null) {
            codes.add(descriptor.directTool().getToolDefinition().name());
            CodeactToolMetadata metadata = descriptor.directTool().getCodeactMetadata();
            if (metadata != null && metadata.aliases() != null) {
                codes.addAll(metadata.aliases());
            }
        }
        return codes;
    }

    private String normalize(String value) {
        return StringUtils.hasText(value) ? value.trim().toLowerCase(Locale.ROOT) : "";
    }
}
