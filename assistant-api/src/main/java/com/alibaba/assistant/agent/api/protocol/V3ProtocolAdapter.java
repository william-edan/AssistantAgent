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
package com.alibaba.assistant.agent.api.protocol;

import com.alibaba.assistant.agent.runtime.agent.AssistantStateKeys;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 前端协议适配器。
 *
 * <p>负责把内部工具输出、执行结果和线程状态快照统一转换为前端稳定协议。
 * 前端看到的 {@code STAGE}、{@code FORM_STATE}、{@code TASK_STATE}、{@code RESULT}
 * 都由这里产出，因此这里是前后端协议边界的核心收口点。</p>
 */
@Component
@Profile("migration")
public class V3ProtocolAdapter {

    public static final String PROTOCOL_VERSION = "2026-03-13";

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final ObjectMapper objectMapper;

    private final ProtocolPayloadSupport payloadSupport;

    private final List<ProtocolStrategy> strategies;

    public V3ProtocolAdapter(ObjectMapper objectMapper) {
        this(objectMapper, new ProtocolPayloadSupport(), null);
    }

    public V3ProtocolAdapter(ObjectMapper objectMapper, List<ProtocolStrategy> strategies) {
        this(objectMapper, new ProtocolPayloadSupport(), strategies);
    }

    @Autowired
    public V3ProtocolAdapter(
            ObjectMapper objectMapper,
            ProtocolPayloadSupport payloadSupport,
            List<ProtocolStrategy> strategies) {
        this.objectMapper = objectMapper;
        this.payloadSupport = payloadSupport != null ? payloadSupport : new ProtocolPayloadSupport();
        this.strategies = orderedStrategies(strategies, this.payloadSupport);
    }

    public Object adaptRequest(Object request) {
        return request;
    }

    public List<FrontendEvent> adapt(String toolName, String toolOutput, OverAllState state) {
        return adapt(null, toolName, toolOutput, (Map<String, Object>) null);
    }

    public List<FrontendEvent> adapt(String toolName, String toolOutput, Map<String, Object> state) {
        return adapt(null, toolName, toolOutput, state);
    }

    public List<FrontendEvent> adapt(
            String threadId,
            String toolName,
            String toolOutput,
            Map<String, Object> state) {
        try {
            Map<String, Object> payload = parsePayload(toolOutput);
            String normalizedToolName = normalizeToolName(toolName);
            Map<String, Object> stateView = state != null ? state : Map.of();
            return findStrategy(normalizedToolName, payload)
                    .map(strategy -> strategy.adapt(threadId, normalizedToolName, payload, stateView))
                    .orElseGet(List::of);
        }
        catch (Exception e) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("toolName", toolName);
            payload.put("error", e.getMessage());
            payload.put("rawOutput", toolOutput);
            return List.of(payloadSupport.errorEvent(threadId, "protocol_adapter_error", payload));
        }
    }

    public String toJson(List<FrontendEvent> events) {
        try {
            return objectMapper.writeValueAsString(events != null ? events : Collections.emptyList());
        }
        catch (Exception e) {
            return "[]";
        }
    }

    public Object adaptResponse(Object internalResponse) {
        return internalResponse;
    }

    public FrontendEvent stageEvent(String threadId, FrontendStage stage, Map<String, Object> payload) {
        return payloadSupport.stageEvent(threadId, stage, payload);
    }

    public FrontendEvent messageEvent(String threadId, String text) {
        return payloadSupport.messageEvent(threadId, text);
    }

    public FrontendEvent formStateEvent(String threadId, Map<String, Object> payload) {
        return payloadSupport.formStateEvent(threadId, payload);
    }

    public FrontendEvent taskStateEvent(String threadId, Map<String, Object> payload) {
        return payloadSupport.taskStateEvent(threadId, payload);
    }

    public Map<String, Object> executionTaskPayload(Map<String, Object> executionPayload) {
        return payloadSupport.executionTaskPayload(executionPayload);
    }

    public FrontendEvent executionProgressEvent(String threadId, Map<String, Object> payload) {
        return payloadSupport.executionProgressEvent(threadId, payload);
    }

    public FrontendEvent resultEvent(String threadId, Map<String, Object> payload) {
        return payloadSupport.resultEvent(threadId, payload);
    }

    public FrontendEvent errorEvent(String threadId, String code, Map<String, Object> payload) {
        return payloadSupport.errorEvent(threadId, code, payload);
    }

    public Map<String, Object> projectThreadState(
            String toolName,
            String toolOutput,
            Map<String, Object> state) {
        Map<String, Object> payload = parsePayload(toolOutput);
        String normalizedToolName = normalizeToolName(toolName);
        Map<String, Object> stateView = state != null ? state : Map.of();
        Map<String, Object> snapshot = findStrategy(normalizedToolName, payload)
                .map(strategy -> strategy.projectThreadState(normalizedToolName, payload, stateView))
                .orElseGet(Map::of);
        if (!snapshot.isEmpty()) {
            snapshot = new LinkedHashMap<>(snapshot);
            snapshot.putIfAbsent("protocolVersion", PROTOCOL_VERSION);
            snapshot.put("updatedAt", Instant.now().toString());
            String assistantUid = asText(stateView.get(AssistantStateKeys.ASSISTANT_UID));
            if (StringUtils.hasText(assistantUid)) {
                snapshot.put("assistantUid", assistantUid);
            }
        }
        return decorateRoleBinding(snapshot, stateView);
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> projectThreadState(Map<String, Object> state) {
        Map<String, Object> currentState = state != null ? state : Map.of();
        Object existing = currentState.get(AssistantStateKeys.FRONTEND_THREAD_STATE);
        if (existing instanceof Map<?, ?> existingSnapshot) {
            return decorateRoleBinding(new LinkedHashMap<>((Map<String, Object>) existingSnapshot), currentState);
        }

        Object executionResult = currentState.get(AssistantStateKeys.EXECUTION_RESULT);
        if (executionResult instanceof Map<?, ?> executionResultMap && !executionResultMap.isEmpty()) {
            return decorateRoleBinding(payloadSupport.projectResultState((Map<String, Object>) executionResultMap, currentState), currentState);
        }

        String phase = asText(currentState.get(AssistantStateKeys.CONVERSATION_PHASE));
        if (!StringUtils.hasText(phase)) {
            return decorateRoleBinding(Map.of(), currentState);
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("phase", phase);
        payload.put("message", asText(currentState.get("message")));
        payload.put("collected", currentState.get(AssistantStateKeys.COLLECTED_SLOTS));
        payload.put("allCollected", currentState.get(AssistantStateKeys.COLLECTED_SLOTS));
        payload.put("enrichedSlots", currentState.get(AssistantStateKeys.ENRICHED_SLOTS));
        payload.put("missing", currentState.get("missing"));

        if ("READY_TO_CONFIRM".equalsIgnoreCase(phase) || "CONFIRMING".equalsIgnoreCase(phase)) {
            return decorateRoleBinding(payloadSupport.projectConfirmState(payload, currentState), currentState);
        }
        return decorateRoleBinding(payloadSupport.projectCollectState(payload, currentState), currentState);
    }

    private List<ProtocolStrategy> orderedStrategies(List<ProtocolStrategy> strategies, ProtocolPayloadSupport payloadSupport) {
        List<ProtocolStrategy> ordered = new ArrayList<>();
        if (strategies != null) {
            ordered.addAll(strategies);
        }
        else {
            ordered.addAll(defaultStrategies(payloadSupport));
        }
        AnnotationAwareOrderComparator.sort(ordered);
        return List.copyOf(ordered);
    }

    private List<ProtocolStrategy> defaultStrategies(ProtocolPayloadSupport payloadSupport) {
        return List.of(
                new FormStateProtocolStrategy(payloadSupport),
                new ConfirmProtocolStrategy(payloadSupport),
                new ExecutionResultProtocolStrategy(payloadSupport),
                new MessageProtocolStrategy(payloadSupport),
                new GenericTaskProtocolStrategy(payloadSupport));
    }

    private java.util.Optional<ProtocolStrategy> findStrategy(String normalizedToolName, Map<String, Object> payload) {
        return strategies.stream()
                .filter(strategy -> strategy.supports(normalizedToolName, payload))
                .findFirst();
    }

    private Map<String, Object> parsePayload(String toolOutput) {
        if (!StringUtils.hasText(toolOutput)) {
            return new LinkedHashMap<>();
        }
        try {
            Map<String, Object> payload = objectMapper.readValue(toolOutput, MAP_TYPE);
            payload.putIfAbsent("rawOutput", toolOutput);
            return payload;
        }
        catch (Exception ignored) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("rawOutput", toolOutput);
            return payload;
        }
    }

    private String normalizeToolName(String toolName) {
        return StringUtils.hasText(toolName) ? toolName.trim() : "";
    }

    private Map<String, Object> decorateRoleBinding(Map<String, Object> snapshot, Map<String, Object> stateView) {
        if ((snapshot == null || snapshot.isEmpty()) && (stateView == null || stateView.isEmpty())) {
            return Map.of();
        }
        Map<String, Object> decorated = snapshot == null || snapshot.isEmpty()
                ? new LinkedHashMap<>()
                : new LinkedHashMap<>(snapshot);
        copyIfPresent(decorated, "rolePackageCode", stateView.get(AssistantStateKeys.ROLE_PACKAGE_CODE));
        copyIfPresent(decorated, "rolePackageVersion", stateView.get(AssistantStateKeys.ROLE_PACKAGE_VERSION));
        copyIfPresent(decorated, "roleScenarioCode", stateView.get(AssistantStateKeys.ROLE_SCENARIO_CODE));
        return decorated;
    }

    private void copyIfPresent(Map<String, Object> target, String key, Object value) {
        String text = asText(value);
        if (StringUtils.hasText(text)) {
            target.put(key, text);
        }
    }
    private String asText(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return StringUtils.hasText(text) ? text : null;
    }
}


