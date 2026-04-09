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
package com.alibaba.assistant.agent.start.seal.protocol;

import com.alibaba.assistant.agent.api.protocol.FrontendEvent;
import com.alibaba.assistant.agent.api.protocol.FrontendFormStateSupport;
import com.alibaba.assistant.agent.api.protocol.FrontendStage;
import com.alibaba.assistant.agent.api.protocol.ProtocolPayloadSupport;
import com.alibaba.assistant.agent.api.protocol.ProtocolStrategy;
import com.alibaba.assistant.agent.runtime.agent.AssistantStateKeys;
import com.alibaba.assistant.agent.start.seal.tool.SealApplyFormTool;
import com.alibaba.assistant.agent.start.seal.tool.SealApplyTool;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 用章申请协议适配器。
 *
 * <p>将本地工具返回的 FORM/RESULT payload 统一转换成前端协议。</p>
 */
@Component
@Profile("migration")
@Order(129)
public class SealWorkflowProtocolStrategy implements ProtocolStrategy {

    private final ProtocolPayloadSupport payloadSupport;

    public SealWorkflowProtocolStrategy(ProtocolPayloadSupport payloadSupport) {
        this.payloadSupport = payloadSupport;
    }

    @Override
    public boolean supports(String normalizedToolName, Map<String, Object> payload) {
        return SealApplyFormTool.TOOL_NAME.equals(normalizedToolName)
                || SealApplyTool.TOOL_NAME.equals(normalizedToolName);
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
        normalizedPayload.putIfAbsent("title", "新增用章申请");
        normalizedPayload.putIfAbsent("toolCode", SealApplyTool.TOOL_NAME);
        normalizedPayload.putIfAbsent("artifactCode", SealApplyTool.TOOL_NAME);
        normalizedPayload.putIfAbsent("submit_tool", SealApplyTool.TOOL_NAME);
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
        normalizedPayload.putIfAbsent("message", canSubmit ? "请确认用章申请信息后提交。" : "请补全用章申请信息。");
        normalizedPayload.putIfAbsent("summary", Map.of());
        return FrontendFormStateSupport.normalizePayload(normalizedPayload, phase, status);
    }

    private Map<String, Object> normalizeResultPayload(Map<String, Object> payload) {
        Map<String, Object> normalizedPayload = payload != null ? new LinkedHashMap<>(payload) : new LinkedHashMap<>();
        normalizedPayload.putIfAbsent("toolCode", SealApplyTool.TOOL_NAME);
        normalizedPayload.putIfAbsent("artifactCode", SealApplyTool.TOOL_NAME);
        boolean success = !Boolean.FALSE.equals(normalizedPayload.get("success"))
                && !StringUtils.hasText(asText(normalizedPayload.get("error")));
        String message = firstText(
                normalizedPayload.get("message"),
                success ? "用章申请提交成功" : "用章申请提交失败");
        normalizedPayload.put("success", success);
        normalizedPayload.put("message", message);
        if (!success) {
            normalizedPayload.put("error", firstText(normalizedPayload.get("error"), message));
        }

        Map<String, Object> finalOutputs = new LinkedHashMap<>();
        putIfHasText(finalOutputs, "处理结果", message);
        putIfHasText(finalOutputs, "主题", normalizedPayload.get("title"));
        putIfHasText(finalOutputs, "用章日期", normalizedPayload.get("use_time"));
        putIfHasText(finalOutputs, "审批人", normalizedPayload.get("check_unames"));
        putIfHasText(finalOutputs, "记录ID", normalizedPayload.get("recordId"));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("templateCode", "SEAL_APPLY_RESULT_CARD");
        result.put("title", success ? "用章申请结果" : "用章申请失败");
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
            String title = firstText(field.get("label"), field.get("title"), name);
            String rawType = asText(field.get("type"));
            Object value = field.containsKey("value") ? field.get("value") : values.get(name);
            List<Map<String, Object>> options = normalizeOptions(field.get("options"));

            Map<String, Object> normalizedField = new LinkedHashMap<>();
            normalizedField.put("name", name);
            normalizedField.put("title", title);
            normalizedField.put("label", title);
            normalizedField.put("type", resolveValueType(rawType));
            normalizedField.put("uiComponent", resolveUiComponent(rawType));
            normalizedField.put("required", Boolean.TRUE.equals(field.get("required")));
            normalizedField.put("value", value);
            normalizedField.put("editable", true);
            normalizedField.put("displayConfig", Map.of(
                    "showInSummary", true,
                    "inlineEditable", true));
            normalizedField.put("options", options);
            normalizedField.put("optionsLoaded", field.containsKey("optionsLoaded")
                    ? field.get("optionsLoaded")
                    : !options.isEmpty());
            copyIfPresent(normalizedField, field, "remote");
            copyIfPresent(normalizedField, field, "labelField");
            copyIfPresent(normalizedField, field, "valueField");
            fields.add(normalizedField);
        }
        return fields;
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
        return fields.stream()
                .filter(field -> Boolean.TRUE.equals(field.get("required")))
                .filter(field -> !StringUtils.hasText(asText(values.get(field.get("name")))))
                .map(field -> Map.<String, Object>of(
                        "name", String.valueOf(field.get("name")),
                        "title", String.valueOf(field.get("title"))))
                .toList();
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
        snapshot.put("toolCode", SealApplyTool.TOOL_NAME);
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

    private String resolveValueType(String type) {
        if ("number".equalsIgnoreCase(type)) {
            return "number";
        }
        return "string";
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
