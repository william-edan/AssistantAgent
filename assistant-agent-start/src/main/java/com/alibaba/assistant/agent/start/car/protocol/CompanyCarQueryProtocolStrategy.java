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
package com.alibaba.assistant.agent.start.car.protocol;

import com.alibaba.assistant.agent.api.protocol.FrontendEvent;
import com.alibaba.assistant.agent.api.protocol.ProtocolPayloadSupport;
import com.alibaba.assistant.agent.api.protocol.ProtocolStrategy;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 公司车辆查询协议适配策略。
 *
 * <p>将 {@code artifact_execute} 的原始执行结果转为前端可直接渲染的结构化卡片格式，
 * 避免前端直接消费执行引擎返回的粗粒度 JSON。</p>
 */
@Component
@Profile("migration")
@Order(119)
public class CompanyCarQueryProtocolStrategy implements ProtocolStrategy {

    private static final String COMPANY_CAR_QUERY_TOOL_CODE = "gougu_oa.company_car_info_query";

    private static final String TEMPLATE_CODE = "PROFILE_CARD";

    private static final String RECORD_TYPE_SINGLE = "single";

    private static final String RECORD_TYPE_LIST = "list";

    private final ProtocolPayloadSupport payloadSupport;

    public CompanyCarQueryProtocolStrategy(ProtocolPayloadSupport payloadSupport) {
        this.payloadSupport = payloadSupport;
    }

    @Override
    public boolean supports(String normalizedToolName, Map<String, Object> payload) {
        if (!"artifact_execute".equals(normalizedToolName) && !"execute_code".equals(normalizedToolName)) {
            return false;
        }
        Map<String, Object> executionResult = asMap(payload != null ? payload.get("result") : null);
        String artifactCode = firstText(
                payload != null ? payload.get("artifactCode") : null,
                executionResult.get("artifactCode"),
                executionResult.get("toolCode"));
        return COMPANY_CAR_QUERY_TOOL_CODE.equals(artifactCode);
    }

    @Override
    public List<FrontendEvent> adapt(
            String threadId,
            String normalizedToolName,
            Map<String, Object> payload,
            Map<String, Object> state) {
        return List.of(payloadSupport.resultEvent(threadId, normalizePayload(payload)));
    }

    @Override
    public Map<String, Object> projectThreadState(
            String normalizedToolName,
            Map<String, Object> payload,
            Map<String, Object> state) {
        return payloadSupport.projectResultState(normalizePayload(payload), state);
    }

    /**
     * 将执行层返回规范化为卡片型结果结构。
     */
    private Map<String, Object> normalizePayload(Map<String, Object> payload) {
        Map<String, Object> normalizedPayload = payload != null ? new LinkedHashMap<>(payload) : new LinkedHashMap<>();
        Map<String, Object> executionResult = asMap(normalizedPayload.get("result"));
        List<Map<String, Object>> records = normalizeRecords(extractRawRecords(executionResult));
        boolean success = isSuccessful(normalizedPayload, executionResult);
        String message = resolveMessage(normalizedPayload, executionResult, success, records.size());

        normalizedPayload.put("success", success);
        normalizedPayload.put("artifactCode", COMPANY_CAR_QUERY_TOOL_CODE);
        normalizedPayload.put("toolCode", COMPANY_CAR_QUERY_TOOL_CODE);
        normalizedPayload.put("message", message);
        normalizedPayload.put("result", success ? buildSuccessResult(records, message) : buildFailureResult(message));
        if (!success) {
            normalizedPayload.put("error", firstText(
                    normalizedPayload.get("error"),
                    executionResult.get("error"),
                    message));
        }
        return normalizedPayload;
    }

    private boolean isSuccessful(Map<String, Object> payload, Map<String, Object> executionResult) {
        return !Boolean.FALSE.equals(payload.get("success"))
                && !Boolean.FALSE.equals(executionResult.get("success"))
                && !StringUtils.hasText(firstText(payload.get("error"), executionResult.get("error")));
    }

    private String resolveMessage(
            Map<String, Object> payload,
            Map<String, Object> executionResult,
            boolean success,
            int recordCount) {
        Map<String, Object> finalOutputs = asMap(executionResult.get("finalOutputs"));
        String message = firstText(payload.get("message"), finalOutputs.get("message"), executionResult.get("message"));
        if (StringUtils.hasText(message)) {
            return message;
        }
        if (!success) {
            return "公司车辆信息查询失败";
        }
        if (recordCount <= 0) {
            return "未查询到公司车辆信息。";
        }
        return "已查询到%d条公司车辆信息。".formatted(recordCount);
    }

    private Map<String, Object> buildSuccessResult(List<Map<String, Object>> records, String message) {
        Map<String, Object> finalOutputs = buildFinalOutputs(records);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("templateCode", TEMPLATE_CODE);
        result.put("title", "公司车辆信息");
        result.put("summary", message);
        result.put("text", message);
        result.put("recordType", records.size() > 1 ? RECORD_TYPE_LIST : RECORD_TYPE_SINGLE);
        result.put("finalOutputs", finalOutputs);
        result.put("highlights", buildHighlights(finalOutputs, records));
        result.put("sections", buildSections(records));
        result.put("records", records);
        result.put("profile", new LinkedHashMap<>(finalOutputs));
        return result;
    }

    private Map<String, Object> buildFailureResult(String message) {
        Map<String, Object> finalOutputs = new LinkedHashMap<>();
        finalOutputs.put("查询名称", "公司车辆信息");
        finalOutputs.put("结果说明", message);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("templateCode", TEMPLATE_CODE);
        result.put("title", "公司车辆信息查询失败");
        result.put("summary", message);
        result.put("text", message);
        result.put("recordType", RECORD_TYPE_SINGLE);
        result.put("finalOutputs", finalOutputs);
        result.put("highlights", List.of(buildFieldItem("状态", "查询失败")));
        result.put("sections", List.of(buildSection(
                "failure",
                "失败信息",
                List.of(buildFieldItem("原因", message)))));
        result.put("records", List.of());
        result.put("profile", Map.of());
        return result;
    }

    private Map<String, Object> buildFinalOutputs(List<Map<String, Object>> records) {
        Map<String, Object> finalOutputs = new LinkedHashMap<>();
        finalOutputs.put("查询名称", "公司车辆信息");
        finalOutputs.put("车辆总数", String.valueOf(records.size()));
        if (records.isEmpty()) {
            return finalOutputs;
        }
        Map<String, Object> firstRecord = records.get(0);
        putText(finalOutputs, "车辆名称", asText(firstRecord.get("车辆名称")));
        putText(finalOutputs, "车牌号", asText(firstRecord.get("车牌号")));
        putText(finalOutputs, "状态", asText(firstRecord.get("状态")));
        putText(finalOutputs, "驾驶员", asText(firstRecord.get("驾驶员")));
        if (records.size() > 1) {
            List<String> names = new ArrayList<>();
            for (int i = 0; i < Math.min(records.size(), 3); i++) {
                String name = asText(records.get(i).get("车辆名称"));
                if (StringUtils.hasText(name)) {
                    names.add(name);
                }
            }
            if (!names.isEmpty()) {
                finalOutputs.put("车辆名称列表", String.join("、", names));
            }
        }
        return finalOutputs;
    }

    private List<Map<String, Object>> buildHighlights(
            Map<String, Object> finalOutputs,
            List<Map<String, Object>> records) {
        List<Map<String, Object>> highlights = new ArrayList<>();
        addHighlight(highlights, "车辆总数", finalOutputs.get("车辆总数"));
        addHighlight(highlights, "车辆名称", finalOutputs.get("车辆名称"));
        addHighlight(highlights, "车牌号", finalOutputs.get("车牌号"));
        addHighlight(highlights, "状态", finalOutputs.get("状态"));
        if (highlights.size() <= 1 && !records.isEmpty()) {
            addHighlight(highlights, "驾驶员", finalOutputs.get("驾驶员"));
        }
        return highlights;
    }

    private List<Map<String, Object>> buildSections(List<Map<String, Object>> records) {
        if (records == null || records.isEmpty()) {
            return List.of(buildSection(
                    "empty",
                    "查询结果",
                    List.of(buildFieldItem("说明", "未查询到公司车辆信息。"))));
        }
        List<Map<String, Object>> sections = new ArrayList<>();
        for (int index = 0; index < records.size(); index++) {
            Map<String, Object> record = records.get(index);
            List<Map<String, Object>> items = new ArrayList<>();
            for (Map.Entry<String, Object> entry : record.entrySet()) {
                String value = asText(entry.getValue());
                if (StringUtils.hasText(entry.getKey()) && StringUtils.hasText(value)) {
                    items.add(buildFieldItem(entry.getKey(), value));
                }
            }
            sections.add(buildSection(
                    "car_" + (index + 1),
                    firstText(record.get("车辆名称"), "车辆" + (index + 1)),
                    items));
        }
        return sections;
    }

    private List<Map<String, Object>> normalizeRecords(List<Map<String, Object>> rawRecords) {
        if (rawRecords == null || rawRecords.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> records = new ArrayList<>();
        for (Map<String, Object> rawRecord : rawRecords) {
            Map<String, Object> record = new LinkedHashMap<>();
            putMappedValue(record, "车辆ID", rawRecord, "id");
            putMappedValue(record, "车辆名称", rawRecord, "title", "car_name", "carName");
            putMappedValue(record, "车牌号", rawRecord, "name", "plate", "plate_no", "plateNo", "car_no", "carNo");
            putMappedValue(record, "座位数", rawRecord, "seats", "seat", "seat_count", "seatCount");
            putMappedValue(record, "颜色", rawRecord, "color", "car_color", "carColor");
            putMappedValue(record, "里程(公里)", rawRecord, "mileage", "odo", "odometer");
            putMappedValue(record, "油耗", rawRecord, "oil", "fuel");
            putMappedValue(record, "车架号(VIN)", rawRecord, "vin");
            putMappedValue(record, "发动机号", rawRecord, "engine", "engine_no", "engineNo");
            putMappedValue(record, "购置日期", rawRecord, "buy_time", "buyTime", "purchase_date", "purchaseDate");
            putMappedValue(record, "购置价格", rawRecord, "price", "buy_price", "buyPrice");
            putMappedValue(record, "保险到期", rawRecord, "insure_time", "insureTime", "insurance_expire", "insuranceExpire");
            putMappedValue(record, "年审到期", rawRecord, "review_time", "reviewTime", "annual_review", "annualReview");
            putMappedValue(record, "驾驶员", rawRecord, "driver_name", "driverName", "driver");
            putMappedValue(record, "备注", rawRecord, "remark", "comment");

            String status = resolveStatusLabel(firstValue(rawRecord, "status", "state"));
            if (StringUtils.hasText(status)) {
                record.put("状态", status);
            }
            records.add(record.isEmpty() ? new LinkedHashMap<>(rawRecord) : record);
        }
        return List.copyOf(records);
    }

    private List<Map<String, Object>> extractRawRecords(Map<String, Object> executionResult) {
        Map<String, Object> finalOutputs = asMap(executionResult.get("finalOutputs"));
        List<Map<String, Object>> records = asListOfMaps(finalOutputs.get("data"));
        if (!records.isEmpty()) {
            return records;
        }
        Map<String, Object> stepResults = asMap(executionResult.get("stepResults"));
        Map<String, Object> queryStep = asMap(stepResults.get("query"));
        Map<String, Object> outputs = asMap(queryStep.get("outputs"));
        records = asListOfMaps(outputs.get("data"));
        if (!records.isEmpty()) {
            return records;
        }
        return asListOfMaps(executionResult.get("data"));
    }

    private void putMappedValue(
            Map<String, Object> target,
            String targetKey,
            Map<String, Object> source,
            String... aliases) {
        Object value = firstValue(source, aliases);
        String text = asText(value);
        if (StringUtils.hasText(targetKey) && StringUtils.hasText(text)) {
            target.put(targetKey, text);
        }
    }

    private Object firstValue(Map<String, Object> source, String... aliases) {
        if (source == null || source.isEmpty() || aliases == null || aliases.length == 0) {
            return null;
        }
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            String normalizedKey = normalizeKey(entry.getKey());
            for (String alias : aliases) {
                if (normalizeKey(alias).equals(normalizedKey)) {
                    return entry.getValue();
                }
            }
        }
        return null;
    }

    private String resolveStatusLabel(Object rawStatus) {
        String text = asText(rawStatus);
        if (!StringUtils.hasText(text)) {
            return null;
        }
        return switch (text) {
            case "1" -> "启用";
            case "0" -> "停用";
            default -> text;
        };
    }

    private void addHighlight(List<Map<String, Object>> highlights, String label, Object value) {
        String text = asText(value);
        if (StringUtils.hasText(label) && StringUtils.hasText(text)) {
            highlights.add(buildFieldItem(label, text));
        }
    }

    private Map<String, Object> buildFieldItem(String label, String value) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("label", label);
        item.put("value", value);
        return item;
    }

    private Map<String, Object> buildSection(String key, String title, List<Map<String, Object>> items) {
        Map<String, Object> section = new LinkedHashMap<>();
        section.put("key", key);
        section.put("title", title);
        section.put("items", items);
        return section;
    }

    private void putText(Map<String, Object> target, String key, String value) {
        if (target != null && StringUtils.hasText(key) && StringUtils.hasText(value)) {
            target.put(key, value);
        }
    }

    private String normalizeKey(String key) {
        if (!StringUtils.hasText(key)) {
            return "";
        }
        return key.replaceAll("[\\s_\\-]", "").toLowerCase(Locale.ROOT);
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

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            return new LinkedHashMap<>((Map<String, Object>) map);
        }
        return Map.of();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> asListOfMaps(Object value) {
        if (!(value instanceof List<?> list) || list.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> records = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> map && !map.isEmpty()) {
                records.add(new LinkedHashMap<>((Map<String, Object>) map));
            }
        }
        return records;
    }
}
