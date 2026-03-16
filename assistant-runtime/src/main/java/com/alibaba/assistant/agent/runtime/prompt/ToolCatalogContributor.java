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

import com.alibaba.assistant.agent.prompt.PromptContribution;
import com.alibaba.assistant.agent.prompt.PromptContributor;
import com.alibaba.assistant.agent.prompt.PromptContributorContext;
import com.alibaba.assistant.agent.runtime.config.RuntimeConfigView;
import com.alibaba.assistant.agent.runtime.registry.ArtifactPublicationLookupService;
import com.alibaba.assistant.agent.runtime.registry.PublishedToolDescriptor;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * 向提示词注入用户可见的业务工具目录。
 */
@Component
@Profile("migration")
public class ToolCatalogContributor implements PromptContributor {

    private final ArtifactPublicationLookupService artifactPublicationLookupService;

    private final RuntimeConfigView runtimeConfigView;

    public ToolCatalogContributor(
            ArtifactPublicationLookupService artifactPublicationLookupService,
            RuntimeConfigView runtimeConfigView) {
        this.artifactPublicationLookupService = artifactPublicationLookupService;
        this.runtimeConfigView = runtimeConfigView;
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
        return runtimeConfigView.promptDynamicEnabled();
    }

    @Override
    public PromptContribution contribute(PromptContributorContext context) {
        List<PublishedToolDescriptor> descriptors = artifactPublicationLookupService
                .listPublishedArtifacts(context.getAttributes());
        if (descriptors == null || descriptors.isEmpty()) {
            return PromptContribution.empty();
        }

        List<PublishedToolDescriptor> visible = new ArrayList<>();
        for (PublishedToolDescriptor descriptor : descriptors) {
            if (descriptor != null
                    && descriptor.isUserVisible()
                    && descriptor.artifact() != null
                    && StringUtils.hasText(descriptor.artifact().getArtifactCode())) {
                visible.add(descriptor);
            }
        }
        if (visible.isEmpty()) {
            return PromptContribution.empty();
        }

        int limit = Math.max(1, runtimeConfigView.promptMaxToolsInPrompt());
        boolean truncated = visible.size() > limit;
        List<PublishedToolDescriptor> limited = visible.subList(0, Math.min(limit, visible.size()));
        return PromptContribution.builder()
                .append(new UserMessage(renderArtifactCatalogText(limited, truncated, visible.size())))
                .build();
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
}

