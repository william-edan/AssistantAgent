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
package com.alibaba.assistant.agent.start.saas.app.service;

import com.alibaba.assistant.agent.start.saas.infrastructure.connector.ConnectorInvokeContext;
import com.alibaba.assistant.agent.start.saas.infrastructure.mybatis.dataobject.ConnectorApiDO;
import com.alibaba.assistant.agent.start.saas.infrastructure.mybatis.mapper.ConnectorApiMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Capability workflow executor.
 *
 * @author Assistant Agent Team
 * @since 1.0.0
 */
@Service
public class CapabilityWorkflowService {

    private static final Pattern PLACEHOLDER_PATTERN = Pattern.compile("^\\$\\{([^}]+)}$");

    private final ConnectorApiMapper connectorApiMapper;

    private final ConnectorInvocationService connectorInvocationService;

    private final ObjectMapper objectMapper;

    public CapabilityWorkflowService(ConnectorApiMapper connectorApiMapper,
            ConnectorInvocationService connectorInvocationService, ObjectMapper objectMapper) {
        this.connectorApiMapper = connectorApiMapper;
        this.connectorInvocationService = connectorInvocationService;
        this.objectMapper = objectMapper;
    }

    /**
     * Execute capability workflow based on route config.
     *
     * @param baseContext base invoke context
     * @param collectedInput merged input from slot collection
     * @return workflow result
     */
    public Map<String, Object> execute(ConnectorInvokeContext baseContext, Map<String, Object> collectedInput) {
        JsonNode routeNode = parseRoute(baseContext.getCapability().getRouteConfigJson());
        List<JsonNode> executionOrder = resolveExecutionOrder(routeNode);

        Map<String, Object> stepOutputs = new LinkedHashMap<>();
        Map<String, Object> finalOutput = Map.of();

        int index = 0;
        for (JsonNode stepNode : executionOrder) {
            index++;
            String stepCode = resolveStepCode(stepNode, index);
            String apiCode = requiredText(stepNode, "apiCode", "route step apiCode is required");
            ConnectorApiDO connectorApi = requireConnectorApi(baseContext.getConnector().getId(), apiCode);
            Map<String, Object> requestBody = resolveRequestBody(stepNode.get("requestBody"), collectedInput, stepOutputs);
            Map<String, String> stepHeaders = resolveStepHeaders(stepNode.get("headers"), collectedInput, stepOutputs);
            String requestMode = resolveRequestMode(stepNode);

            ConnectorInvokeContext stepContext = cloneWithStep(
                    baseContext, stepCode, connectorApi, requestBody, stepHeaders, requestMode);
            Map<String, Object> stepResult = connectorInvocationService.invoke(stepContext);
            stepOutputs.put(stepCode, stepResult);
            finalOutput = stepResult;
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("steps", stepOutputs);
        result.put("final", finalOutput);
        return result;
    }

    private JsonNode parseRoute(String routeConfigJson) {
        try {
            return objectMapper.readTree(routeConfigJson);
        }
        catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("routeConfigJson must be valid json");
        }
    }

    private List<JsonNode> resolveExecutionOrder(JsonNode routeNode) {
        JsonNode stepsNode = routeNode.get("steps");
        if (stepsNode != null && stepsNode.isArray() && !stepsNode.isEmpty()) {
            List<JsonNode> linear = new ArrayList<>();
            for (JsonNode step : stepsNode) {
                linear.add(step);
            }
            return linear;
        }

        JsonNode nodesNode = routeNode.get("nodes");
        JsonNode edgesNode = routeNode.get("edges");
        if (nodesNode == null || !nodesNode.isArray() || nodesNode.isEmpty()) {
            throw new IllegalArgumentException("routeConfigJson.nodes is required");
        }

        Map<String, JsonNode> nodesByCode = new LinkedHashMap<>();
        for (JsonNode node : nodesNode) {
            String nodeCode = resolveNodeCode(node);
            if (nodesByCode.containsKey(nodeCode)) {
                throw new IllegalArgumentException("duplicate route nodeCode: " + nodeCode);
            }
            nodesByCode.put(nodeCode, node);
        }

        Map<String, Set<String>> in = new LinkedHashMap<>();
        Map<String, Set<String>> out = new LinkedHashMap<>();
        nodesByCode.keySet().forEach(code -> {
            in.put(code, new LinkedHashSet<>());
            out.put(code, new LinkedHashSet<>());
        });

        if (edgesNode != null && edgesNode.isArray()) {
            for (JsonNode edge : edgesNode) {
                String from = requiredText(edge, "from", "route edge from is required");
                String to = requiredText(edge, "to", "route edge to is required");
                if (!nodesByCode.containsKey(from) || !nodesByCode.containsKey(to)) {
                    throw new IllegalArgumentException("route edge references unknown node");
                }
                out.get(from).add(to);
                in.get(to).add(from);
            }
        }

        List<String> ready = new ArrayList<>();
        for (Map.Entry<String, Set<String>> entry : in.entrySet()) {
            if (entry.getValue().isEmpty()) {
                ready.add(entry.getKey());
            }
        }
        Collections.sort(ready);

        List<JsonNode> ordered = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        while (!ready.isEmpty()) {
            String nodeCode = ready.remove(0);
            if (!visited.add(nodeCode)) {
                continue;
            }
            ordered.add(nodesByCode.get(nodeCode));
            List<String> next = new ArrayList<>(out.get(nodeCode));
            Collections.sort(next);
            for (String downstream : next) {
                Set<String> deps = in.get(downstream);
                deps.remove(nodeCode);
                if (deps.isEmpty()) {
                    ready.add(downstream);
                }
            }
            Collections.sort(ready);
        }

        if (ordered.size() != nodesByCode.size()) {
            throw new IllegalArgumentException("route graph has cycle");
        }
        return ordered;
    }

    private String resolveStepCode(JsonNode stepNode, int stepIndex) {
        JsonNode stepCodeNode = stepNode.get("stepCode");
        if (stepCodeNode != null && !stepCodeNode.asText().isBlank()) {
            return stepCodeNode.asText();
        }
        JsonNode nodeCodeNode = stepNode.get("nodeCode");
        if (nodeCodeNode != null && !nodeCodeNode.asText().isBlank()) {
            return nodeCodeNode.asText();
        }
        return "step_" + stepIndex;
    }

    private String resolveNodeCode(JsonNode node) {
        JsonNode nodeCodeNode = node.get("nodeCode");
        if (nodeCodeNode != null && !nodeCodeNode.asText().isBlank()) {
            return nodeCodeNode.asText();
        }
        JsonNode stepCodeNode = node.get("stepCode");
        if (stepCodeNode != null && !stepCodeNode.asText().isBlank()) {
            return stepCodeNode.asText();
        }
        throw new IllegalArgumentException("route nodeCode is required");
    }

    private String requiredText(JsonNode node, String field, String message) {
        JsonNode value = node.get(field);
        if (value == null || value.asText().isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.asText();
    }

    private ConnectorApiDO requireConnectorApi(Long connectorId, String apiCode) {
        ConnectorApiDO connectorApi = connectorApiMapper.selectOne(Wrappers.lambdaQuery(ConnectorApiDO.class)
                .eq(ConnectorApiDO::getConnectorId, connectorId)
                .eq(ConnectorApiDO::getApiCode, apiCode)
                .eq(ConnectorApiDO::getStatus, "ACTIVE"));
        if (connectorApi == null) {
            throw new IllegalArgumentException("tool api not registered: " + apiCode);
        }
        return connectorApi;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> resolveRequestBody(JsonNode requestBodyNode, Map<String, Object> input,
            Map<String, Object> stepOutputs) {
        if (requestBodyNode == null || requestBodyNode.isNull()) {
            return new LinkedHashMap<>(input);
        }
        Object resolved = resolveNodeValue(requestBodyNode, input, stepOutputs);
        if (resolved instanceof Map) {
            return (Map<String, Object>) resolved;
        }
        throw new IllegalArgumentException("route step requestBody must be object");
    }

    private Object resolveNodeValue(JsonNode node, Map<String, Object> input, Map<String, Object> stepOutputs) {
        if (node.isObject()) {
            Map<String, Object> map = new LinkedHashMap<>();
            node.fields().forEachRemaining(entry -> map.put(entry.getKey(),
                    resolveNodeValue(entry.getValue(), input, stepOutputs)));
            return map;
        }
        if (node.isArray()) {
            List<Object> values = new ArrayList<>();
            for (JsonNode item : node) {
                values.add(resolveNodeValue(item, input, stepOutputs));
            }
            return values;
        }
        if (node.isTextual()) {
            String text = node.asText();
            Matcher matcher = PLACEHOLDER_PATTERN.matcher(text);
            if (matcher.matches()) {
                return resolvePlaceholder(matcher.group(1), input, stepOutputs);
            }
            return text;
        }
        if (node.isBoolean()) {
            return node.asBoolean();
        }
        if (node.isInt() || node.isLong()) {
            return node.asLong();
        }
        if (node.isFloatingPointNumber()) {
            return node.asDouble();
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> resolveStepHeaders(JsonNode headersNode, Map<String, Object> input,
            Map<String, Object> stepOutputs) {
        if (headersNode == null || headersNode.isNull()) {
            return Map.of();
        }
        Object resolved = resolveNodeValue(headersNode, input, stepOutputs);
        if (!(resolved instanceof Map)) {
            throw new IllegalArgumentException("route step headers must be object");
        }
        Map<String, Object> raw = (Map<String, Object>) resolved;
        Map<String, String> headers = new LinkedHashMap<>();
        raw.forEach((key, value) -> {
            if (value != null && !String.valueOf(value).isBlank()) {
                headers.put(key, String.valueOf(value));
            }
        });
        return headers;
    }

    private String resolveRequestMode(JsonNode stepNode) {
        JsonNode modeNode = stepNode.get("requestMode");
        if (modeNode == null || modeNode.asText().isBlank()) {
            return "JSON";
        }
        return modeNode.asText();
    }

    private Object resolvePlaceholder(String expression, Map<String, Object> input, Map<String, Object> stepOutputs) {
        String[] paths = expression.split("\\.");
        if (paths.length < 2) {
            return null;
        }
        if ("input".equals(paths[0])) {
            return walkValue(input, paths, 1);
        }
        if (("step".equals(paths[0]) || "node".equals(paths[0])) && paths.length >= 3) {
            Object stepValue = stepOutputs.get(paths[1]);
            return walkValue(stepValue, paths, 2);
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private Object walkValue(Object current, String[] paths, int start) {
        Object value = current;
        for (int i = start; i < paths.length; i++) {
            if (value == null) {
                return null;
            }
            if (value instanceof Map) {
                value = ((Map<String, Object>) value).get(paths[i]);
                continue;
            }
            if (value instanceof List) {
                int index = Integer.parseInt(paths[i]);
                List<Object> list = (List<Object>) value;
                value = index >= 0 && index < list.size() ? list.get(index) : null;
                continue;
            }
            return null;
        }
        return value;
    }

    private ConnectorInvokeContext cloneWithStep(ConnectorInvokeContext baseContext, String stepCode,
            ConnectorApiDO connectorApi, Map<String, Object> requestBody, Map<String, String> stepHeaders,
            String requestMode) {
        ConnectorInvokeContext context = new ConnectorInvokeContext();
        context.setTenantId(baseContext.getTenantId());
        context.setSessionId(baseContext.getSessionId());
        context.setRequest(baseContext.getRequest());
        context.setCapability(baseContext.getCapability());
        context.setConnector(baseContext.getConnector());
        context.setConnectorAuth(baseContext.getConnectorAuth());
        context.setConnectorApi(connectorApi);
        context.setStepCode(stepCode);
        context.setStepInput(requestBody);
        context.setAuthHeaders(baseContext.getAuthHeaders());
        context.setAuthCookies(baseContext.getAuthCookies());
        context.setStepHeaders(stepHeaders);
        context.setStepRequestMode(requestMode);
        context.setDelegatedExternalUserId(baseContext.getDelegatedExternalUserId());
        return context;
    }
}
