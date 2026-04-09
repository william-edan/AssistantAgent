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
package com.alibaba.assistant.agent.start.car.service;

import com.alibaba.assistant.agent.runtime.planner.ToolExecutor;
import com.alibaba.assistant.agent.start.car.model.CarFeeAddRequest;
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
 * Tool-meta backed dependency service for the car fee flow.
 */
@Service
@Profile("migration")
public class CarFeeToolMetaService {

    private static final Logger log = LoggerFactory.getLogger(CarFeeToolMetaService.class);

    private static final String DEFAULT_TENANT = "default";

    public static final String CAR_LOOKUP_TOOL_CODE = "gougu_oa.company_car_info_query";

    public static final String FEE_TYPE_LOOKUP_TOOL_CODE = "gougu_oa.car_fee_type_options";

    public static final String USER_LOOKUP_TOOL_CODE = "gougu_oa.reward_employee_lookup";

    public static final String CAR_FEE_ADD_TOOL_CODE = "gougu_oa.car_fee_add";

    private final ToolExecutor toolExecutor;

    private final ObjectMapper objectMapper;

    public CarFeeToolMetaService(ToolExecutor toolExecutor, ObjectMapper objectMapper) {
        this.toolExecutor = toolExecutor;
        this.objectMapper = objectMapper;
    }

    public List<CarRecord> listCars(@Nullable ToolContext toolContext) {
        return parseCars(execute(CAR_LOOKUP_TOOL_CODE, Map.of(), toolContext));
    }

    public List<OptionRecord> listFeeTypes(@Nullable ToolContext toolContext) {
        return parseOptions(
                execute(FEE_TYPE_LOOKUP_TOOL_CODE, Map.of(), toolContext),
                List.of("id", "value", "dict_value", "code", "type"),
                List.of("title", "name", "label", "dict_label", "dict_name"));
    }

    public List<UserRecord> listUsers(@Nullable ToolContext toolContext) {
        return parseUsers(execute(USER_LOOKUP_TOOL_CODE, Map.of(), toolContext));
    }

    public Optional<CarRecord> findCarById(String carId, @Nullable ToolContext toolContext) {
        if (!StringUtils.hasText(carId)) {
            return Optional.empty();
        }
        return listCars(toolContext).stream()
                .filter(car -> carId.trim().equals(car.id()))
                .findFirst();
    }

    public Optional<UserRecord> findUserById(String userId, @Nullable ToolContext toolContext) {
        if (!StringUtils.hasText(userId)) {
            return Optional.empty();
        }
        return listUsers(toolContext).stream()
                .filter(user -> userId.trim().equals(user.id()))
                .findFirst();
    }

    public AddResult addCarFee(CarFeeAddRequest request, @Nullable ToolContext toolContext) {
        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("car_name", request.carName());
        arguments.put("car_id", request.carId());
        arguments.put("types", request.types());
        arguments.put("title", request.title());
        arguments.put("fee_time", request.feeTime());
        arguments.put("amount", request.amount().stripTrailingZeros().toPlainString());
        arguments.put("handled_name", request.handledName());
        arguments.put("handled", request.handled());
        arguments.put("file_ids", Optional.ofNullable(request.fileIds()).orElse(""));
        arguments.put("content", Optional.ofNullable(request.content()).orElse(""));

        ToolExecutor.ExecutionResult executionResult = execute(CAR_FEE_ADD_TOOL_CODE, arguments, toolContext);
        String message = resolveMessage(executionResult).orElse("新增车辆费用成功");
        String recordId = resolveRecordId(executionResult).orElse(null);
        String rawPayload = summarizePayload(executionResult.payload());
        return new AddResult(true, message, recordId, rawPayload);
    }

    private ToolExecutor.ExecutionResult execute(
            String toolCode,
            Map<String, Object> arguments,
            @Nullable ToolContext toolContext) {
        ToolExecutor.ExecutionResult executionResult = toolExecutor.execute(
                DEFAULT_TENANT,
                toolCode,
                arguments,
                toolContext);
        if (!executionResult.success()) {
            String message = Optional.ofNullable(executionResult.errorMessage())
                    .filter(StringUtils::hasText)
                    .orElse("tool_meta execution failed");
            throw new IllegalStateException(toolCode + " execution failed: " + message);
        }
        log.info(
                "CarFeeToolMetaService#execute - toolCode={}, output={}",
                toolCode,
                summarizePayload(executionResult.outputFields()));
        return executionResult;
    }

    private List<CarRecord> parseCars(ToolExecutor.ExecutionResult executionResult) {
        List<CarRecord> cars = new ArrayList<>();
        for (JsonNode node : flattenToNodes(resolveDataNode(executionResult))) {
            String id = firstText(node, "id", "car_id", "carId");
            String title = firstText(node, "title", "car_name", "carName", "name");
            if (StringUtils.hasText(id) && StringUtils.hasText(title)) {
                cars.add(new CarRecord(id, title));
            }
        }
        return List.copyOf(cars);
    }

    private List<UserRecord> parseUsers(ToolExecutor.ExecutionResult executionResult) {
        List<UserRecord> users = new ArrayList<>();
        for (JsonNode node : flattenToNodes(resolveDataNode(executionResult))) {
            String id = firstText(
                    node,
                    "id",
                    "uid",
                    "user_id",
                    "userId",
                    "employee_id",
                    "employeeId");
            String name = firstText(
                    node,
                    "name",
                    "realname",
                    "real_name",
                    "uname",
                    "nick_name",
                    "nickName");
            if (StringUtils.hasText(id) && StringUtils.hasText(name)) {
                users.add(new UserRecord(id, name));
            }
        }
        return List.copyOf(users);
    }

    private List<OptionRecord> parseOptions(
            ToolExecutor.ExecutionResult executionResult,
            List<String> valueAliases,
            List<String> labelAliases) {
        List<OptionRecord> options = new ArrayList<>();
        for (JsonNode node : flattenToNodes(resolveDataNode(executionResult))) {
            String value = firstText(node, valueAliases.toArray(String[]::new));
            String label = firstText(node, labelAliases.toArray(String[]::new));
            if (StringUtils.hasText(value) && StringUtils.hasText(label)) {
                options.add(new OptionRecord(value, label));
            }
        }
        return List.copyOf(options);
    }

    private Optional<String> resolveMessage(ToolExecutor.ExecutionResult executionResult) {
        JsonNode outputNode = objectMapper.valueToTree(executionResult.outputFields());
        JsonNode payloadNode = objectMapper.valueToTree(executionResult.payload());
        return Optional.ofNullable(firstText(
                outputNode,
                "msg",
                "message",
                "result_message",
                "resultMessage"))
                .or(() -> Optional.ofNullable(firstText(
                        payloadNode.path("finalOutputs"),
                        "message",
                        "msg")))
                .or(() -> Optional.ofNullable(firstText(payloadNode, "message", "error")));
    }

    private Optional<String> resolveRecordId(ToolExecutor.ExecutionResult executionResult) {
        JsonNode outputNode = objectMapper.valueToTree(executionResult.outputFields());
        JsonNode payloadNode = objectMapper.valueToTree(executionResult.payload());
        JsonNode dataNode = resolveDataNode(executionResult);
        return Optional.ofNullable(firstText(
                dataNode,
                "id",
                "aid",
                "return_id",
                "record_id",
                "recordId"))
                .or(() -> Optional.ofNullable(firstText(
                        outputNode,
                        "id",
                        "aid",
                        "return_id",
                        "record_id",
                        "recordId")))
                .or(() -> Optional.ofNullable(firstText(
                        payloadNode.path("finalOutputs"),
                        "id",
                        "aid",
                        "return_id",
                        "record_id",
                        "recordId")));
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
            String text = asText(node.asText());
            return StringUtils.hasText(text) ? text : null;
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

    public record CarRecord(String id, String title) {
    }

    public record OptionRecord(String value, String label) {
    }

    public record UserRecord(String id, String name) {
    }

    public record AddResult(boolean success, String message, String recordId, String rawPayload) {
    }
}
