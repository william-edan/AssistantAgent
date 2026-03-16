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
import com.alibaba.assistant.agent.runtime.compiler.RuntimeArtifact;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.util.StringUtils;

import java.util.Locale;

/**
 * 运行时发布描述，统一承载工具类型、可见性和调用策略。
 */
public record PublishedToolDescriptor(
        String sourceType,
        String publicationKey,
        String displayName,
        String targetClassName,
        String targetClassDescription,
        boolean alwaysAvailable,
        String executionSystemCode,
        String toolType,
        String visibility,
        String invocationPolicy,
        RuntimeArtifact artifact,
        CodeactTool directTool) {

    private static final String DEFAULT_TOOL_TYPE = "ACTION";

    private static final String DEFAULT_VISIBILITY = "USER";

    private static final String DEFAULT_INVOCATION_POLICY = "DIRECT";

    public PublishedToolDescriptor(
            String sourceType,
            String publicationKey,
            String displayName,
            String targetClassName,
            String targetClassDescription,
            boolean alwaysAvailable,
            String executionSystemCode,
            RuntimeArtifact artifact) {
        this(sourceType, publicationKey, displayName, targetClassName, targetClassDescription,
                alwaysAvailable, executionSystemCode, DEFAULT_TOOL_TYPE, DEFAULT_VISIBILITY,
                DEFAULT_INVOCATION_POLICY, artifact, null);
    }

    public PublishedToolDescriptor(
            String sourceType,
            String publicationKey,
            String displayName,
            String targetClassName,
            String targetClassDescription,
            boolean alwaysAvailable,
            String executionSystemCode,
            RuntimeArtifact artifact,
            CodeactTool directTool) {
        this(sourceType, publicationKey, displayName, targetClassName, targetClassDescription,
                alwaysAvailable, executionSystemCode, DEFAULT_TOOL_TYPE, DEFAULT_VISIBILITY,
                DEFAULT_INVOCATION_POLICY, artifact, directTool);
    }

    public PublishedToolDescriptor {
        toolType = normalizeToolType(toolType);
        visibility = normalizeVisibility(visibility);
        invocationPolicy = normalizeInvocationPolicy(invocationPolicy, visibility);
    }

    public static PublishedToolDescriptor forArtifact(
            String sourceType,
            String publicationKey,
            String displayName,
            String targetClassName,
            String targetClassDescription,
            boolean alwaysAvailable,
            String executionSystemCode,
            RuntimeArtifact artifact) {
        return forArtifact(sourceType, publicationKey, displayName, targetClassName, targetClassDescription,
                alwaysAvailable, executionSystemCode, DEFAULT_TOOL_TYPE, DEFAULT_VISIBILITY,
                DEFAULT_INVOCATION_POLICY, artifact);
    }

    public static PublishedToolDescriptor forArtifact(
            String sourceType,
            String publicationKey,
            String displayName,
            String targetClassName,
            String targetClassDescription,
            boolean alwaysAvailable,
            String executionSystemCode,
            String toolType,
            String visibility,
            String invocationPolicy,
            RuntimeArtifact artifact) {
        return new PublishedToolDescriptor(
                sourceType,
                publicationKey,
                displayName,
                targetClassName,
                targetClassDescription,
                alwaysAvailable,
                executionSystemCode,
                toolType,
                visibility,
                invocationPolicy,
                artifact,
                null);
    }

    public static PublishedToolDescriptor forDirectTool(
            String sourceType,
            String publicationKey,
            String displayName,
            CodeactTool directTool) {
        return newPublishedDirectTool(sourceType, publicationKey, displayName, DEFAULT_TOOL_TYPE,
                DEFAULT_VISIBILITY, DEFAULT_INVOCATION_POLICY, directTool);
    }

    public static PublishedToolDescriptor forDirectTool(
            String sourceType,
            String publicationKey,
            String displayName,
            String toolType,
            String visibility,
            String invocationPolicy,
            CodeactTool directTool) {
        return newPublishedDirectTool(sourceType, publicationKey, displayName, toolType, visibility,
                invocationPolicy, directTool);
    }

    public boolean isArtifactPublication() {
        return artifact != null;
    }

    public boolean isDirectToolPublication() {
        return directTool != null;
    }

    /**
     * 规划器可见表示可被主流程编排，但不一定面向最终用户。
     */
    public boolean isPlannerExposed() {
        if ("DEPENDENCY_ONLY".equalsIgnoreCase(invocationPolicy)) {
            return false;
        }
        return !"INTERNAL".equalsIgnoreCase(visibility);
    }

    /**
     * 用户可见表示可以进入前端目录、主对话工具表和任务展示。
     */
    public boolean isUserVisible() {
        if ("DEPENDENCY_ONLY".equalsIgnoreCase(invocationPolicy)) {
            return false;
        }
        return "USER".equalsIgnoreCase(visibility);
    }

    private static PublishedToolDescriptor newPublishedDirectTool(
            String sourceType,
            String publicationKey,
            String displayName,
            String toolType,
            String visibility,
            String invocationPolicy,
            CodeactTool directTool) {
        CodeactToolMetadata metadata = directTool != null ? directTool.getCodeactMetadata() : null;
        ToolDefinition definition = directTool != null ? directTool.getToolDefinition() : null;
        return new PublishedToolDescriptor(
                sourceType,
                publicationKey,
                firstNonBlank(displayName,
                        metadata != null ? metadata.displayName() : null,
                        definition != null ? definition.description() : null,
                        definition != null ? definition.name() : null),
                metadata != null ? metadata.targetClassName() : null,
                metadata != null ? metadata.targetClassDescription() : null,
                metadata != null && metadata.alwaysAvailable(),
                null,
                toolType,
                visibility,
                invocationPolicy,
                null,
                directTool);
    }

    private static String normalizeToolType(String rawToolType) {
        if (!StringUtils.hasText(rawToolType)) {
            return DEFAULT_TOOL_TYPE;
        }
        String normalized = rawToolType.trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "QUERY", "ACTION", "WORKFLOW", "AGENT_TASK" -> normalized;
            default -> DEFAULT_TOOL_TYPE;
        };
    }

    private static String normalizeVisibility(String rawVisibility) {
        if (!StringUtils.hasText(rawVisibility)) {
            return DEFAULT_VISIBILITY;
        }
        String normalized = rawVisibility.trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "USER", "PLANNER", "INTERNAL" -> normalized;
            default -> DEFAULT_VISIBILITY;
        };
    }

    private static String normalizeInvocationPolicy(String rawInvocationPolicy, String visibility) {
        if (!StringUtils.hasText(rawInvocationPolicy)) {
            return "INTERNAL".equalsIgnoreCase(visibility) ? "DEPENDENCY_ONLY" : DEFAULT_INVOCATION_POLICY;
        }
        String normalized = rawInvocationPolicy.trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "DIRECT", "COMPOSABLE", "DEPENDENCY_ONLY" -> normalized;
            default -> "INTERNAL".equalsIgnoreCase(visibility) ? "DEPENDENCY_ONLY" : DEFAULT_INVOCATION_POLICY;
        };
    }

    private static String firstNonBlank(String... values) {
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
