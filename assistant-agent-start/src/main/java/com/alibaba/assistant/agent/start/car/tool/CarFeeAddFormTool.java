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
package com.alibaba.assistant.agent.start.car.tool;

import com.alibaba.assistant.agent.common.enums.Language;
import com.alibaba.assistant.agent.common.tools.CodeExample;
import com.alibaba.assistant.agent.common.tools.CodeactToolMetadata;
import com.alibaba.assistant.agent.common.tools.DefaultCodeactToolMetadata;
import com.alibaba.assistant.agent.extension.dynamic.tool.AbstractDynamicCodeactTool;
import com.alibaba.assistant.agent.start.car.service.CarFeeToolMetaService;
import com.alibaba.assistant.agent.start.car.util.CarFeeFormSummaryParser;
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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Form tool for the car fee creation flow.
 */
@Component
@Profile("migration")
public class CarFeeAddFormTool extends AbstractDynamicCodeactTool {

    public static final String TOOL_NAME = "car_fee_add_form";

    private static final Logger log = LoggerFactory.getLogger(CarFeeAddFormTool.class);

    private final CarFeeToolMetaService carFeeToolMetaService;

    public CarFeeAddFormTool(ObjectMapper objectMapper, CarFeeToolMetaService carFeeToolMetaService) {
        super(objectMapper, buildToolDefinition(), buildMetadata());
        this.carFeeToolMetaService = carFeeToolMetaService;
    }

    @Override
    protected String doCall(Map<String, Object> args, @Nullable ToolContext toolContext) throws Exception {
        FieldOptions fieldOptions = loadFieldOptions(toolContext);
        Map<String, Object> values = resolveValues(args, fieldOptions);

        List<Map<String, Object>> fields = buildFields(values, fieldOptions);
        List<Map<String, Object>> missingFields = fields.stream()
                .filter(field -> Boolean.TRUE.equals(field.get("required")))
                .filter(field -> !StringUtils.hasText(asText(values.get(field.get("name")))))
                .map(field -> Map.<String, Object>of(
                        "name", String.valueOf(field.get("name")),
                        "title", String.valueOf(field.get("label"))))
                .toList();

        boolean canSubmit = missingFields.isEmpty();
        String mode = canSubmit ? "CONFIRM" : "COLLECT";
        String phase = canSubmit ? "CONFIRMING" : "COLLECTING";
        String status = canSubmit ? "WAITING_CONFIRMATION" : "WAITING_INPUT";
        String message = canSubmit ? "请确认车辆费用信息后提交。" : "请填写车辆费用信息。";

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("kind", "FORM");
        payload.put("type", "form");
        payload.put("title", "新增车辆费用");
        payload.put("toolCode", CarFeeAddTool.TOOL_NAME);
        payload.put("artifactCode", CarFeeAddTool.TOOL_NAME);
        payload.put("submit_tool", CarFeeAddTool.TOOL_NAME);
        payload.put("mode", mode);
        payload.put("phase", phase);
        payload.put("status", status);
        payload.put("message", message);
        payload.put("values", values);
        payload.put("fields", fields);
        payload.put("missingFields", missingFields);
        payload.put("summary", Map.of());
        payload.put("canSubmit", canSubmit);
        return objectMapper.writeValueAsString(payload);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> resolveValues(Map<String, Object> args, FieldOptions fieldOptions) {
        Map<String, Object> values = new LinkedHashMap<>();
        Object rawValues = args.get("values");
        if (rawValues instanceof Map<?, ?> valueMap) {
            valueMap.forEach((key, value) -> {
                if (key != null && value != null) {
                    values.put(String.valueOf(key), value);
                }
            });
        }
        mergeSummaryValues(values, asText(args.get("userInput")), fieldOptions);
        Object rawSlotInputs = args.get("slotInputs");
        if (rawSlotInputs instanceof Map<?, ?> slotInputMap) {
            slotInputMap.forEach((key, value) -> {
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
        return values;
    }

    private void mergeSummaryValues(
            Map<String, Object> values,
            @Nullable String userInput,
            FieldOptions fieldOptions) {
        Map<String, String> summaryValues = CarFeeFormSummaryParser.extractRawValues(userInput);
        if (summaryValues.isEmpty()) {
            return;
        }
        putMatchedOption(values, "car_id", summaryValues.get("car_id"), fieldOptions.carOptions());
        putMatchedOption(values, "types", summaryValues.get("types"), fieldOptions.feeTypeOptions());
        putMatchedOption(values, "handled", summaryValues.get("handled"), fieldOptions.userOptions());
        putIfHasText(values, "title", summaryValues.get("title"));
        putIfHasText(values, "fee_time", summaryValues.get("fee_time"));
        putIfHasText(values, "amount", summaryValues.get("amount"));
        putIfHasText(values, "file_ids", summaryValues.get("file_ids"));
        putIfHasText(values, "content", summaryValues.get("content"));
    }

    private boolean isBusinessField(String key) {
        return StringUtils.hasText(key)
                && !"values".equals(key)
                && !"slotInputs".equals(key)
                && !"userInput".equals(key)
                && !"confirmed".equals(key)
                && !"frontendThreadState".equals(key);
    }

    private FieldOptions loadFieldOptions(@Nullable ToolContext toolContext) {
        List<Map<String, Object>> carOptions = loadCarOptions(toolContext);
        List<Map<String, Object>> feeTypeOptions = loadFeeTypeOptions(toolContext);
        List<Map<String, Object>> userOptions = loadUserOptions(toolContext);
        return new FieldOptions(carOptions, feeTypeOptions, userOptions);
    }

    private List<Map<String, Object>> loadCarOptions(@Nullable ToolContext toolContext) {
        try {
            return carFeeToolMetaService.listCars(toolContext).stream()
                    .map(car -> option(car.title(), car.id()))
                    .toList();
        }
        catch (Exception exception) {
            log.warn(
                    "CarFeeAddFormTool#loadCarOptions - preload failed, fallback to frontend remote, error={}",
                    exception.getMessage());
            return List.of();
        }
    }

    private List<Map<String, Object>> loadFeeTypeOptions(@Nullable ToolContext toolContext) {
        try {
            return carFeeToolMetaService.listFeeTypes(toolContext).stream()
                    .map(option -> option(option.label(), option.value()))
                    .toList();
        }
        catch (Exception exception) {
            log.warn(
                    "CarFeeAddFormTool#loadFeeTypeOptions - preload failed, fallback to frontend remote, error={}",
                    exception.getMessage());
            return List.of();
        }
    }

    private List<Map<String, Object>> loadUserOptions(@Nullable ToolContext toolContext) {
        try {
            return carFeeToolMetaService.listUsers(toolContext).stream()
                    .map(user -> option(user.name(), user.id()))
                    .toList();
        }
        catch (Exception exception) {
            log.warn(
                    "CarFeeAddFormTool#loadUserOptions - preload failed, fallback to frontend remote, error={}",
                    exception.getMessage());
            return List.of();
        }
    }

    private List<Map<String, Object>> buildFields(Map<String, Object> values, FieldOptions fieldOptions) {
        List<Map<String, Object>> fields = new ArrayList<>();
        fields.add(field("车辆", "car_id", "select", values.get("car_id"), true, Map.of(
                "remote", "/adm/car/datalist?page=1&limit=20&keywords=",
                "labelField", "title",
                "valueField", "id",
                "options", fieldOptions.carOptions(),
                "optionsLoaded", !fieldOptions.carOptions().isEmpty())));
        fields.add(field("费用类型", "types", "select", values.get("types"), true, Map.of(
                "remote", "/adm/basic/datalist?types=1",
                "labelField", "title",
                "valueField", "id",
                "options", fieldOptions.feeTypeOptions(),
                "optionsLoaded", !fieldOptions.feeTypeOptions().isEmpty())));
        fields.add(field("费用主题", "title", "input", values.get("title"), true, Map.of()));
        fields.add(field("费用日期", "fee_time", "date", values.get("fee_time"), true, Map.of()));
        fields.add(field("费用金额", "amount", "number", values.get("amount"), true, Map.of()));
        fields.add(field("经手人", "handled", "select", values.get("handled"), true, Map.of(
                "remote", "/api/oa_integration/get_all_users",
                "labelField", "name",
                "valueField", "id",
                "options", fieldOptions.userOptions(),
                "optionsLoaded", !fieldOptions.userOptions().isEmpty())));
        fields.add(field("附件", "file_ids", "upload", values.get("file_ids"), false, Map.of()));
        fields.add(field("备注", "content", "textarea", values.get("content"), false, Map.of()));
        return fields;
    }

    private Map<String, Object> field(
            String label,
            String name,
            String type,
            Object value,
            boolean required,
            Map<String, Object> extra) {
        Map<String, Object> field = new LinkedHashMap<>();
        field.put("label", label);
        field.put("title", label);
        field.put("name", name);
        field.put("type", type);
        field.put("value", value);
        field.put("required", required);
        if (extra != null && !extra.isEmpty()) {
            field.putAll(extra);
        }
        return field;
    }

    private Map<String, Object> option(String label, String value) {
        return Map.of("label", label, "value", value);
    }

    private void putMatchedOption(
            Map<String, Object> values,
            String fieldName,
            @Nullable String rawValue,
            List<Map<String, Object>> options) {
        String matchedValue = matchOptionValue(rawValue, options);
        if (StringUtils.hasText(matchedValue)) {
            values.put(fieldName, matchedValue);
        }
    }

    private String matchOptionValue(@Nullable String rawValue, List<Map<String, Object>> options) {
        if (!StringUtils.hasText(rawValue)) {
            return null;
        }
        String normalizedRawValue = normalizeComparisonText(rawValue);
        for (Map<String, Object> option : options) {
            String valueText = asText(option.get("value"));
            String labelText = firstText(option.get("label"), option.get("title"), option.get("name"));
            if (normalizedRawValue.equals(normalizeComparisonText(valueText))
                    || normalizedRawValue.equals(normalizeComparisonText(labelText))) {
                return valueText;
            }
        }
        return null;
    }

    private String normalizeComparisonText(@Nullable String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        return value.replaceAll("[\\p{P}\\p{S}\\s]+", "").toLowerCase(Locale.ROOT);
    }

    private void putIfHasText(Map<String, Object> values, String key, @Nullable String value) {
        if (StringUtils.hasText(value)) {
            values.put(key, value);
        }
    }

    private String firstText(Object... values) {
        if (values == null) {
            return null;
        }
        for (Object value : values) {
            String text = asText(value);
            if (StringUtils.hasText(text)) {
                return text;
            }
        }
        return null;
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
                .description("Return the car fee creation form schema for frontend rendering.")
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
                .targetClassName("car_fee_tools")
                .targetClassDescription("Car fee form tools")
                .fewShots(List.of(new CodeExample(
                        "open car fee form",
                        "result = car_fee_add_form(userInput='我要新增车辆费用')",
                        "返回车辆费用表单")))
                .displayName(TOOL_NAME)
                .returnDirect(true)
                .alwaysAvailable(true)
                .build();
    }

    private record FieldOptions(
            List<Map<String, Object>> carOptions,
            List<Map<String, Object>> feeTypeOptions,
            List<Map<String, Object>> userOptions) {
    }
}
