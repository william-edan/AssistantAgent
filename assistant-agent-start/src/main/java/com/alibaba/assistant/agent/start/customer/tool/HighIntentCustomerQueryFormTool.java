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
package com.alibaba.assistant.agent.start.customer.tool;

import com.alibaba.assistant.agent.common.enums.Language;
import com.alibaba.assistant.agent.common.tools.CodeExample;
import com.alibaba.assistant.agent.common.tools.CodeactToolMetadata;
import com.alibaba.assistant.agent.common.tools.DefaultCodeactToolMetadata;
import com.alibaba.assistant.agent.extension.dynamic.tool.AbstractDynamicCodeactTool;
import com.alibaba.assistant.agent.start.customer.model.HighIntentCustomerIntentResult;
import com.alibaba.assistant.agent.start.customer.node.HighIntentCustomerIntentNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.context.annotation.Profile;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 高意向客户查询表单工具。
 */
@Component
@Profile("migration")
public class HighIntentCustomerQueryFormTool extends AbstractDynamicCodeactTool {

    public static final String TOOL_NAME = "high_intent_customer_query_form";

    private static final int DEFAULT_PAGE = 1;

    private static final int DEFAULT_LIMIT = 100;

    private final HighIntentCustomerIntentNode intentNode;

    private final HighIntentCustomerQueryTool queryTool;

    public HighIntentCustomerQueryFormTool(
            ObjectMapper objectMapper,
            HighIntentCustomerIntentNode intentNode,
            HighIntentCustomerQueryTool queryTool) {
        super(objectMapper, buildToolDefinition(), buildMetadata());
        this.intentNode = intentNode;
        this.queryTool = queryTool;
    }

    @Override
    protected String doCall(Map<String, Object> args, @Nullable ToolContext toolContext) throws Exception {
        Map<String, Object> values = resolveValues(args);
        List<Map<String, Object>> fields = buildFields(values);
        List<Map<String, Object>> missingFields = fields.stream()
                .filter(field -> Boolean.TRUE.equals(field.get("required")))
                .filter(field -> !StringUtils.hasText(asText(values.get(field.get("name")))))
                .map(field -> Map.<String, Object>of(
                        "name", String.valueOf(field.get("name")),
                        "title", String.valueOf(field.get("label"))))
                .toList();

        // 已经能确定员工姓名时直接发起查询，避免再次弹确认表单。
        if (missingFields.isEmpty()) {
            return submitDirectly(args, values, toolContext);
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("kind", "FORM");
        payload.put("type", "form");
        payload.put("title", "查询高意向客户");
        payload.put("toolCode", HighIntentCustomerQueryTool.TOOL_NAME);
        payload.put("artifactCode", HighIntentCustomerQueryTool.TOOL_NAME);
        payload.put("submit_tool", HighIntentCustomerQueryTool.TOOL_NAME);
        payload.put("mode", "COLLECT");
        payload.put("phase", "COLLECTING");
        payload.put("status", "WAITING_INPUT");
        payload.put("message", "请填写查询条件。");
        payload.put("values", values);
        payload.put("fields", fields);
        payload.put("missingFields", missingFields);
        payload.put("summary", Map.of());
        payload.put("canSubmit", false);
        return objectMapper.writeValueAsString(payload);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> resolveValues(Map<String, Object> args) {
        Map<String, Object> values = new LinkedHashMap<>();
        Object rawValues = args.get("values");
        if (rawValues instanceof Map<?, ?> valueMap) {
            valueMap.forEach((key, value) -> {
                if (key != null && value != null) {
                    values.put(String.valueOf(key), value);
                }
            });
        }
        Object rawSlotInputs = args.get("slotInputs");
        if (rawSlotInputs instanceof Map<?, ?> slotInputs) {
            slotInputs.forEach((key, value) -> {
                if (key != null && value != null) {
                    values.put(String.valueOf(key), value);
                }
            });
        }
        args.forEach((key, value) -> {
            if (isBusinessField(key) && value != null) {
                values.put(key, value);
            }
        });

        // 表单只负责兜底补齐员工姓名，分页参数由后端固定为 1/100。
        HighIntentCustomerIntentResult intentResult = intentNode.identify(asText(args.get("userInput")));
        putIfAbsent(values, "username", intentResult.username());
        return values;
    }

    private String submitDirectly(
            Map<String, Object> args,
            Map<String, Object> values,
            @Nullable ToolContext toolContext) throws Exception {
        Map<String, Object> queryArgs = new LinkedHashMap<>();
        putIfHasText(queryArgs, "userInput", asText(args.get("userInput")));
        queryArgs.put("confirmed", true);
        queryArgs.put("page", DEFAULT_PAGE);
        queryArgs.put("limit", DEFAULT_LIMIT);
        putIfHasText(queryArgs, "username", asText(values.get("username")));
        return queryTool.doCall(queryArgs, toolContext);
    }

    private boolean isBusinessField(String key) {
        return StringUtils.hasText(key)
                && !"values".equals(key)
                && !"slotInputs".equals(key)
                && !"userInput".equals(key)
                && !"confirmed".equals(key)
                && !"frontendThreadState".equals(key)
                && !"page".equals(key)
                && !"limit".equals(key);
    }

    private List<Map<String, Object>> buildFields(Map<String, Object> values) {
        List<Map<String, Object>> fields = new ArrayList<>();
        fields.add(field("员工姓名", "username", "input", values.get("username"), true));
        return fields;
    }

    private Map<String, Object> field(String label, String name, String type, Object value, boolean required) {
        Map<String, Object> field = new LinkedHashMap<>();
        field.put("label", label);
        field.put("title", label);
        field.put("name", name);
        field.put("type", type);
        field.put("value", value);
        field.put("required", required);
        return field;
    }

    private void putIfAbsent(Map<String, Object> values, String key, @Nullable Object value) {
        if (!values.containsKey(key) && value != null && StringUtils.hasText(asText(value))) {
            values.put(key, value);
        }
    }

    private void putIfHasText(Map<String, Object> values, String key, @Nullable String value) {
        if (StringUtils.hasText(value)) {
            values.put(key, value);
        }
    }

    private String asText(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return StringUtils.hasText(text) ? text : null;
    }

    private static ToolDefinition buildToolDefinition() {
        return DefaultToolDefinition.builder()
                .name(TOOL_NAME)
                .description("Return the high intent customer query form schema for frontend rendering.")
                .inputSchema("""
                        {
                          "type": "object",
                          "properties": {
                            "userInput": {
                              "type": "string",
                              "description": "Original user input"
                            },
                            "values": {
                              "type": "object",
                              "description": "Pre-filled form values"
                            },
                            "slotInputs": {
                              "type": "object",
                              "description": "Current structured slot inputs"
                            }
                          }
                        }
                        """)
                .build();
    }

    private static CodeactToolMetadata buildMetadata() {
        return DefaultCodeactToolMetadata.builder()
                .addSupportedLanguage(Language.PYTHON)
                .targetClassName("customer_query_tools")
                .targetClassDescription("High intent customer query form tools")
                .fewShots(List.of(new CodeExample(
                        "open customer query form",
                        "result = high_intent_customer_query_form(userInput='查询张三的高意向客户')",
                        "返回高意向客户查询表单")))
                .displayName(TOOL_NAME)
                .returnDirect(true)
                .alwaysAvailable(true)
                .build();
    }
}
