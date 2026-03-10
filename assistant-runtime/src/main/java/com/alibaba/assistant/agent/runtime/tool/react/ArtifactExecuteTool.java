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
package com.alibaba.assistant.agent.runtime.tool.react;

import com.alibaba.assistant.agent.runtime.execution.ArtifactRuntimeExecutor;
import com.alibaba.assistant.agent.runtime.registry.ArtifactPublicationLookupService;
import com.alibaba.assistant.agent.runtime.registry.PublishedToolDescriptor;
import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiFunction;

/**
 * Stable orchestration tool for executing published artifacts by toolCode.
 */
@Component
@Profile("migration")
public class ArtifactExecuteTool implements BiFunction<ArtifactExecuteTool.Request, ToolContext, ArtifactExecuteTool.Response> {

    private final ArtifactPublicationLookupService artifactPublicationLookupService;

    private final ArtifactRuntimeExecutor artifactRuntimeExecutor;

    public ArtifactExecuteTool(
            ArtifactPublicationLookupService artifactPublicationLookupService,
            ArtifactRuntimeExecutor artifactRuntimeExecutor,
            com.fasterxml.jackson.databind.ObjectMapper objectMapper) {
        this.artifactPublicationLookupService = artifactPublicationLookupService;
        this.artifactRuntimeExecutor = artifactRuntimeExecutor;
    }

    public static ToolCallback createToolCallback(ArtifactExecuteTool tool) {
        return FunctionToolCallback.builder("artifact_execute", tool)
                .description("Execute a published action or workflow artifact by toolCode after confirmation.")
                .inputType(Request.class)
                .build();
    }

    @Override
    public Response apply(Request request, ToolContext toolContext) {
        Request effectiveRequest = request != null ? request : new Request();
        if (!StringUtils.hasText(effectiveRequest.toolCode)) {
            return Response.error("toolCode is required");
        }

        Optional<PublishedToolDescriptor> descriptor = artifactPublicationLookupService
                .findPublishedArtifact(effectiveRequest.toolCode, toolContext);
        if (descriptor.isEmpty()) {
            return Response.error("Published artifact not found: " + effectiveRequest.toolCode);
        }

        Map<String, Object> params = effectiveRequest.params != null
                ? new LinkedHashMap<>(effectiveRequest.params)
                : new LinkedHashMap<>();
        Map<String, Object> payload = artifactRuntimeExecutor.execute(descriptor.get(), params, toolContext);
        boolean success = !Boolean.FALSE.equals(payload.get("success"))
                && !StringUtils.hasText(asText(payload.get("error")));

        Response response = new Response();
        response.success = success;
        response.artifactCode = descriptor.get().artifact() != null
                ? descriptor.get().artifact().getArtifactCode()
                : effectiveRequest.toolCode;
        response.result = payload;
        response.error = success ? null : asText(payload.get("error"));
        return response;
    }

    private String asText(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return StringUtils.hasText(text) ? text : null;
    }

    public static class Request {

        @JsonAlias({ "tool_code", "artifactCode", "artifact_code" })
        @JsonPropertyDescription("Artifact/tool code from the published catalog.")
        public String toolCode;

        @JsonPropertyDescription("Execution parameters for the selected artifact.")
        public Map<String, Object> params = new LinkedHashMap<>();

        @JsonPropertyDescription("Whether the user has confirmed execution.")
        public Boolean confirmed;
    }

    public static class Response {

        public boolean success;

        public String artifactCode;

        public Map<String, Object> result;

        public String error;

        static Response error(String error) {
            Response response = new Response();
            response.success = false;
            response.error = error;
            response.result = Map.of("success", false, "error", error);
            return response;
        }
    }
}

