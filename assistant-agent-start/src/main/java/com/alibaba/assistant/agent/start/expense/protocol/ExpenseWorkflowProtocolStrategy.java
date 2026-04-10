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
package com.alibaba.assistant.agent.start.expense.protocol;

import com.alibaba.assistant.agent.api.protocol.FrontendEvent;
import com.alibaba.assistant.agent.api.protocol.FrontendFormStateSupport;
import com.alibaba.assistant.agent.api.protocol.FrontendStage;
import com.alibaba.assistant.agent.api.protocol.ProtocolPayloadSupport;
import com.alibaba.assistant.agent.api.protocol.ProtocolStrategy;
import com.alibaba.assistant.agent.runtime.agent.AssistantStateKeys;
import com.alibaba.assistant.agent.start.expense.tool.ExpenseAddFormTool;
import com.alibaba.assistant.agent.start.expense.tool.ExpenseAddTool;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 报销流程协议适配器。
 *
 * <p>除了普通字段外，这里会保留多报销项的动态字段描述，保证前端能继续渲染明细列表。</p>
 */
@Component
@Profile("migration")
@Order(130)
public class ExpenseWorkflowProtocolStrategy implements ProtocolStrategy {

    private final ProtocolPayloadSupport payloadSupport;

    public ExpenseWorkflowProtocolStrategy(ProtocolPayloadSupport payloadSupport) {
        this.payloadSupport = payloadSupport;
    }

    @Override
    public boolean supports(String normalizedToolName, Map<String, Object> payload) {
        return ExpenseAddFormTool.TOOL_NAME.equals(normalizedToolName)
                || ExpenseAddTool.TOOL_NAME.equals(normalizedToolName);
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
        normalizedPayload.putIfAbsent("title", "新增报销申请");
        normalizedPayload.putIfAbsent("toolCode", ExpenseAddTool.TOOL_NAME);
        normalizedPayload.putIfAbsent("artifactCode", ExpenseAddTool.TOOL_NAME);
        normalizedPayload.putIfAbsent("submit_tool", ExpenseAddTool.TOOL_NAME);
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
        String mode = canSubmit ? "CONFIRM" : "COLLECT";
        String status = canSubmit ? "WAITING_CONFIRMATION" : "WAITING_INPUT";
        String phase = canSubmit ? FrontendStage.CONFIRMING.name() : FrontendStage.COLLECTING.name();
        normalizedPayload.put("mode", mode);
        normalizedPayload.put("status", status);
        normalizedPayload.put("phase", phase);
        normalizedPayload.put("canSubmit", canSubmit);
        normalizedPayload.putIfAbsent("message", canSubmit ? "请确认报销申请信息后提交。" : "请补全报销申请信息。");
        normalizedPayload.put("summary", buildSummary(fields));
        return FrontendFormStateSupport.normalizePayload(normalizedPayload, phase, status);
    }

    private Map<String, Object> normalizeResultPayload(Map<String, Object> payload) {
        Map<String, Object> normalizedPayload = payload != null ? new LinkedHashMap<>(payload) : new LinkedHashMap<>();
        normalizedPayload.putIfAbsent("toolCode", ExpenseAddTool.TOOL_NAME);
        normalizedPayload.putIfAbsent("artifactCode", ExpenseAddTool.TOOL_NAME);
        boolean success = !Boolean.FALSE.equals(normalizedPayload.get("success"))
                && !StringUtils.hasText(asText(normalizedPayload.get("error")));
        String message = firstText(
                normalizedPayload.get("message"),
                success ? "报销申请提交成功" : "报销申请提交失败");
        normalizedPayload.put("success", success);
        normalizedPayload.put("message", message);
        if (!success) {
            normalizedPayload.put("error", firstText(normalizedPayload.get("error"), message));
        }

        Map<String, Object> finalOutputs = new LinkedHashMap<>();
        putIfHasText(finalOutputs, "处理结果", message);
        putIfHasText(finalOutputs, "凭证编号", normalizedPayload.get("code"));
        putIfHasText(finalOutputs, "报销人", normalizedPayload.get("ptname"));
        putIfHasText(finalOutputs, "金额合计", normalizedPayload.get("totalAmount"));
        putIfHasText(finalOutputs, "记录ID", normalizedPayload.get("recordId"));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("templateCode", "EXPENSE_ADD_RESULT_CARD");
        result.put("title", success ? "报销申请结果" : "报销申请失败");
        result.put("summary", message);
        result.put("text", message);
        result.put("recordType", "single");
        result.put("finalOutputs", finalOutputs);
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
            Object value = field.containsKey("value") ? field.get("value") : values.get(name);
            fields.add(normalizeField(field, value));
        }
        return fields;
    }

    private Map<String, Object> normalizeField(Map<String, Object> field, Object value) {
        String name = asText(field.get("name"));
        String rawType = asText(field.get("type"));
        String uiComponent = firstText(asText(field.get("uiComponent")), resolveUiComponent(rawType));
        List<Map<String, Object>> options = normalizeOptions(field.get("options"));

        Map<String, Object> normalizedField = new LinkedHashMap<>();
        normalizedField.put("name", name);
        normalizedField.put("title", firstText(field.get("label"), field.get("title"), name));
        normalizedField.put("label", firstText(field.get("label"), field.get("title"), name));
        normalizedField.put("type", resolveValueType(rawType, value));
        normalizedField.put("uiComponent", uiComponent);
        normalizedField.put("required", Boolean.TRUE.equals(field.get("required")));
        normalizedField.put("value", value);
        normalizedField.put("editable", !Boolean.FALSE.equals(field.get("editable")));
        normalizedField.put("displayConfig", mergeDisplayConfig(field.get("displayConfig"), name));
        normalizedField.put("options", options);
        normalizedField.put("optionsLoaded", field.containsKey("optionsLoaded")
                ? field.get("optionsLoaded")
                : !options.isEmpty());
        copyIfPresent(normalizedField, field, "remote");
        copyIfPresent(normalizedField, field, "labelField");
        copyIfPresent(normalizedField, field, "valueField");
        copyIfPresent(normalizedField, field, "multiple");
        copyIfPresent(normalizedField, field, "readOnly");
        copyIfPresent(normalizedField, field, "placeholder");
        copyIfPresent(normalizedField, field, "allowAdd");
        copyIfPresent(normalizedField, field, "allowRemove");
        copyIfPresent(normalizedField, field, "minItems");
        copyIfPresent(normalizedField, field, "maxItems");
        copyIfPresent(normalizedField, field, "addButtonText");
        copyIfPresent(normalizedField, field, "defaultItem");
        if (field.containsKey("itemFields")) {
            normalizedField.put("itemFields", normalizeNestedFields(field.get("itemFields")));
        }
        return normalizedField;
    }

    private List<Map<String, Object>> normalizeNestedFields(Object rawFields) {
        List<Map<String, Object>> normalized = new ArrayList<>();
        for (Map<String, Object> field : asListOfMaps(rawFields)) {
            normalized.add(normalizeField(field, field.get("value")));
        }
        return normalized;
    }

    private List<Map<String, Object>> normalizeOptions(Object rawOptions) {
        List<Map<String, Object>> normalized = new ArrayList<>();
        for (Map<String, Object> option : asListOfMaps(rawOptions)) {
            String label = firstText(option.get("label"), option.get("title"), option.get("name"), option.get("value"));
            Object value = option.get("value");
            if (!StringUtils.hasText(label) || value == null) {
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("label", label);
            item.put("value", value);
            if (option.containsKey("disabled")) {
                item.put("disabled", option.get("disabled"));
            }
            normalized.add(item);
        }
        return normalized;
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
        List<Map<String, Object>> missingFields = new ArrayList<>();
        for (Map<String, Object> field : fields) {
            if (!Boolean.TRUE.equals(field.get("required"))) {
                continue;
            }
            String name = asText(field.get("name"));
            if (!StringUtils.hasText(name)) {
                continue;
            }
            if (!hasValue(values.get(name))) {
                missingFields.add(Map.of("name", name, "title", String.valueOf(field.get("title"))));
            }
        }
        return missingFields;
    }

    private Map<String, Object> buildSummary(List<Map<String, Object>> fields) {
        List<Map<String, Object>> summaryItems = new ArrayList<>();
        List<Map<String, Object>> secondaryItems = new ArrayList<>();
        for (Map<String, Object> field : fields) {
            Map<String, Object> displayConfig = asMap(field.get("displayConfig"));
            if (Boolean.FALSE.equals(displayConfig.get("showInSummary"))) {
                continue;
            }
            String displayValue = resolveDisplayValue(field, field.get("value"));
            if (!StringUtils.hasText(displayValue)) {
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("label", firstText(field.get("title"), field.get("name")));
            item.put("value", displayValue);
            item.put("icon", asText(displayConfig.get("summaryIcon")));
            item.put("order", displayConfig.get("summaryOrder"));
            item.put("group", asText(displayConfig.get("summaryGroup")));
            if ("SECONDARY".equalsIgnoreCase(asText(displayConfig.get("summaryGroup")))) {
                secondaryItems.add(item);
            }
            else {
                summaryItems.add(item);
            }
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("summaryItems", summaryItems);
        summary.put("secondaryItems", secondaryItems);
        return summary;
    }

    private String resolveDisplayValue(Map<String, Object> field, Object value) {
        if ("expense_details".equalsIgnoreCase(asText(field.get("uiComponent")))) {
            return summarizeDetails(value, field.get("itemFields"));
        }
        List<Map<String, Object>> options = asListOfMaps(field.get("options"));
        if (value instanceof List<?> list) {
            List<String> labels = new ArrayList<>();
            for (Object item : list) {
                String label = resolveOptionLabel(options, item);
                if (StringUtils.hasText(label)) {
                    labels.add(label);
                }
            }
            return String.join("，", labels);
        }
        String label = resolveOptionLabel(options, value);
        if (StringUtils.hasText(label)) {
            return label;
        }
        return asText(value);
    }

    private String summarizeDetails(Object rawValue, Object rawItemFields) {
        List<Map<String, Object>> itemFields = asListOfMaps(rawItemFields);
        List<Map<String, Object>> details = asListOfMaps(rawValue);
        List<String> parts = new ArrayList<>();
        for (Map<String, Object> detail : details) {
            String cateLabel = resolveNestedOptionLabel(itemFields, "cate_id", detail.get("cate_id"));
            String amount = asText(detail.get("amount"));
            String remarks = asText(detail.get("remarks"));
            StringBuilder builder = new StringBuilder();
            if (StringUtils.hasText(cateLabel)) {
                builder.append(cateLabel);
            }
            if (StringUtils.hasText(amount)) {
                if (builder.length() > 0) {
                    builder.append(' ');
                }
                builder.append(amount);
            }
            if (StringUtils.hasText(remarks)) {
                builder.append("（").append(remarks).append("）");
            }
            if (builder.length() > 0) {
                parts.add(builder.toString());
            }
        }
        return String.join("；", parts);
    }

    private String resolveNestedOptionLabel(List<Map<String, Object>> itemFields, String fieldName, Object value) {
        for (Map<String, Object> itemField : itemFields) {
            if (fieldName.equals(itemField.get("name"))) {
                return firstText(resolveOptionLabel(asListOfMaps(itemField.get("options")), value), asText(value));
            }
        }
        return asText(value);
    }

    private String resolveOptionLabel(List<Map<String, Object>> options, Object value) {
        String text = asText(value);
        if (!StringUtils.hasText(text)) {
            return null;
        }
        for (Map<String, Object> option : options) {
            if (text.equals(asText(option.get("value")))) {
                return firstText(option.get("label"), option.get("title"), option.get("name"));
            }
        }
        return null;
    }

    private Map<String, Object> mergeDisplayConfig(Object rawDisplayConfig, String name) {
        Map<String, Object> displayConfig = new LinkedHashMap<>(defaultDisplayConfig(name));
        displayConfig.putAll(asMap(rawDisplayConfig));
        return displayConfig;
    }

    private Map<String, Object> defaultDisplayConfig(String name) {
        Map<String, Object> displayConfig = new LinkedHashMap<>();
        displayConfig.put("showInSummary", true);
        displayConfig.put("summaryOrder", summaryOrder(name));
        displayConfig.put("summaryGroup", "check_copy_uids".equals(name) ? "SECONDARY" : "CORE");
        displayConfig.put("inlineEditable", true);
        return displayConfig;
    }

    private int summaryOrder(String name) {
        return switch (name) {
            case "subject_id" -> 1;
            case "code" -> 2;
            case "expense_time" -> 3;
            case "income_month" -> 4;
            case "project_id" -> 5;
            case "ptname" -> 6;
            case "details" -> 7;
            case "check_uids" -> 8;
            case "check_copy_uids" -> 9;
            default -> 99;
        };
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
        snapshot.put("toolCode", ExpenseAddTool.TOOL_NAME);
        snapshot.put("pendingCardType", "FORM_CARD");
        snapshot.put("pendingForm", formPayload);
        return snapshot;
    }

    private String resolveUiComponent(String type) {
        if (!StringUtils.hasText(type)) {
            return "text";
        }
        return switch (type.toLowerCase()) {
            case "input" -> "text";
            default -> type;
        };
    }

    private String resolveValueType(String type, Object value) {
        if ("array".equalsIgnoreCase(type) || value instanceof List<?>) {
            return "array";
        }
        if ("number".equalsIgnoreCase(type)) {
            return "number";
        }
        return "string";
    }

    private boolean hasValue(Object value) {
        if (value == null) {
            return false;
        }
        if (value instanceof String text) {
            return StringUtils.hasText(text);
        }
        if (value instanceof List<?> list) {
            return !list.isEmpty();
        }
        if (value instanceof Map<?, ?> map) {
            return !map.isEmpty();
        }
        return true;
    }

    private void copyIfPresent(Map<String, Object> target, Map<String, Object> source, String key) {
        if (source != null && source.containsKey(key)) {
            target.put(key, source.get(key));
        }
    }

    private void putIfHasText(Map<String, Object> target, String key, Object value) {
        String text = asText(value);
        if (StringUtils.hasText(text)) {
            target.put(key, text);
        }
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
