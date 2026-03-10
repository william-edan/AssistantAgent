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
import com.alibaba.assistant.agent.runtime.tool.codeact.CapabilityBridgeToolFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * Compatibility publication provider that exposes legacy bridge tools to the provider-driven registry.
 */
@Component
@Profile("migration")
public class LegacyToolPublicationProvider implements ToolPublicationProvider {

    private final CapabilityBridgeToolFactory capabilityBridgeToolFactory;

    public LegacyToolPublicationProvider(CapabilityBridgeToolFactory capabilityBridgeToolFactory) {
        this.capabilityBridgeToolFactory = capabilityBridgeToolFactory;
    }

    @Override
    public String providerId() {
        return "legacy-bridge";
    }

    @Override
    public List<PublishedToolDescriptor> listPublishedTools(PublicationScope scope) {
        String tenantId = scope != null && StringUtils.hasText(scope.tenantId()) ? scope.tenantId() : "default";
        List<CodeactTool> tools = capabilityBridgeToolFactory.createToolsForTenant(tenantId);
        List<PublishedToolDescriptor> descriptors = new ArrayList<>(tools.size());
        for (CodeactTool tool : tools) {
            if (tool == null || tool.getToolDefinition() == null || !StringUtils.hasText(tool.getToolDefinition().name())) {
                continue;
            }
            CodeactToolMetadata metadata = tool.getCodeactMetadata();
            descriptors.add(PublishedToolDescriptor.forDirectTool(
                    providerId(),
                    "legacy:" + tool.getToolDefinition().name(),
                    metadata != null ? metadata.displayName() : tool.getToolDefinition().description(),
                    tool));
        }
        return List.copyOf(descriptors);
    }
}
