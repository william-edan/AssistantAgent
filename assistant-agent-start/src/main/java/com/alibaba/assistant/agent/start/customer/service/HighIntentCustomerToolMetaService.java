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
package com.alibaba.assistant.agent.start.customer.service;

import com.alibaba.assistant.agent.runtime.planner.ToolExecutor;
import com.alibaba.assistant.agent.start.customer.model.HighIntentCustomerQueryRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.context.annotation.Profile;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * 高意向客户查询 tool_meta 服务。
 */
@Service
@Profile("migration")
public class HighIntentCustomerToolMetaService {

    private static final Logger log = LoggerFactory.getLogger(HighIntentCustomerToolMetaService.class);

    private static final String DEFAULT_TENANT = "default";

    public static final String HIGH_INTENT_CUSTOMER_QUERY_TOOL_CODE = "gougu_oa.high_intent_customer_query";

    private final ToolExecutor toolExecutor;

    private final ObjectMapper objectMapper;

    public HighIntentCustomerToolMetaService(ToolExecutor toolExecutor, ObjectMapper objectMapper) {
        this.toolExecutor = toolExecutor;
        this.objectMapper = objectMapper;
    }

    /**
     * 通过 tool_meta 查询员工的高意向客户列表。
     */
    public QueryResult queryHighIntentCustomers(
            HighIntentCustomerQueryRequest request,
            @Nullable ToolContext toolContext) {
        Map<String, Object> query = buildQuery(request);
        ToolExecutor.ExecutionResult executionResult = execute(Map.of("query", query), toolContext);
        List<CustomerRecord> customers = parseCustomers(executionResult);
        String message = resolveMessage(executionResult, request.username(), customers.size());
        return new QueryResult(customers, message, summarizePayload(executionResult.payload()));
    }

    private Map<String, Object> buildQuery(HighIntentCustomerQueryRequest request) {
        Map<String, Object> query = new LinkedHashMap<>();
        query.put("page", request.page());
        query.put("limit", request.limit());
        query.put("follow_time", "");
        query.put("next_time", "");
        query.put("industry_id", "");
        query.put("grade_id", "");
        query.put("source_id", "");
        query.put("customer_status", "");
        query.put("intent_status", "8");
        query.put("username", request.username());
        query.put("uid", "");
        query.put("keywords", "");
        query.put("tab", "0");
        query.put("order_field", "");
        query.put("order_type", "");
        return query;
    }

    private ToolExecutor.ExecutionResult execute(Map<String, Object> arguments, @Nullable ToolContext toolContext) {
        ToolExecutor.ExecutionResult executionResult = toolExecutor.execute(
                DEFAULT_TENANT,
                HIGH_INTENT_CUSTOMER_QUERY_TOOL_CODE,
                arguments,
                toolContext);
        if (!executionResult.success()) {
            String message = Optional.ofNullable(executionResult.errorMessage())
                    .filter(StringUtils::hasText)
                    .orElse("tool_meta execution failed");
            throw new IllegalStateException(HIGH_INTENT_CUSTOMER_QUERY_TOOL_CODE + " execution failed: " + message);
        }
        log.info(
                "HighIntentCustomerToolMetaService#execute - output={}",
                summarizePayload(executionResult.outputFields()));
        return executionResult;
    }

    private List<CustomerRecord> parseCustomers(ToolExecutor.ExecutionResult executionResult) {
        List<CustomerRecord> customers = new ArrayList<>();
        for (JsonNode node : flattenToNodes(resolveDataNode(executionResult))) {
            customers.add(new CustomerRecord(
                    firstText(node, "name", "customer_name", "customerName", "title"),
                    firstText(node, "contact_name", "contactName", "linkman", "contacts"),
                    firstText(node, "contact_mobile", "contactMobile", "mobile", "phone", "tel"),
                    firstText(node, "belong_name", "belongName", "owner_name", "ownerName", "username"),
                    firstText(node, "grade", "grade_name", "gradeName"),
                    firstText(node, "industry", "industry_name", "industryName"),
                    firstText(node, "create_time", "createTime", "add_time", "addTime")));
        }
        return List.copyOf(customers);
    }

    private String resolveMessage(
            ToolExecutor.ExecutionResult executionResult,
            String username,
            int recordCount) {
        JsonNode outputNode = objectMapper.valueToTree(executionResult.outputFields());
        JsonNode payloadNode = objectMapper.valueToTree(executionResult.payload());
        String explicitMessage = firstText(outputNode, "msg", "message");
        if (!StringUtils.hasText(explicitMessage)) {
            explicitMessage = firstText(payloadNode.path("finalOutputs"), "message", "msg");
        }
        if (StringUtils.hasText(explicitMessage)) {
            return explicitMessage;
        }
        if (recordCount <= 0) {
            return "未查询到%s的高意向客户。".formatted(username);
        }
        return "已查询到%d条高意向客户。".formatted(recordCount);
    }

    private JsonNode resolveDataNode(ToolExecutor.ExecutionResult executionResult) {
        JsonNode outputNode = objectMapper.valueToTree(executionResult.outputFields());
        JsonNode dataNode = outputNode.path("data");
        if (!dataNode.isMissingNode() && !dataNode.isNull()) {
            return dataNode;
        }
        JsonNode payloadNode = objectMapper.valueToTree(executionResult.payload());
        JsonNode payloadDataNode = payloadNode.path("finalOutputs").path("data");
        if (!payloadDataNode.isMissingNode() && !payloadDataNode.isNull()) {
            return payloadDataNode;
        }
        return outputNode;
    }

    private List<JsonNode> flattenToNodes(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return List.of();
        }
        if (node.isArray()) {
            List<JsonNode> result = new ArrayList<>();
            node.forEach(result::add);
            return result;
        }
        if (node.isObject()) {
            JsonNode nested = firstContainer(node, "list", "rows", "records", "data");
            if (nested != node) {
                return flattenToNodes(nested);
            }
            return List.of(node);
        }
        return List.of();
    }

    private JsonNode firstContainer(JsonNode node, String... fieldNames) {
        if (node == null || !node.isObject() || fieldNames == null) {
            return node;
        }
        for (String fieldName : fieldNames) {
            JsonNode candidate = node.path(fieldName);
            if (!candidate.isMissingNode() && !candidate.isNull()) {
                return candidate;
            }
        }
        return node;
    }

    private String firstText(JsonNode node, String... aliases) {
        if (node == null || node.isMissingNode() || node.isNull() || aliases == null) {
            return null;
        }
        if (!node.isObject()) {
            return asText(node.asText());
        }
        for (String alias : aliases) {
            String normalizedAlias = normalizeKey(alias);
            var fields = node.fields();
            while (fields.hasNext()) {
                var entry = fields.next();
                if (normalizeKey(entry.getKey()).equals(normalizedAlias)) {
                    String text = asText(entry.getValue().asText());
                    if (StringUtils.hasText(text)) {
                        return text;
                    }
                }
            }
        }
        return null;
    }

    private String normalizeKey(String key) {
        if (!StringUtils.hasText(key)) {
            return "";
        }
        return key.replaceAll("[\\s_\\-]", "").toLowerCase(Locale.ROOT);
    }

    private String asText(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        String trimmed = value.trim();
        return StringUtils.hasText(trimmed) ? trimmed : null;
    }

    private String summarizePayload(Object payload) {
        try {
            String raw = payload instanceof String text ? text : objectMapper.writeValueAsString(payload);
            if (!StringUtils.hasText(raw)) {
                return "";
            }
            return raw.length() > 400 ? raw.substring(0, 400) + "..." : raw;
        }
        catch (Exception exception) {
            return String.valueOf(payload);
        }
    }

    public record CustomerRecord(
            String name,
            String contactName,
            String contactMobile,
            String belongName,
            String grade,
            String industry,
            String createTime) {
    }

    public record QueryResult(
            List<CustomerRecord> customers,
            String message,
            String rawPayload) {
    }
}

