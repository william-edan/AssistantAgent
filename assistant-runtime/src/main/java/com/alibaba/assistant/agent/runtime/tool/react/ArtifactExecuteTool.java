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

import com.alibaba.assistant.agent.common.tools.CodeactTool;
import com.alibaba.assistant.agent.common.util.StructuredValueSanitizer;
import com.alibaba.assistant.agent.runtime.agent.AssistantStateKeys;
import com.alibaba.assistant.agent.runtime.execution.ArtifactRuntimeExecutor;
import com.alibaba.assistant.agent.runtime.registry.ArtifactPublicationLookupService;
import com.alibaba.assistant.agent.runtime.registry.PublishedToolDescriptor;
import com.alibaba.assistant.agent.slot.model.SlotDefinition;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.agent.tools.ToolContextConstants;
import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiFunction;

/**
 * 执行工具。
 *
 * <p>负责根据 {@code toolCode} 找到已经发布的动作或工作流，并触发真正的业务执行。
 * 对主 Agent 来说，它是“用户确认之后真正执行”的统一出口。</p>
 */
@Component
@Profile("migration")
public class ArtifactExecuteTool implements BiFunction<ArtifactExecuteTool.Request, ToolContext, ArtifactExecuteTool.Response> {

    private final ArtifactPublicationLookupService artifactPublicationLookupService;

    private final ArtifactRuntimeExecutor artifactRuntimeExecutor;

    private final ArtifactExecutionParamAssembler artifactExecutionParamAssembler;

    private final ObjectMapper objectMapper;

    public ArtifactExecuteTool(
            ArtifactPublicationLookupService artifactPublicationLookupService,
            ArtifactRuntimeExecutor artifactRuntimeExecutor,
            ArtifactExecutionParamAssembler artifactExecutionParamAssembler,
            ObjectMapper objectMapper) {
        this.artifactPublicationLookupService = artifactPublicationLookupService;
        this.artifactRuntimeExecutor = artifactRuntimeExecutor;
        this.artifactExecutionParamAssembler = artifactExecutionParamAssembler;
        this.objectMapper = objectMapper;
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

        Map<String, Object> payload = executeDescriptor(descriptor.get(), effectiveRequest, toolContext);
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

    private Map<String, Object> executeDescriptor(
            PublishedToolDescriptor descriptor,
            Request request,
            ToolContext toolContext) {
        if (descriptor == null) {
            return Map.of("success", false, "error", "Published artifact descriptor is missing");
        }
        Map<String, Object> executionParams = resolveExecutionParams(request, toolContext);
        // 快速定位：用户确认后的真正执行出口在这里，artifact publication 会统一进入 ArtifactRuntimeExecutor。
        if (descriptor.isArtifactPublication()) {
            return artifactRuntimeExecutor.execute(descriptor, executionParams, toolContext);
        }
        if (descriptor.isDirectToolPublication()) {
            return executeDirectTool(descriptor.directTool(), request.toolCode, executionParams, request.confirmed, toolContext);
        }
        return Map.of("success", false, "error", "Published artifact descriptor is missing");
    }

    private Map<String, Object> executeDirectTool(
            CodeactTool directTool,
            String toolCode,
            Map<String, Object> params,
            Boolean confirmed,
            ToolContext toolContext) {
        if (directTool == null) {
            return Map.of("success", false, "error", "Published artifact descriptor is missing");
        }
        Map<String, Object> toolInput = new LinkedHashMap<>();
        if (StringUtils.hasText(toolCode)) {
            toolInput.put("toolCode", toolCode);
        }
        if (params != null && !params.isEmpty()) {
            Map<String, Object> normalizedParams = normalizeDirectToolParams(toolCode, params);
            toolInput.putAll(normalizedParams);
            toolInput.put("params", normalizedParams);
        }
        if (confirmed != null) {
            toolInput.put("confirmed", confirmed);
        }
        try {
            String rawResult = directTool.call(objectMapper.writeValueAsString(toolInput), toolContext);
            return parseDirectToolResult(rawResult, toolCode);
        }
        catch (Exception ex) {
            String message = asText(ex.getMessage());
            return Map.of(
                    "success", false,
                    "error", message != null ? message : ex.getClass().getSimpleName());
        }
    }

    private Map<String, Object> resolveExecutionParams(Request request, ToolContext toolContext) {
        List<SlotDefinition> slotDefinitions = readSlotDefinitions(toolContext);
        return artifactExecutionParamAssembler.assemble(request, toolContext, slotDefinitions);
    }

    private OverAllState getState(ToolContext toolContext) {
        if (toolContext == null || toolContext.getContext() == null) {
            return null;
        }
        Object stateObject = toolContext.getContext().get(ToolContextConstants.AGENT_STATE_CONTEXT_KEY);
        return stateObject instanceof OverAllState ? (OverAllState) stateObject : null;
    }

    @SuppressWarnings("unchecked")
    private List<SlotDefinition> readSlotDefinitions(ToolContext toolContext) {
        OverAllState state = getState(toolContext);
        if (state == null) {
            return List.of();
        }
        Object rawDefinitions = state.value(AssistantStateKeys.SLOT_DEFINITIONS, Object.class).orElse(null);
        if (!(rawDefinitions instanceof Iterable<?> rawList)) {
            return List.of();
        }
        java.util.ArrayList<SlotDefinition> definitions = new java.util.ArrayList<>();
        for (Object item : rawList) {
            if (item instanceof SlotDefinition slotDefinition) {
                definitions.add(slotDefinition);
                continue;
            }
            try {
                definitions.add(objectMapper.convertValue(item, SlotDefinition.class));
            }
            catch (Exception ignored) {
                // 忽略无法转换的脏数据，避免影响主链执行。
            }
        }
        return definitions;
    }

    private Map<String, Object> sanitizeParams(Map<String, Object> params) {
        return params != null ? StructuredValueSanitizer.sanitizeMap(params) : new LinkedHashMap<>();
    }

    private Map<String, Object> normalizeDirectToolParams(String toolCode, Map<String, Object> params) {
        Map<String, Object> normalized = sanitizeParams(params);
        if (!StringUtils.hasText(toolCode)) {
            return normalized;
        }
        if ("gougu_oa.leave_application".equalsIgnoreCase(toolCode.trim())) {
            normalizeLeaveApplicationParams(normalized);
        }
        return normalized;
    }

    private void normalizeLeaveApplicationParams(Map<String, Object> params) {
        if (params == null || params.isEmpty()) {
            return;
        }

        Object leaveDate = firstNonNull(params, "leaveDate", "leave_date", "date");
        Object startDate = firstNonNull(params, "start_date", "startDate");
        if (startDate == null) {
            startDate = leaveDate;
        }
        putIfAbsent(params, "start_date", startDate);

        Object endDate = firstNonNull(params, "end_date", "endDate");
        if (endDate == null) {
            endDate = startDate != null ? startDate : leaveDate;
        }
        putIfAbsent(params, "end_date", endDate);

        putIfAbsent(params, "reason", firstNonNull(params,
                "reason", "leaveReason", "leave_reason", "works", "title"));

        Object rawType = firstNonNull(params, "types", "leaveType", "leave_type", "type");
        Object normalizedType = normalizeLeaveTypeValue(rawType);
        if (normalizedType != null) {
            params.put("types", normalizedType);
        }
    }

    private void putIfAbsent(Map<String, Object> target, String key, Object value) {
        if (target == null || !StringUtils.hasText(key) || value == null || target.containsKey(key)) {
            return;
        }
        target.put(key, value);
    }

    private Object firstNonNull(Map<String, Object> source, String... keys) {
        if (source == null || source.isEmpty() || keys == null || keys.length == 0) {
            return null;
        }
        for (String key : keys) {
            if (!StringUtils.hasText(key)) {
                continue;
            }
            Object value = source.get(key);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private Object normalizeLeaveTypeValue(Object rawType) {
        if (rawType == null) {
            return null;
        }
        if (rawType instanceof Number number) {
            return number.intValue();
        }
        String text = asText(rawType);
        if (!StringUtils.hasText(text)) {
            return null;
        }
        try {
            return Integer.parseInt(text);
        }
        catch (NumberFormatException ignored) {
            // fall through to label mapping
        }
        return switch (text) {
            case "事假" -> 1;
            case "年假" -> 2;
            case "调休", "调休假" -> 3;
            case "病假" -> 4;
            case "婚假" -> 5;
            case "丧假" -> 6;
            case "产假" -> 7;
            case "陪产假" -> 8;
            case "其他" -> 9;
            default -> rawType;
        };
    }

    private Map<String, Object> parseDirectToolResult(String rawResult, String toolCode) {
        if (!StringUtils.hasText(rawResult)) {
            return Map.of("success", true, "toolCode", toolCode);
        }
        try {
            return objectMapper.readValue(rawResult, new TypeReference<LinkedHashMap<String, Object>>() {
            });
        }
        catch (Exception ignored) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("success", true);
            if (StringUtils.hasText(toolCode)) {
                payload.put("toolCode", toolCode);
            }
            payload.put("rawResult", rawResult);
            return payload;
        }
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
