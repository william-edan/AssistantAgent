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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
        normalizedPayload.put("fields", asListOfMaps(normalizedPayload.get("fields")));
        normalizedPayload.put("missingFields", asListOfMaps(normalizedPayload.get("missingFields")));
        normalizedPayload.putIfAbsent("summary", Map.of());
        normalizedPayload.putIfAbsent("mode", isConfirmMode(normalizedPayload) ? "CONFIRM" : "COLLECT");
        normalizedPayload.putIfAbsent("status", isConfirmMode(normalizedPayload) ? "WAITING_CONFIRMATION" : "WAITING_INPUT");
        normalizedPayload.putIfAbsent("phase", isConfirmMode(normalizedPayload)
                ? FrontendStage.CONFIRMING.name()
                : FrontendStage.COLLECTING.name());
        normalizedPayload.putIfAbsent("canSubmit", isConfirmMode(normalizedPayload));
        return FrontendFormStateSupport.normalizePayload(
                normalizedPayload,
                asText(normalizedPayload.get("phase")),
                asText(normalizedPayload.get("status")));
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
