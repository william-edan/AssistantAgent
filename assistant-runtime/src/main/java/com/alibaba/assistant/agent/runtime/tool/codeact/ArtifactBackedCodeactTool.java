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
package com.alibaba.assistant.agent.runtime.tool.codeact;

import com.alibaba.assistant.agent.common.tools.CodeactToolMetadata;
import com.alibaba.assistant.agent.extension.dynamic.tool.AbstractDynamicCodeactTool;
import com.alibaba.assistant.agent.runtime.execution.ArtifactRuntimeExecutor;
import com.alibaba.assistant.agent.runtime.registry.PublishedToolDescriptor;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.lang.Nullable;

import java.util.Map;

/**
 * Baseline artifact-backed dynamic tool.
 */
public class ArtifactBackedCodeactTool extends AbstractDynamicCodeactTool {

    private final PublishedToolDescriptor publishedDescriptor;

    private final ArtifactRuntimeExecutor artifactRuntimeExecutor;

    public ArtifactBackedCodeactTool(
            ObjectMapper objectMapper,
            PublishedToolDescriptor publishedDescriptor,
            ToolDefinition toolDefinition,
            CodeactToolMetadata codeactMetadata,
            ArtifactRuntimeExecutor artifactRuntimeExecutor) {
        super(objectMapper, toolDefinition, codeactMetadata);
        this.publishedDescriptor = publishedDescriptor;
        this.artifactRuntimeExecutor = artifactRuntimeExecutor;
    }

    public PublishedToolDescriptor getPublishedDescriptor() {
        return publishedDescriptor;
    }

    @Override
    protected String doCall(Map<String, Object> args, @Nullable org.springframework.ai.chat.model.ToolContext toolContext)
            throws Exception {
        if (artifactRuntimeExecutor == null) {
            return objectMapper.writeValueAsString(Map.of(
                    "success", false,
                    "artifactCode", publishedDescriptor.artifact() != null
                            ? publishedDescriptor.artifact().getArtifactCode() : null,
                    "error", "Artifact runtime executor is not configured"));
        }
        return objectMapper.writeValueAsString(
                artifactRuntimeExecutor.execute(publishedDescriptor, args, toolContext));
    }
}

