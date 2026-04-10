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
package com.alibaba.assistant.agent.start.customer.protocol;

import com.alibaba.assistant.agent.api.protocol.FrontendEvent;
import com.alibaba.assistant.agent.api.protocol.FrontendFormStateSupport;
import com.alibaba.assistant.agent.api.protocol.FrontendStage;
import com.alibaba.assistant.agent.api.protocol.ProtocolPayloadSupport;
import com.alibaba.assistant.agent.api.protocol.ProtocolStrategy;
import com.alibaba.assistant.agent.runtime.agent.AssistantStateKeys;
import com.alibaba.assistant.agent.start.customer.tool.HighIntentCustomerQueryFormTool;
import com.alibaba.assistant.agent.start.customer.tool.HighIntentCustomerQueryTool;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 高意向客户查询协议适配策略。
 */
@Component
@Profile("migration")
@Order(129)
public class HighIntentCustomerWorkflowProtocolStrategy implements ProtocolStrategy {

    private final ProtocolPayloadSupport payloadSupport;

    public HighIntentCustomerWorkflowProtocolStrategy(ProtocolPayloadSupport payloadSupport) {
        this.payloadSupport = payloadSupport;
    }

    @Override
    public boolean supports(String normalizedToolName, Map<String, Object> payload) {
        return HighIntentCustomerQueryFormTool.TOOL_NAME.equals(normalizedToolName)
                || HighIntentCustomerQueryTool.TOOL_NAME.equals(normalizedToolName);
    }

    @Override
    public List<FrontendEvent> adapt(
            String threadId,
            String normalizedToolName,
            Map<String, Object> payload,
            Map<String, Object> state) {
        if (isFormPayload(payload)) {
            return List.of(payloadSupport.formStateEvent(threadId, normalizeFormPayload(payload)));
        }
        return List.of(payloadSupport.resultEvent(threadId, normalizeResultPayload(payload)));
    }

    @Override
    public Map<String, Object> projectThreadState(
            String normalizedToolName,
            Map<String, Object> payload,
            Map<String, Object> state) {
        if (isFormPayload(payload)) {
            return buildFormSnapshot(normalizeFormPayload(payload), state);
        }
        return payloadSupport.projectResultState(normalizeResultPayload(payload), state);
    }

    private boolean isFormPayload(Map<String, Object> payload) {
        return "FORM".equalsIgnoreCase(asText(payload != null ? payload.get("kind") : null))
                || "form".equalsIgnoreCase(asText(payload != null ? payload.get("type") : null));
    }

    private Map<String, Object> normalizeFormPayload(Map<String, Object> payload) {
        Map<String, Object> normalizedPayload = payload != null ? new LinkedHashMap<>(payload) : new LinkedHashMap<>();
        normalizedPayload.put("kind", "FORM");
        normalizedPayload.put("type", "form");
        normalizedPayload.putIfAbsent("title", "查询高意向客户");
        normalizedPayload.putIfAbsent("toolCode", HighIntentCustomerQueryTool.TOOL_NAME);
        normalizedPayload.putIfAbsent("artifactCode", HighIntentCustomerQueryTool.TOOL_NAME);
        normalizedPayload.putIfAbsent("submit_tool", HighIntentCustomerQueryTool.TOOL_NAME);
        Map<String, Object> values = asMap(normalizedPayload.get("values"));
        normalizedPayload.put("values", values);

        List<Map<String, Object>> fields = normalizeFields(normalizedPayload.get("fields"), values);
        normalizedPayload.put("fields", fields);
        List<Map<String, Object>> missingFields = normalizeMissingFields(
                normalizedPayload.get("missingFields"),
                fields,
                values);
        normalizedPayload.put("missingFields", missingFields);

        boolean canSubmit = missingFields.isEmpty() || Boolean.TRUE.equals(normalizedPayload.get("canSubmit"));
        String phase = canSubmit ? FrontendStage.CONFIRMING.name() : FrontendStage.COLLECTING.name();
        String status = canSubmit ? "WAITING_CONFIRMATION" : "WAITING_INPUT";
        normalizedPayload.put("mode", canSubmit ? "CONFIRM" : "COLLECT");
        normalizedPayload.put("phase", phase);
        normalizedPayload.put("status", status);
        normalizedPayload.put("canSubmit", canSubmit);
        normalizedPayload.putIfAbsent("message", canSubmit ? "请确认查询条件后提交。" : "请填写查询条件。");
        normalizedPayload.putIfAbsent("summary", Map.of());
        return FrontendFormStateSupport.normalizePayload(normalizedPayload, phase, status);
    }

    private Map<String, Object> normalizeResultPayload(Map<String, Object> payload) {
        Map<String, Object> normalizedPayload = payload != null ? new LinkedHashMap<>(payload) : new LinkedHashMap<>();
        normalizedPayload.putIfAbsent("toolCode", HighIntentCustomerQueryTool.TOOL_NAME);
        normalizedPayload.putIfAbsent("artifactCode", HighIntentCustomerQueryTool.TOOL_NAME);
        boolean success = !Boolean.FALSE.equals(normalizedPayload.get("success"))
                && !StringUtils.hasText(asText(normalizedPayload.get("error")));
        String username = asText(normalizedPayload.get("username"));
        List<Map<String, Object>> records = normalizeRecords(normalizedPayload.get("records"));
        String message = firstText(
                normalizedPayload.get("message"),
                success
                        ? records.isEmpty()
                        ? "未查询到高意向客户。"
                        : "已查询到%d条高意向客户。".formatted(records.size())
                        : "高意向客户查询失败");
        normalizedPayload.put("success", success);
        normalizedPayload.put("message", message);
        if (!success) {
            normalizedPayload.put("error", firstText(normalizedPayload.get("error"), message));
        }

        Map<String, Object> finalOutputs = new LinkedHashMap<>();
        finalOutputs.put("查询名称", "高意向客户查询");
        putIfHasText(finalOutputs, "员工姓名", username);
        finalOutputs.put("客户数量", String.valueOf(records.size()));
        if (!records.isEmpty()) {
            putIfHasText(finalOutputs, "首个客户", records.get(0).get("客户名称"));
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("templateCode", "HIGH_INTENT_CUSTOMER_RESULT_CARD");
        result.put("title", success ? "高意向客户查询结果" : "高意向客户查询失败");
        result.put("summary", message);
        result.put("text", message);
        result.put("recordType", records.size() > 1 ? "list" : "single");
        result.put("finalOutputs", finalOutputs);
        result.put("highlights", buildHighlights(finalOutputs));
        result.put("sections", buildSections(records, success, message));
        result.put("records", records);
        normalizedPayload.put("result", result);
        return normalizedPayload;
    }

    private List<Map<String, Object>> normalizeFields(Object rawFields, Map<String, Object> values) {
        List<Map<String, Object>> fields = new ArrayList<>();
        for (Map<String, Object> field : asListOfMaps(rawFields)) {
            String name = asText(field.get("name"));
            if (!StringUtils.hasText(name)) {
                continue;
            }
            String title = firstText(field.get("label"), field.get("title"), name);
            String rawType = asText(field.get("type"));
            Object value = field.containsKey("value") ? field.get("value") : values.get(name);
            Map<String, Object> normalizedField = new LinkedHashMap<>();
            normalizedField.put("name", name);
            normalizedField.put("title", title);
            normalizedField.put("label", title);
            normalizedField.put("type", resolveValueType(name, rawType));
            normalizedField.put("uiComponent", resolveUiComponent(rawType));
            normalizedField.put("required", Boolean.TRUE.equals(field.get("required")));
            normalizedField.put("value", value);
            normalizedField.put("editable", true);
            normalizedField.put("displayConfig", Map.of(
                    "showInSummary", true,
                    "inlineEditable", true));
            fields.add(normalizedField);
        }
        return fields;
    }

    private List<Map<String, Object>> normalizeMissingFields(
            Object rawMissingFields,
            List<Map<String, Object>> fields,
            Map<String, Object> values) {
        List<Map<String, Object>> explicitMissing = asListOfMaps(rawMissingFields).stream()
                .map(field -> {
                    String name = asText(field.get("name"));
                    String title = firstText(field.get("title"), name);
                    if (!StringUtils.hasText(name)) {
                        return null;
                    }
                    return Map.<String, Object>of("name", name, "title", title);
                })
                .filter(item -> item != null)
                .toList();
        if (!explicitMissing.isEmpty()) {
            return explicitMissing;
        }
        return fields.stream()
                .filter(field -> Boolean.TRUE.equals(field.get("required")))
                .filter(field -> !StringUtils.hasText(asText(values.get(field.get("name")))))
                .map(field -> Map.<String, Object>of(
                        "name", String.valueOf(field.get("name")),
                        "title", String.valueOf(field.get("title"))))
                .toList();
    }

    private List<Map<String, Object>> normalizeRecords(Object rawRecords) {
        List<Map<String, Object>> records = new ArrayList<>();
        for (Map<String, Object> rawRecord : asListOfMaps(rawRecords)) {
            Map<String, Object> record = new LinkedHashMap<>();
            putMappedValue(record, "客户名称", rawRecord, "name", "customer_name", "customerName", "title");
            putMappedValue(record, "联系人", rawRecord, "contact_name", "contactName", "linkman", "contacts");
            putMappedValue(record, "手机号", rawRecord, "contact_mobile", "contactMobile", "mobile", "phone", "tel");
            putMappedValue(record, "所属人", rawRecord, "belong_name", "belongName", "owner_name", "ownerName", "username");
            putMappedValue(record, "客户等级", rawRecord, "grade", "grade_name", "gradeName");
            putMappedValue(record, "行业", rawRecord, "industry", "industry_name", "industryName");
            putMappedValue(record, "创建时间", rawRecord, "create_time", "createTime", "add_time", "addTime");
            records.add(record.isEmpty() ? new LinkedHashMap<>(rawRecord) : record);
        }
        return records;
    }

    private List<Map<String, Object>> buildHighlights(Map<String, Object> finalOutputs) {
        List<Map<String, Object>> highlights = new ArrayList<>();
        addHighlight(highlights, "员工姓名", finalOutputs.get("员工姓名"));
        addHighlight(highlights, "客户数量", finalOutputs.get("客户数量"));
        addHighlight(highlights, "首个客户", finalOutputs.get("首个客户"));
        return highlights;
    }

    private List<Map<String, Object>> buildSections(
            List<Map<String, Object>> records,
            boolean success,
            String message) {
        if (!success) {
            return List.of(buildSection(
                    "failure",
                    "失败信息",
                    List.of(buildFieldItem("原因", message))));
        }
        if (records.isEmpty()) {
            return List.of(buildSection(
                    "empty",
                    "查询结果",
                    List.of(buildFieldItem("说明", "未查询到高意向客户。"))));
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
                    "customer_" + (index + 1),
                    firstText(record.get("客户名称"), "客户" + (index + 1)),
                    items));
        }
        return sections;
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
        section.put("items", items != null ? items : List.of());
        return section;
    }

    private void addHighlight(List<Map<String, Object>> highlights, String label, Object value) {
        String text = asText(value);
        if (StringUtils.hasText(label) && StringUtils.hasText(text)) {
            highlights.add(buildFieldItem(label, text));
        }
    }

    private void putMappedValue(Map<String, Object> target, String targetKey, Map<String, Object> source, String... aliases) {
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

    private Map<String, Object> buildFormSnapshot(Map<String, Object> formPayload, Map<String, Object> state) {
        String status = FrontendFormStateSupport.normalizedStatus(
                formPayload,
                asText(formPayload.get("phase")),
                asText(formPayload.get("status")));
        FrontendStage stage = FrontendFormStateSupport.normalizedStage(
                formPayload,
                asText(formPayload.get("phase")),
                asText(formPayload.get("status")));

        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("status", status);
        snapshot.put("phase", stage.name());
        snapshot.put("unfinished", !Boolean.TRUE.equals(formPayload.get("readOnly")));
        snapshot.put("canResume", !Boolean.TRUE.equals(formPayload.get("readOnly")));
        putIfHasText(snapshot, "assistantUid", state != null ? state.get(AssistantStateKeys.ASSISTANT_UID) : null);
        snapshot.put("toolCode", HighIntentCustomerQueryTool.TOOL_NAME);
        snapshot.put("pendingCardType", "FORM_CARD");
        snapshot.put("pendingForm", formPayload);
        return snapshot;
    }

    private String resolveUiComponent(String type) {
        if ("input".equalsIgnoreCase(type)) {
            return "text";
        }
        return StringUtils.hasText(type) ? type : "text";
    }

    private String resolveValueType(String name, String type) {
        if ("page".equals(name) || "limit".equals(name) || "number".equalsIgnoreCase(type)) {
            return "number";
        }
        return "string";
    }

    private void putIfHasText(Map<String, Object> target, String key, Object value) {
        String text = asText(value);
        if (StringUtils.hasText(text)) {
            target.put(key, text);
        }
    }

    private String normalizeKey(String key) {
        if (!StringUtils.hasText(key)) {
            return "";
        }
        return key.replaceAll("[\\s_\\-]", "");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            return new LinkedHashMap<>((Map<String, Object>) map);
        }
        return new LinkedHashMap<>();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> asListOfMaps(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                result.add(new LinkedHashMap<>((Map<String, Object>) map));
            }
        }
        return result;
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
}

