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
package com.alibaba.assistant.agent.start.reward.protocol;

import com.alibaba.assistant.agent.api.protocol.FrontendEvent;
import com.alibaba.assistant.agent.api.protocol.FrontendFormStateSupport;
import com.alibaba.assistant.agent.api.protocol.FrontendStage;
import com.alibaba.assistant.agent.api.protocol.ProtocolPayloadSupport;
import com.alibaba.assistant.agent.api.protocol.ProtocolStrategy;
import com.alibaba.assistant.agent.runtime.agent.AssistantStateKeys;
import com.alibaba.assistant.agent.start.reward.tool.RewardWorkflowTool;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 员工奖惩流程协议适配策略。
 */
@Component
@Profile("migration")
@Order(127)
public class RewardWorkflowProtocolStrategy implements ProtocolStrategy {

    private static final String DEFAULT_SUCCESS_MESSAGE = "员工奖惩处理完成";

    private static final String DEFAULT_FAILURE_MESSAGE = "员工奖惩处理失败";

    private static final String DEFAULT_CARD_SUCCESS_MESSAGE = "处理完成";

    private static final String DEFAULT_CARD_FAILURE_MESSAGE = "处理失败";

    private static final String FAILURE_SEPARATOR = "；";

    private final ProtocolPayloadSupport payloadSupport;

    @SuppressWarnings("unused")
    private final ObjectMapper objectMapper;

    public RewardWorkflowProtocolStrategy(ProtocolPayloadSupport payloadSupport, ObjectMapper objectMapper) {
        this.payloadSupport = payloadSupport;
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean supports(String normalizedToolName, Map<String, Object> payload) {
        return RewardWorkflowTool.TOOL_NAME.equals(normalizedToolName);
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
        return "FORM".equals(asText(payload != null ? payload.get("kind") : null));
    }

    private Map<String, Object> normalizeFormPayload(Map<String, Object> payload) {
        Map<String, Object> normalizedPayload = payload != null ? new LinkedHashMap<>(payload) : new LinkedHashMap<>();
        normalizedPayload.putIfAbsent("toolCode", RewardWorkflowTool.TOOL_NAME);
        normalizedPayload.putIfAbsent("artifactCode", RewardWorkflowTool.TOOL_NAME);
        normalizedPayload.put("values", asMap(normalizedPayload.get("values")));
        normalizedPayload.put("missingFields", normalizeMissingFields(normalizedPayload.get("missingFields")));
        normalizedPayload.put("fields", normalizeRewardFields(
                asListOfMaps(normalizedPayload.get("fields")),
                asMap(normalizedPayload.get("values")),
                asListOfMaps(normalizedPayload.get("missingFields"))));
        normalizedPayload.putIfAbsent("mode", isConfirmMode(normalizedPayload) ? "CONFIRM" : "COLLECT");
        normalizedPayload.putIfAbsent("status", isConfirmMode(normalizedPayload) ? "WAITING_CONFIRMATION" : "WAITING_INPUT");
        normalizedPayload.putIfAbsent("phase", isConfirmMode(normalizedPayload)
                ? FrontendStage.CONFIRMING.name()
                : FrontendStage.COLLECTING.name());
        normalizedPayload.putIfAbsent("canSubmit", isConfirmMode(normalizedPayload));
        Map<String, Object> summary = asMap(normalizedPayload.get("summary"));
        normalizedPayload.put("summary", summary.isEmpty()
                ? buildSummary(asListOfMaps(normalizedPayload.get("fields")))
                : summary);
        return FrontendFormStateSupport.normalizePayload(
                normalizedPayload,
                asText(normalizedPayload.get("phase")),
                asText(normalizedPayload.get("status")));
    }

    private List<Map<String, Object>> normalizeRewardFields(
            List<Map<String, Object>> fields,
            Map<String, Object> values,
            List<Map<String, Object>> missingFields) {
        Set<String> missingNames = new LinkedHashSet<>();
        for (Map<String, Object> missingField : missingFields) {
            String name = asText(missingField.get("name"));
            if (StringUtils.hasText(name)) {
                missingNames.add(name);
            }
        }

        List<Map<String, Object>> normalizedFields = new ArrayList<>();
        for (Map<String, Object> field : fields) {
            String name = asText(field.get("name"));
            if (!StringUtils.hasText(name)) {
                continue;
            }
            Object value = field.containsKey("value") ? field.get("value") : values.get(name);
            String uiComponent = firstText(asText(field.get("uiComponent")), resolveUiComponent(name, field.get("type")));
            List<Map<String, Object>> options = normalizeOptions(field.get("options"));

            Map<String, Object> normalizedField = new LinkedHashMap<>();
            normalizedField.put("name", name);
            normalizedField.put("title", firstText(field.get("title"), name));
            normalizedField.put("type", firstText(resolveValueType(name, uiComponent, value), asText(field.get("type"))));
            normalizedField.put("description", field.get("description"));
            normalizedField.put("required", Boolean.TRUE.equals(field.get("required")));
            normalizedField.put("priority", firstText(asText(field.get("priority")), defaultPriority(name)));
            normalizedField.put("askMode", firstText(asText(field.get("askMode")), "BATCH"));
            normalizedField.put("uiComponent", uiComponent);
            normalizedField.put("displayConfig", mergeDisplayConfig(field.get("displayConfig"), name));
            normalizedField.put("validation", asMap(field.get("validation")));
            normalizedField.put("computed", asMap(field.get("computed")));
            normalizedField.put("editable", !Boolean.FALSE.equals(field.get("editable")));
            normalizedField.put("value", value);
            normalizedField.put("missing", missingNames.contains(name));
            normalizedField.put("options", options);
            normalizedField.put("optionsLoaded", field.containsKey("optionsLoaded")
                    ? field.get("optionsLoaded")
                    : !options.isEmpty());
            normalizedFields.add(normalizedField);
        }
        return normalizedFields;
    }

    private List<Map<String, Object>> normalizeMissingFields(Object rawMissingFields) {
        List<Map<String, Object>> normalized = new ArrayList<>();
        for (Map<String, Object> missingField : asListOfMaps(rawMissingFields)) {
            String name = asText(missingField.get("name"));
            if (!StringUtils.hasText(name)) {
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", name);
            item.put("title", firstText(missingField.get("title"), name));
            normalized.add(item);
        }
        return normalized;
    }

    private List<Map<String, Object>> normalizeOptions(Object rawOptions) {
        List<Map<String, Object>> normalized = new ArrayList<>();
        for (Map<String, Object> option : asListOfMaps(rawOptions)) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("label", firstText(option.get("label"), option.get("value")));
            item.put("value", option.get("value"));
            item.put("disabled", Boolean.TRUE.equals(option.get("disabled")));
            normalized.add(item);
        }
        return normalized;
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
        displayConfig.put("summaryGroup", "remark".equals(name) ? "SECONDARY" : "CORE");
        displayConfig.put("inlineEditable", true);
        if ("remark".equals(name)) {
            displayConfig.put("secondaryGroup", true);
        }
        return displayConfig;
    }

    private Map<String, Object> buildSummary(List<Map<String, Object>> fields) {
        List<Map<String, Object>> summaryItems = new ArrayList<>();
        List<Map<String, Object>> secondaryItems = new ArrayList<>();
        for (Map<String, Object> field : fields) {
            Map<String, Object> displayConfig = asMap(field.get("displayConfig"));
            if (Boolean.FALSE.equals(displayConfig.get("showInSummary"))) {
                continue;
            }
            Object value = field.get("value");
            String displayValue = resolveDisplayValue(field, value);
            if (!StringUtils.hasText(displayValue)) {
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("label", firstText(field.get("title"), field.get("name")));
            item.put("value", displayValue);
            item.put("icon", asText(displayConfig.get("summaryIcon")));
            item.put("order", displayConfig.get("summaryOrder"));
            item.put("group", asText(displayConfig.get("summaryGroup")));
            if (Boolean.TRUE.equals(displayConfig.get("secondaryGroup"))
                    || "SECONDARY".equalsIgnoreCase(asText(displayConfig.get("summaryGroup")))) {
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

    private String resolveDisplayValue(Map<String, Object> field, Object rawValue) {
        String text = asText(rawValue);
        if (!StringUtils.hasText(text)) {
            return text;
        }
        for (Map<String, Object> option : asListOfMaps(field.get("options"))) {
            String optionValue = asText(option.get("value"));
            if (StringUtils.hasText(optionValue) && optionValue.equals(text)) {
                return firstText(option.get("label"), optionValue);
            }
        }
        return text;
    }

    private String resolveUiComponent(String name, Object rawType) {
        String type = asText(rawType);
        if ("types".equals(name) || "rewards_cate".equals(name) || "SELECT".equalsIgnoreCase(type)) {
            return "select";
        }
        if ("rewards_time".equals(name) || "DATE".equalsIgnoreCase(type)) {
            return "date";
        }
        if ("remark".equals(name) || "TEXTAREA".equalsIgnoreCase(type)) {
            return "textarea";
        }
        if ("cost".equals(name)) {
            return "number";
        }
        return "text";
    }

    private String resolveValueType(String name, String uiComponent, Object value) {
        if ("types".equals(name) || value instanceof Integer || value instanceof Long) {
            return "integer";
        }
        if ("cost".equals(name)) {
            return "number";
        }
        if ("select".equals(uiComponent)) {
            return value instanceof Number ? "integer" : "string";
        }
        return "string";
    }

    private String defaultPriority(String name) {
        return "remark".equals(name) ? "SECONDARY" : "CORE";
    }

    private int summaryOrder(String name) {
        return switch (name) {
            case "uname" -> 1;
            case "types" -> 2;
            case "rewards_cate" -> 3;
            case "cost" -> 4;
            case "rewards_time" -> 5;
            case "remark" -> 6;
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
        putIfHasText(snapshot, "assistantUid", asText(state != null ? state.get(AssistantStateKeys.ASSISTANT_UID) : null));
        snapshot.put("toolCode", firstText(formPayload.get("toolCode"), RewardWorkflowTool.TOOL_NAME));
        snapshot.put("pendingCardType", "FORM_CARD");
        snapshot.put("pendingForm", formPayload);
        return snapshot;
    }

    private Map<String, Object> normalizeResultPayload(Map<String, Object> payload) {
        Map<String, Object> normalizedPayload = payload != null ? new LinkedHashMap<>(payload) : new LinkedHashMap<>();
        normalizedPayload.putIfAbsent("toolCode", RewardWorkflowTool.TOOL_NAME);
        normalizedPayload.putIfAbsent("artifactCode", RewardWorkflowTool.TOOL_NAME);

        boolean success = !Boolean.FALSE.equals(normalizedPayload.get("success"))
                && !StringUtils.hasText(asText(normalizedPayload.get("error")));
        String failureDetail = success ? null : resolveFailureDetail(normalizedPayload);
        String message = success
                ? firstText(normalizedPayload.get("message"), normalizedPayload.get("error"), DEFAULT_SUCCESS_MESSAGE)
                : firstText(
                        firstFailureText(normalizedPayload.get("message"), normalizedPayload.get("error"), failureDetail),
                        DEFAULT_FAILURE_MESSAGE);
        normalizedPayload.put("message", message);
        if (!success) {
            normalizedPayload.put("error", firstText(
                    firstFailureText(normalizedPayload.get("error"), failureDetail, normalizedPayload.get("message")),
                    message));
        }
        normalizedPayload.put("result", buildResultCard(normalizedPayload));
        return normalizedPayload;
    }

    private Map<String, Object> buildResultCard(Map<String, Object> payload) {
        boolean success = !Boolean.FALSE.equals(payload.get("success"))
                && !StringUtils.hasText(asText(payload.get("error")));
        String failureDetail = success ? null : resolveFailureDetail(payload);
        String message = success
                ? firstText(payload.get("message"), payload.get("error"), DEFAULT_CARD_SUCCESS_MESSAGE)
                : firstText(
                        firstFailureText(payload.get("error"), failureDetail, payload.get("message")),
                        DEFAULT_CARD_FAILURE_MESSAGE);

        Map<String, Object> finalOutputs = new LinkedHashMap<>();
        finalOutputs.put("处理结果", message);
        copyText(finalOutputs, "员工姓名", payload.get("uname"));
        copyText(finalOutputs, "奖惩分类", payload.get("matchedCategoryName"));
        copyText(finalOutputs, "奖惩记录ID", payload.get("rewardId"));
        copyText(finalOutputs, "处理人数", payload.get("totalUsers"));
        copyText(finalOutputs, "成功人数", payload.get("successCount"));
        copyText(finalOutputs, "失败人数", payload.get("failedCount"));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("templateCode", "REWARD_RESULT_CARD");
        result.put("title", success ? "员工奖惩处理结果" : "员工奖惩处理失败");
        result.put("summary", message);
        result.put("text", message);
        result.put("recordType", "single");
        result.put("finalOutputs", finalOutputs);
        result.put("highlights", finalOutputs.entrySet().stream()
                .limit(4)
                .map(entry -> Map.<String, Object>of("label", entry.getKey(), "value", String.valueOf(entry.getValue())))
                .toList());
        result.put("sections", List.of(Map.of(
                "key", "reward_result",
                "title", "处理摘要",
                "items", finalOutputs.entrySet().stream()
                        .map(entry -> Map.<String, Object>of("label", entry.getKey(), "value", String.valueOf(entry.getValue())))
                        .toList())));
        return result;
    }

    private boolean isConfirmMode(Map<String, Object> payload) {
        return "CONFIRM".equalsIgnoreCase(asText(payload.get("mode")))
                || "CONFIRMING".equalsIgnoreCase(asText(payload.get("phase")))
                || Boolean.TRUE.equals(payload.get("canSubmit"));
    }

    private void copyText(Map<String, Object> target, String key, Object value) {
        String text = asText(value);
        if (StringUtils.hasText(text)) {
            target.put(key, text);
        }
    }

    private void putIfHasText(Map<String, Object> target, String key, String value) {
        if (StringUtils.hasText(key) && StringUtils.hasText(value)) {
            target.put(key, value);
        }
    }

    private String resolveFailureDetail(Map<String, Object> payload) {
        return firstFailureText(
                payload != null ? payload.get("error") : null,
                payload != null ? payload.get("failedSummary") : null,
                summarizeFailedUsers(payload != null ? payload.get("failedUsers") : null),
                summarizeExecutionDetails(payload != null ? payload.get("stepResults") : null),
                summarizeExecutionDetails(payload != null ? payload.get("executionEvents") : null),
                payload != null ? payload.get("message") : null);
    }

    private String summarizeFailedUsers(Object value) {
        List<String> details = asListOfMaps(value).stream()
                .map(item -> {
                    String uname = asText(item.get("uname"));
                    String detail = firstFailureText(item.get("message"), item.get("error"), item.get("reason"));
                    if (StringUtils.hasText(uname) && StringUtils.hasText(detail)) {
                        return uname + "(" + detail + ")";
                    }
                    return firstFailureText(detail, uname);
                })
                .filter(StringUtils::hasText)
                .distinct()
                .limit(3)
                .toList();
        return details.isEmpty() ? null : String.join(FAILURE_SEPARATOR, details);
    }

    private String summarizeExecutionDetails(Object value) {
        List<String> details = asListOfMaps(value).stream()
                .map(this::extractExecutionDetail)
                .filter(StringUtils::hasText)
                .distinct()
                .limit(3)
                .toList();
        return details.isEmpty() ? null : String.join(FAILURE_SEPARATOR, details);
    }

    private String extractExecutionDetail(Map<String, Object> item) {
        Map<String, Object> payload = asMap(item.get("payload"));
        Map<String, Object> outputs = asMap(item.get("outputs"));
        String stepName = firstText(item.get("stepName"), payload.get("stepName"), item.get("label"), item.get("name"));
        String detail = firstFailureText(
                item.get("error"),
                item.get("message"),
                item.get("text"),
                payload.get("error"),
                payload.get("message"),
                payload.get("text"),
                outputs.get("error"),
                outputs.get("message"),
                outputs.get("text"));
        if (StringUtils.hasText(stepName) && StringUtils.hasText(detail) && !detail.startsWith(stepName)) {
            return stepName + ":" + detail;
        }
        return firstFailureText(detail, stepName);
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

    private String firstFailureText(Object... values) {
        if (values == null) {
            return null;
        }
        for (Object value : values) {
            String text = asText(value);
            if (StringUtils.hasText(text) && !isGenericFailureText(text)) {
                return text;
            }
        }
        return null;
    }

    private boolean isGenericFailureText(String text) {
        if (!StringUtils.hasText(text)) {
            return false;
        }
        String normalized = text.trim();
        return DEFAULT_FAILURE_MESSAGE.equals(normalized)
                || DEFAULT_CARD_FAILURE_MESSAGE.equals(normalized);
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
        return new LinkedHashMap<>();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> asListOfMaps(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream()
                .filter(Map.class::isInstance)
                .<Map<String, Object>>map(item -> new LinkedHashMap<>((Map<String, Object>) item))
                .toList();
    }
}
