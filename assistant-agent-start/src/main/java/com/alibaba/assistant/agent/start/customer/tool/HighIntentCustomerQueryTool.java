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
import com.alibaba.assistant.agent.runtime.agent.AssistantStateKeys;
import com.alibaba.assistant.agent.start.customer.model.HighIntentCustomerQueryRequest;
import com.alibaba.assistant.agent.start.customer.service.HighIntentCustomerToolMetaService;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.agent.tools.ToolContextConstants;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.context.annotation.Profile;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 高意向客户查询提交工具。
 */
@Component
@Profile("migration")
public class HighIntentCustomerQueryTool extends AbstractDynamicCodeactTool {

    private static final Logger log = LoggerFactory.getLogger(HighIntentCustomerQueryTool.class);

    public static final String TOOL_NAME = "high_intent_customer_query";

    private static final int DEFAULT_PAGE = 1;

    private static final int DEFAULT_LIMIT = 100;

    private final HighIntentCustomerToolMetaService toolMetaService;

    public HighIntentCustomerQueryTool(ObjectMapper objectMapper, HighIntentCustomerToolMetaService toolMetaService) {
        super(objectMapper, buildToolDefinition(), buildMetadata());
        this.toolMetaService = toolMetaService;
    }

    @Override
    protected String doCall(Map<String, Object> args, @Nullable ToolContext toolContext) throws Exception {
        try {
            Map<String, Object> values = resolveValues(args, toolContext);
            String username = asText(values.get("username"));
            if (!StringUtils.hasText(username)) {
                return objectMapper.writeValueAsString(errorPayload(
                        "请补充员工姓名后再查询。",
                        values,
                        List.of("username")));
            }

            // 业务要求固定查询第一页的100条，不再让前端参与选择分页参数。
            HighIntentCustomerQueryRequest request = new HighIntentCustomerQueryRequest(username, DEFAULT_PAGE, DEFAULT_LIMIT);
            HighIntentCustomerToolMetaService.QueryResult queryResult =
                    toolMetaService.queryHighIntentCustomers(request, toolContext);
            return objectMapper.writeValueAsString(successPayload(request, queryResult));
        }
        catch (Exception exception) {
            String message = Optional.ofNullable(exception.getMessage())
                    .filter(StringUtils::hasText)
                    .orElse("高意向客户查询失败");
            log.warn("HighIntentCustomerQueryTool#doCall - failed, error={}", message, exception);
            return objectMapper.writeValueAsString(errorPayload(message, Map.of(), List.of()));
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> resolveValues(Map<String, Object> args, @Nullable ToolContext toolContext) {
        Map<String, Object> values = new LinkedHashMap<>();
        Object frontendThreadState = args.get("frontendThreadState");
        if (frontendThreadState instanceof Map<?, ?> stateMap) {
            Object pendingForm = stateMap.get("pendingForm");
            if (pendingForm instanceof Map<?, ?> pendingFormMap
                    && TOOL_NAME.equals(asText(pendingFormMap.get("toolCode")))) {
                Object pendingValues = pendingFormMap.get("values");
                if (pendingValues instanceof Map<?, ?> valueMap) {
                    valueMap.forEach((key, value) -> {
                        if (key != null && value != null) {
                            values.put(String.valueOf(key), value);
                        }
                    });
                }
            }
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

        OverAllState state = extractState(toolContext);
        if (state != null) {
            Object currentTurnInputs = state.value(AssistantStateKeys.CURRENT_TURN_SLOT_INPUTS, Object.class).orElse(null);
            if (currentTurnInputs instanceof Map<?, ?> inputMap) {
                inputMap.forEach((key, value) -> {
                    if (key != null && value != null) {
                        values.put(String.valueOf(key), value);
                    }
                });
            }
        }
        return values;
    }

    private boolean isBusinessField(String key) {
        return StringUtils.hasText(key)
                && !"userInput".equals(key)
                && !"confirmed".equals(key)
                && !"slotInputs".equals(key)
                && !"frontendThreadState".equals(key)
                && !"page".equals(key)
                && !"limit".equals(key);
    }

    private OverAllState extractState(@Nullable ToolContext toolContext) {
        if (toolContext == null || toolContext.getContext() == null) {
            return null;
        }
        Object rawState = toolContext.getContext().get(ToolContextConstants.AGENT_STATE_CONTEXT_KEY);
        return rawState instanceof OverAllState state ? state : null;
    }

    private Map<String, Object> successPayload(
            HighIntentCustomerQueryRequest request,
            HighIntentCustomerToolMetaService.QueryResult queryResult) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("kind", "RESULT");
        payload.put("success", true);
        payload.put("toolCode", TOOL_NAME);
        payload.put("artifactCode", TOOL_NAME);
        payload.put("message", queryResult.message());
        payload.put("username", request.username());
        payload.put("page", request.page());
        payload.put("limit", request.limit());
        payload.put("recordCount", queryResult.customers().size());
        payload.put("records", queryResult.customers().stream().map(this::toRecordMap).toList());
        return payload;
    }

    private Map<String, Object> toRecordMap(HighIntentCustomerToolMetaService.CustomerRecord customer) {
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("name", customer.name());
        record.put("contact_name", customer.contactName());
        record.put("contact_mobile", customer.contactMobile());
        record.put("belong_name", customer.belongName());
        record.put("grade", customer.grade());
        record.put("industry", customer.industry());
        record.put("create_time", customer.createTime());
        return record;
    }

    private Map<String, Object> errorPayload(String message, Map<String, Object> values, List<String> missingFields) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("kind", "RESULT");
        payload.put("success", false);
        payload.put("toolCode", TOOL_NAME);
        payload.put("artifactCode", TOOL_NAME);
        payload.put("message", message);
        payload.put("error", message);
        if (values != null && !values.isEmpty()) {
            payload.put("values", values);
        }
        if (missingFields != null && !missingFields.isEmpty()) {
            payload.put("missingFields", missingFields.stream()
                    .map(name -> Map.<String, Object>of("name", name, "title", fieldTitle(name)))
                    .toList());
        }
        return payload;
    }

    private String fieldTitle(String name) {
        return switch (name) {
            case "username" -> "员工姓名";
            case "page" -> "页码";
            case "limit" -> "每页条数";
            default -> name;
        };
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
                .description("Query high intent customers through tool_meta after user confirmation.")
                .inputSchema("""
                        {
                          "type": "object",
                          "properties": {
                            "username": {"type": "string"},
                            "page": {"type": "integer"},
                            "limit": {"type": "integer"},
                            "confirmed": {"type": "boolean"},
                            "slotInputs": {"type": "object"},
                            "frontendThreadState": {"type": "object"}
                          }
                        }
                        """)
                .build();
    }

    private static CodeactToolMetadata buildMetadata() {
        return DefaultCodeactToolMetadata.builder()
                .addSupportedLanguage(Language.PYTHON)
                .targetClassName("customer_query_tools")
                .targetClassDescription("High intent customer query tools")
                .fewShots(List.of(new CodeExample(
                        "query high intent customer",
                        "result = high_intent_customer_query(username='张三')",
                        "查询员工的高意向客户")))
                .displayName(TOOL_NAME)
                .returnDirect(true)
                .alwaysAvailable(true)
                .build();
    }
}