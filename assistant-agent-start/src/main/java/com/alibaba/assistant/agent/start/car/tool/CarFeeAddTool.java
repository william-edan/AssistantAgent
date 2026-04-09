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
import com.alibaba.assistant.agent.runtime.agent.AssistantStateKeys;
import com.alibaba.assistant.agent.start.car.model.CarFeeAddRequest;
import com.alibaba.assistant.agent.start.car.service.CarFeeToolMetaService;
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

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 车辆费用新增提交工具。
 */
@Component
@Profile("migration")
public class CarFeeAddTool extends AbstractDynamicCodeactTool {

    private static final Logger log = LoggerFactory.getLogger(CarFeeAddTool.class);

    public static final String TOOL_NAME = "car_fee_add";

    private static final Set<String> REQUIRED_FIELDS = Set.of(
            "car_id",
            "types",
            "title",
            "fee_time",
            "amount",
            "handled");

    private final CarFeeToolMetaService carFeeToolMetaService;

    public CarFeeAddTool(ObjectMapper objectMapper, CarFeeToolMetaService carFeeToolMetaService) {
        super(objectMapper, buildToolDefinition(), buildMetadata());
        this.carFeeToolMetaService = carFeeToolMetaService;
    }

    @Override
    protected String doCall(Map<String, Object> args, @Nullable ToolContext toolContext) throws Exception {
        try {
            // 1) 优先合并前端pendingForm和当前轮输入，保证确认提交场景参数不丢失。
            Map<String, Object> values = resolveValues(args, toolContext);

            // 2) 核心必填校验。
            List<String> missingFields = REQUIRED_FIELDS.stream()
                    .filter(field -> !StringUtils.hasText(asText(values.get(field))))
                    .sorted()
                    .toList();
            if (!missingFields.isEmpty()) {
                return objectMapper.writeValueAsString(errorPayload(
                        "请补全必要字段: " + String.join("、", missingFields),
                        values,
                        missingFields));
            }

            BigDecimal amount = parseAmount(values.get("amount"));
            if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
                return objectMapper.writeValueAsString(errorPayload(
                        "费用金额必须大于0",
                        values,
                        List.of("amount")));
            }

            // 3) 自动补全 car_name 和 handled_name。
            String carId = asText(values.get("car_id"));
            String handledId = asText(values.get("handled"));
            String carName = firstText(
                    values.get("car_name"),
                    carFeeToolMetaService.findCarById(carId, toolContext).map(CarFeeToolMetaService.CarRecord::title).orElse(null));
            if (!StringUtils.hasText(carName)) {
                return objectMapper.writeValueAsString(errorPayload(
                        "未找到对应车辆，请重新选择车辆",
                        values,
                        List.of("car_id")));
            }
            String handledName = firstText(
                    values.get("handled_name"),
                    carFeeToolMetaService.findUserById(handledId, toolContext).map(CarFeeToolMetaService.UserRecord::name).orElse(null));
            if (!StringUtils.hasText(handledName)) {
                return objectMapper.writeValueAsString(errorPayload(
                        "未找到经手人，请重新选择经手人",
                        values,
                        List.of("handled")));
            }

            CarFeeAddRequest request = new CarFeeAddRequest(
                    carName,
                    carId,
                    asText(values.get("types")),
                    asText(values.get("title")),
                    asText(values.get("fee_time")),
                    amount,
                    handledName,
                    handledId,
                    asText(values.get("file_ids")),
                    asText(values.get("content")));

            // 4) 通过 tool_meta 执行真实新增接口。
            CarFeeToolMetaService.AddResult addResult = carFeeToolMetaService.addCarFee(request, toolContext);
            return objectMapper.writeValueAsString(successPayload(addResult, request));
        }
        catch (Exception exception) {
            String message = Optional.ofNullable(exception.getMessage())
                    .filter(StringUtils::hasText)
                    .orElse("新增车辆费用失败");
            log.warn("CarFeeAddTool#doCall - failed, error={}", message, exception);
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
                && !"frontendThreadState".equals(key);
    }

    private OverAllState extractState(@Nullable ToolContext toolContext) {
        if (toolContext == null || toolContext.getContext() == null) {
            return null;
        }
        Object rawState = toolContext.getContext().get(ToolContextConstants.AGENT_STATE_CONTEXT_KEY);
        return rawState instanceof OverAllState state ? state : null;
    }

    private BigDecimal parseAmount(Object rawAmount) {
        String text = asText(rawAmount);
        if (!StringUtils.hasText(text)) {
            return null;
        }
        try {
            return new BigDecimal(text);
        }
        catch (Exception ignored) {
            return null;
        }
    }

    private Map<String, Object> successPayload(CarFeeToolMetaService.AddResult result, CarFeeAddRequest request) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("kind", "RESULT");
        payload.put("success", true);
        payload.put("toolCode", TOOL_NAME);
        payload.put("artifactCode", TOOL_NAME);
        payload.put("message", firstText(result.message(), "新增车辆费用成功"));
        payload.put("recordId", result.recordId());
        payload.put("car_name", request.carName());
        payload.put("car_id", request.carId());
        payload.put("handled_name", request.handledName());
        payload.put("handled", request.handled());
        payload.put("amount", request.amount().stripTrailingZeros().toPlainString());
        return payload;
    }

    private Map<String, Object> errorPayload(
            String message,
            Map<String, Object> values,
            List<String> missingFields) {
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
                    .map(name -> Map.<String, Object>of("name", name, "title", name))
                    .toList());
        }
        return payload;
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
                .description("提交车辆费用新增请求，并自动补全车名和经手人姓名。")
                .inputSchema("""
                        {
                          "type": "object",
                          "properties": {
                            "car_id": {"type": "string"},
                            "types": {"type": "string"},
                            "title": {"type": "string"},
                            "fee_time": {"type": "string"},
                            "amount": {"type": "string"},
                            "handled": {"type": "string"},
                            "file_ids": {"type": "string"},
                            "content": {"type": "string"},
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
                .targetClassName("car_fee_tools")
                .targetClassDescription("车辆费用提交工具")
                .fewShots(List.of(new CodeExample(
                        "submit car fee",
                        "result = car_fee_add(car_id='1', types='2', title='保养费', fee_time='2026-04-09', amount='100', handled='12')",
                        "提交车辆费用新增")))
                .displayName(TOOL_NAME)
                .returnDirect(true)
                .alwaysAvailable(true)
                .build();
    }
}

