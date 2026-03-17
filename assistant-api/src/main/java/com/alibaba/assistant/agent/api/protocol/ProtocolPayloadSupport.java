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
package com.alibaba.assistant.agent.api.protocol;

import com.alibaba.assistant.agent.controlplane.toolregistry.ToolMeta;
import com.alibaba.assistant.agent.common.chat.FrontendTaskStateSupport;
import com.alibaba.assistant.agent.common.util.StructuredValueSanitizer;
import com.alibaba.assistant.agent.runtime.agent.AssistantStateKeys;
import com.alibaba.assistant.agent.slot.model.ToolMetaSnapshot;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Shared frontend protocol payload builder.
 */
@Component
@Profile("migration")
public class ProtocolPayloadSupport {

    public List<FrontendEvent> adaptCollect(String threadId, Map<String, Object> payload, Map<String, Object> state) {
        return List.of(newEvent(
                threadId,
                FrontendEventType.FORM_STATE,
                resolveStage(payload, FrontendStage.COLLECTING),
                buildFormPayload(payload, state, "COLLECT")));
    }

    public List<FrontendEvent> adaptConfirm(String threadId, Map<String, Object> payload, Map<String, Object> state) {
        return List.of(newEvent(
                threadId,
                FrontendEventType.FORM_STATE,
                resolveStage(payload, FrontendStage.CONFIRMING),
                buildConfirmPayload(payload, state)));
    }

    public List<FrontendEvent> adaptExecutionResult(
            String threadId,
            Map<String, Object> payload,
            Map<String, Object> state) {
        List<FrontendEvent> events = new ArrayList<>();
        Map<String, Object> taskPayload = buildRunningTaskPayload(payload, state);
        if (!taskPayload.isEmpty()) {
            events.add(taskStateEvent(threadId, taskPayload));
        }
        if (isWaitingApprovalPayload(payload)) {
            return events;
        }
        events.add(newEvent(
                threadId,
                FrontendEventType.RESULT,
                resolveResultStage(payload),
                buildResultPayload(payload, state)));
        return events;
    }

    public List<FrontendEvent> adaptVisibleMessage(String threadId, Map<String, Object> payload, String rawOutput) {
        String text = extractMessageText(payload, rawOutput);
        if (!FrontendMessageVisibilitySupport.isVisibleAssistantText(text)) {
            return List.of();
        }
        return List.of(messageEvent(threadId, text));
    }

    public List<FrontendEvent> adaptGenericTask(String threadId, Map<String, Object> payload, Map<String, Object> state) {
        Map<String, Object> taskPayload = buildGenericTaskPayload(payload, state);
        if (taskPayload.isEmpty()) {
            return List.of();
        }
        return List.of(taskStateEvent(threadId, taskPayload));
    }

    public FrontendEvent stageEvent(String threadId, FrontendStage stage, Map<String, Object> payload) {
        return newEvent(threadId, FrontendEventType.STAGE, stage, payload);
    }

    public FrontendEvent messageEvent(String threadId, String text) {
        return newEvent(
                threadId,
                FrontendEventType.MESSAGE,
                FrontendStage.DONE,
                Map.of("text", text != null ? text : ""));
    }

    public FrontendEvent formStateEvent(String threadId, Map<String, Object> payload) {
        Map<String, Object> normalizedPayload = FrontendFormStateSupport.normalizePayload(
                payload,
                asText(payload != null ? payload.get("phase") : null),
                asText(payload != null ? payload.get("status") : null));
        FrontendStage stage = FrontendFormStateSupport.normalizedStage(
                normalizedPayload,
                asText(normalizedPayload.get("phase")),
                asText(normalizedPayload.get("status")));
        return newEvent(threadId, FrontendEventType.FORM_STATE, stage, normalizedPayload);
    }

    public FrontendEvent taskStateEvent(String threadId, Map<String, Object> payload) {
        Map<String, Object> normalizedPayload = FrontendTaskStateSupport.normalizePayload(payload);
        return newEvent(threadId, FrontendEventType.TASK_STATE, resolveTaskStage(normalizedPayload), normalizedPayload);
    }

    public Map<String, Object> executionTaskPayload(Map<String, Object> executionPayload) {
        return FrontendTaskStateSupport.normalizePayload(buildExecutionTaskPayload(executionPayload, null));
    }

    public FrontendEvent executionProgressEvent(String threadId, Map<String, Object> payload) {
        return newEvent(threadId, FrontendEventType.EXECUTION_PROGRESS, resolveExecutionStage(payload), payload);
    }

    public FrontendEvent resultEvent(String threadId, Map<String, Object> payload) {
        return newEvent(threadId, FrontendEventType.RESULT, resolveResultStage(payload), buildResultPayload(payload, null));
    }

    public FrontendEvent errorEvent(String threadId, String code, Map<String, Object> payload) {
        Map<String, Object> errorPayload = new LinkedHashMap<>();
        errorPayload.put("code", code);
        if (payload != null && !payload.isEmpty()) {
            errorPayload.putAll(payload);
        }
        return newEvent(threadId, FrontendEventType.ERROR, FrontendStage.ERROR, errorPayload);
    }

    public Map<String, Object> projectCollectState(Map<String, Object> payload, Map<String, Object> state) {
        FrontendStage stage = resolveStage(payload, FrontendStage.COLLECTING);
        String status = stage == FrontendStage.CONFIRMING ? "WAITING_CONFIRMATION" : "WAITING_INPUT";
        Map<String, Object> snapshot = baseSnapshot(status, stage, true, true, state);
        snapshot.put("toolCode", resolveToolCode(payload, state));
        snapshot.put("pendingCardType", "FORM_CARD");
        snapshot.put("pendingForm", buildFormPayload(payload, state, stage == FrontendStage.CONFIRMING ? "CONFIRM" : "COLLECT"));
        return snapshot;
    }

    public Map<String, Object> projectConfirmState(Map<String, Object> payload, Map<String, Object> state) {
        Map<String, Object> confirmForm = asMap(payload.get("confirmForm"));
        if (confirmForm.isEmpty()) {
            return projectCollectState(payload, state);
        }
        Map<String, Object> snapshot = baseSnapshot("WAITING_CONFIRMATION", FrontendStage.CONFIRMING, true, true, state);
        snapshot.put("toolCode", resolveToolCode(confirmForm, state));
        snapshot.put("pendingCardType", "FORM_CARD");
        snapshot.put("pendingForm", buildConfirmPayload(payload, state));
        return snapshot;
    }

    public Map<String, Object> projectResultState(Map<String, Object> payload, Map<String, Object> state) {
        if (isWaitingApprovalPayload(payload)) {
            Map<String, Object> task = FrontendTaskStateSupport.normalizePayload(buildRunningTaskPayload(payload, state));
            Map<String, Object> snapshot = baseSnapshot("WAITING_APPROVAL", FrontendStage.WAITING_APPROVAL, true, true, state);
            snapshot.put("toolCode", resolveToolCode(payload, state));
            snapshot.put("pendingCardType", "TASK_CARD");
            if (!task.isEmpty()) {
                task.put("status", "WAITING_APPROVAL");
                task.put("resultReady", Boolean.FALSE);
                snapshot.put("tasks", List.of(task));
            }
            return snapshot;
        }
        boolean success = !Boolean.FALSE.equals(payload.get("success"))
                && !StringUtils.hasText(asText(payload.get("error")));
        FrontendStage stage = success ? FrontendStage.DONE : FrontendStage.ERROR;
        Map<String, Object> snapshot = baseSnapshot(success ? "COMPLETED" : "FAILED", stage, false, false, state);
        snapshot.put("toolCode", resolveToolCode(payload, state));
        snapshot.put("pendingCardType", "RESULT_CARD");
        snapshot.put("lastResult", buildResultPayload(payload, state));
        Map<String, Object> task = FrontendTaskStateSupport.normalizePayload(buildCompletedTaskPayload(payload, state, success));
        if (!task.isEmpty()) {
            snapshot.put("tasks", List.of(task));
            snapshot.put("notifications", buildNotifications(task, success));
        }
        return snapshot;
    }

    public Map<String, Object> projectReplyState(Map<String, Object> payload, String rawOutput, Map<String, Object> state) {
        Map<String, Object> snapshot = baseSnapshot("COMPLETED", FrontendStage.DONE, false, false, state);
        snapshot.put("lastMessage", extractMessageText(payload, rawOutput));
        return snapshot;
    }

    public Map<String, Object> projectGenericTaskState(Map<String, Object> payload, Map<String, Object> state) {
        Map<String, Object> task = FrontendTaskStateSupport.normalizePayload(buildGenericTaskPayload(payload, state));
        if (task.isEmpty()) {
            return Map.of();
        }
        String status = FrontendTaskStateSupport.normalizedStatus(task);
        FrontendStage stage = resolveTaskStage(task);
        boolean terminal = FrontendTaskStateSupport.isTerminalStatus(status);
        boolean canResume = "WAITING_APPROVAL".equalsIgnoreCase(status);
        Map<String, Object> snapshot = baseSnapshot(status, stage, !terminal, canResume, state);
        snapshot.put("toolCode", resolveToolCode(task, state));
        snapshot.put("pendingCardType", "TASK_CARD");
        snapshot.put("tasks", List.of(task));
        if (task.get("resultPreview") instanceof Map<?, ?> resultPreview) {
            snapshot.put("lastResult", resultPreview);
        }
        if (terminal) {
            snapshot.put("notifications", buildNotifications(task, !"FAILED".equalsIgnoreCase(status)));
        }
        return snapshot;
    }

    public boolean isGenericTaskPayload(Map<String, Object> payload) {
        Map<String, Object> taskPayload = unwrapTaskPayload(payload);
        return StringUtils.hasText(resolveTaskId(taskPayload))
                || StringUtils.hasText(asText(taskPayload.get("taskType")))
                || StringUtils.hasText(asText(taskPayload.get("sourceType")));
    }

    private Map<String, Object> baseSnapshot(
            String status,
            FrontendStage stage,
            boolean unfinished,
            boolean canResume,
            Map<String, Object> state) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("status", status);
        snapshot.put("phase", stage.name());
        snapshot.put("unfinished", unfinished);
        snapshot.put("canResume", canResume);
        String assistantUid = extractAssistantUid(state);
        if (StringUtils.hasText(assistantUid)) {
            snapshot.put("assistantUid", assistantUid);
        }
        return snapshot;
    }

    private Map<String, Object> buildConfirmPayload(Map<String, Object> payload, Map<String, Object> state) {
        Map<String, Object> confirmForm = asMap(payload.get("confirmForm"));
        if (confirmForm.isEmpty()) {
            return buildFormPayload(payload, state, "CONFIRM");
        }

        Map<String, Object> confirmPayload = new LinkedHashMap<>();
        confirmPayload.put("mode", "CONFIRM");
        confirmPayload.put("status", "WAITING_CONFIRMATION");
        confirmPayload.put("phase", FrontendStage.CONFIRMING.name());
        confirmPayload.put("message", asText(payload.get("message")));
        confirmPayload.put("toolCode", resolveToolCode(confirmForm, state));
        Map<String, Object> values = selectedValues(confirmForm, payload);
        confirmPayload.put("values", values);
        confirmPayload.put("fields", buildFields(confirmForm, payload, values));
        confirmPayload.put("missingFields", normalizeFieldList(payload.get("missing")));
        confirmPayload.put("summary", summaryPayload(confirmForm, values, confirmPayload));
        confirmPayload.put("canSubmit", Boolean.TRUE);
        return confirmPayload;
    }

    private Map<String, Object> buildFormPayload(Map<String, Object> payload, Map<String, Object> state, String mode) {
        Map<String, Object> formPayload = new LinkedHashMap<>();
        FrontendStage stage = resolveStage(payload, FrontendStage.COLLECTING);
        boolean confirmationForm = FrontendFormStateSupport.isConfirmationForm(payload, stage.name(), asText(payload.get("status")))
                || "CONFIRM".equalsIgnoreCase(mode);
        formPayload.put("mode", confirmationForm ? "CONFIRM" : "COLLECT");
        formPayload.put("status", confirmationForm ? "WAITING_CONFIRMATION" : "WAITING_INPUT");
        formPayload.put("phase", confirmationForm ? FrontendStage.CONFIRMING.name() : FrontendStage.COLLECTING.name());
        formPayload.put("message", asText(payload.get("message")));
        formPayload.put("round", payload.get("round"));
        formPayload.put("toolCode", resolveToolCode(payload, state));
        Map<String, Object> values = selectedValues(payload, payload);
        formPayload.put("values", values);
        formPayload.put("fields", buildFields(payload, payload, values));
        formPayload.put("missingFields", normalizeFieldList(payload.get("missing")));
        formPayload.put("summary", summaryPayload(payload, values, formPayload));
        formPayload.put("canSubmit", confirmationForm);
        return formPayload;
    }

    private Map<String, Object> buildResultPayload(Map<String, Object> payload, Map<String, Object> state) {
        Map<String, Object> resultPayload = new LinkedHashMap<>();
        resultPayload.put("success", !Boolean.FALSE.equals(payload.get("success"))
                && !StringUtils.hasText(asText(payload.get("error"))));
        resultPayload.put("artifactCode", resolveToolCode(payload, state));
        resultPayload.put("lifecycleStatus", asText(payload.get("lifecycleStatus")));
        resultPayload.put("approvalRequestId", asText(payload.get("approvalRequestId")));
        resultPayload.put("result", asMap(payload.get("result")).isEmpty() ? payload.get("result") : asMap(payload.get("result")));
        resultPayload.put("error", asText(payload.get("error")));
        if (payload.containsKey("executionEvents")) {
            resultPayload.put("executionEvents", payload.get("executionEvents"));
        }
        return resultPayload;
    }

    private Map<String, Object> buildRunningTaskPayload(Map<String, Object> payload, Map<String, Object> state) {
        String taskId = resolveTaskId(payload);
        if (!StringUtils.hasText(taskId)) {
            return Map.of();
        }
        Map<String, Object> task = buildTaskSkeleton(taskId, payload, state);
        task.put("status", resolveArtifactTaskStatus(payload));
        task.put("resultReady", Boolean.FALSE);
        Integer progressPercent = intValue(payload.get("progressPercent"), -1);
        if (progressPercent >= 0) {
            task.put("progressPercent", progressPercent);
        }
        List<Map<String, Object>> liveOutput = extractLiveOutput(payload);
        if (!liveOutput.isEmpty()) {
            task.put("liveOutput", liveOutput);
            String eventType = asText(liveOutput.get(liveOutput.size() - 1).get("eventType"));
            if (StringUtils.hasText(eventType)) {
                task.put("eventType", eventType);
            }
        }
        return FrontendTaskStateSupport.normalizePayload(task);
    }

    private Map<String, Object> buildCompletedTaskPayload(
            Map<String, Object> payload,
            Map<String, Object> state,
            boolean success) {
        String taskId = resolveTaskId(payload);
        if (!StringUtils.hasText(taskId)) {
            return Map.of();
        }
        Map<String, Object> task = buildTaskSkeleton(taskId, payload, state);
        task.put("status", success ? "COMPLETED" : "FAILED");
        task.put("resultReady", Boolean.TRUE);
        task.put("progressPercent", 100);
        Map<String, Object> result = asMap(payload.get("result"));
        if (!result.isEmpty()) {
            task.put("resultPreview", result);
        }
        List<Map<String, Object>> liveOutput = extractLiveOutput(payload);
        if (!liveOutput.isEmpty()) {
            task.put("liveOutput", liveOutput);
        }
        return FrontendTaskStateSupport.normalizePayload(task);
    }

    private Map<String, Object> buildExecutionTaskPayload(Map<String, Object> payload, Map<String, Object> state) {
        String taskId = resolveTaskId(payload);
        if (!StringUtils.hasText(taskId)) {
            return Map.of();
        }
        Map<String, Object> task = buildTaskSkeleton(taskId, payload, state);
        task.put("status", resolveExecutionStatus(payload));
        task.put("resultReady", Boolean.FALSE);
        Integer progressPercent = intValue(payload.get("progressPercent"), -1);
        if (progressPercent >= 0) {
            task.put("progressPercent", progressPercent);
        }
        task.put("eventType", asText(payload.get("eventType")));
        task.put("stepId", asText(payload.get("stepId")));
        List<Map<String, Object>> liveOutput = extractLiveOutput(payload);
        if (!liveOutput.isEmpty()) {
            task.put("liveOutput", liveOutput);
        }
        return FrontendTaskStateSupport.normalizePayload(task);
    }

    private Map<String, Object> buildGenericTaskPayload(Map<String, Object> payload, Map<String, Object> state) {
        Map<String, Object> taskPayload = unwrapTaskPayload(payload);
        String taskId = resolveTaskId(taskPayload);
        if (!StringUtils.hasText(taskId)) {
            return Map.of();
        }
        Map<String, Object> task = buildTaskSkeleton(taskId, taskPayload, state);
        String status = firstText(taskPayload.get("status"), taskPayload.get("state"), "RUNNING");
        task.put("status", status);
        int progressPercent = intValue(taskPayload.get("progressPercent"), -1);
        if (progressPercent >= 0) {
            task.put("progressPercent", progressPercent);
        }
        boolean terminal = isTerminalTaskStatus(status);
        task.put("resultReady", Boolean.TRUE.equals(taskPayload.get("resultReady")) || terminal);
        Map<String, Object> resultPreview = asMap(taskPayload.get("resultPreview"));
        if (resultPreview.isEmpty()) {
            resultPreview = asMap(taskPayload.get("result"));
        }
        if (!resultPreview.isEmpty()) {
            task.put("resultPreview", resultPreview);
        }
        List<Map<String, Object>> liveOutput = extractLiveOutput(taskPayload);
        if (!liveOutput.isEmpty()) {
            task.put("liveOutput", liveOutput);
        }
        if (taskPayload.containsKey("background")) {
            task.put("background", taskPayload.get("background"));
        }
        if (taskPayload.containsKey("detached")) {
            task.put("detached", taskPayload.get("detached"));
        }
        Map<String, Object> display = asMap(taskPayload.get("display"));
        if (!display.isEmpty()) {
            task.put("display", display);
        }
        Map<String, Object> action = asMap(taskPayload.get("action"));
        if (!action.isEmpty()) {
            task.put("action", action);
        }
        Map<String, Object> notification = asMap(taskPayload.get("notification"));
        if (!notification.isEmpty()) {
            task.put("notification", notification);
        }
        if (StringUtils.hasText(asText(taskPayload.get("eventType")))) {
            task.put("eventType", asText(taskPayload.get("eventType")));
        }
        return FrontendTaskStateSupport.normalizePayload(task);
    }

    private Map<String, Object> buildTaskSkeleton(String taskId, Map<String, Object> payload, Map<String, Object> state) {
        Map<String, Object> task = new LinkedHashMap<>();
        task.put("taskId", taskId);
        task.put("runId", firstText(payload.get("runId"), taskId));
        task.put("threadId", firstText(state != null ? state.get(AssistantStateKeys.THREAD_ID) : null, payload.get("threadId")));
        task.put("taskType", firstText(payload.get("taskType"), "ARTIFACT_EXECUTION"));
        task.put("title", resolveTaskTitle(payload, state));
        task.put("sourceType", firstText(payload.get("sourceType"), "ARTIFACT_EXECUTION"));
        task.put("sourceCode", resolveToolCode(payload, state));
        if (payload.containsKey("internal")) {
            task.put("internal", Boolean.TRUE.equals(payload.get("internal")));
        }
        putIfHasText(task, "toolType", firstText(payload.get("toolType"), asMap(payload.get("payload")).get("toolType")));
        putIfHasText(task, "visibility", firstText(payload.get("visibility"), asMap(payload.get("payload")).get("visibility")));
        putIfHasText(task, "invocationPolicy", firstText(payload.get("invocationPolicy"), asMap(payload.get("payload")).get("invocationPolicy")));
        task.put("collapsible", !Boolean.FALSE.equals(payload.get("collapsible")));
        return task;
    }

    private void putIfHasText(Map<String, Object> target, String key, String value) {
        if (target != null && StringUtils.hasText(key) && StringUtils.hasText(value)) {
            target.put(key, value);
        }
    }

    private List<Map<String, Object>> buildNotifications(Map<String, Object> task, boolean success) {
        if (task.isEmpty()) {
            return List.of();
        }
        Map<String, Object> customNotification = asMap(task.get("notification"));
        if (!customNotification.isEmpty()) {
            Map<String, Object> normalized = new LinkedHashMap<>(customNotification);
            normalized.putIfAbsent("taskId", task.get("taskId"));
            normalized.putIfAbsent("status", "UNREAD");
            normalized.putIfAbsent("createdAt", Instant.now().toString());
            if (!normalized.containsKey("action") && task.get("action") instanceof Map<?, ?> action) {
                normalized.put("action", action);
            }
            return List.of(normalized);
        }
        String taskId = asText(task.get("taskId"));
        if (!StringUtils.hasText(taskId)) {
            return List.of();
        }
        Map<String, Object> action = asMap(task.get("action"));
        if (action.isEmpty()) {
            action = new LinkedHashMap<>();
            action.put("type", "TASK_DETAIL");
            action.put("targetId", taskId);
        }
        Map<String, Object> notification = new LinkedHashMap<>();
        notification.put("notificationId", taskId + ":" + (success ? "COMPLETED" : "FAILED"));
        notification.put("taskId", taskId);
        notification.put("status", "UNREAD");
        notification.put("title", firstText(task.get("title"), task.get("sourceCode"), taskId) + (success ? " 已完成" : " 执行失败"));
        notification.put("body", success ? "点击查看结果" : "点击查看失败详情");
        notification.put("createdAt", Instant.now().toString());
        notification.put("action", action);
        return List.of(notification);
    }

    private List<Map<String, Object>> extractLiveOutput(Map<String, Object> payload) {
        if (payload.get("liveOutput") instanceof List<?> rawOutput && !rawOutput.isEmpty()) {
            List<Map<String, Object>> items = new ArrayList<>();
            for (Object item : rawOutput) {
                Map<String, Object> output = asMap(item);
                if (!output.isEmpty()) {
                    items.add(normalizeLiveOutputItem(output));
                }
            }
            if (!items.isEmpty()) {
                return items;
            }
        }
        if (payload.get("executionEvents") instanceof List<?> rawEvents && !rawEvents.isEmpty()) {
            List<Map<String, Object>> items = new ArrayList<>();
            for (Object rawEvent : rawEvents) {
                Map<String, Object> event = asMap(rawEvent);
                if (!event.isEmpty()) {
                    items.add(normalizeLiveOutputItem(event));
                }
            }
            return items;
        }
        if (payload.containsKey("eventType") || payload.containsKey("stepId") || payload.containsKey("payload")) {
            return List.of(normalizeLiveOutputItem(payload));
        }
        return List.of();
    }

    private Map<String, Object> normalizeLiveOutputItem(Map<String, Object> payload) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("eventType", asText(payload.get("eventType")));
        item.put("stepId", asText(payload.get("stepId")));
        item.put("occurredAt", firstText(payload.get("occurredAt"), Instant.now().toString()));
        Map<String, Object> nested = asMap(payload.get("payload"));
        String stepName = firstText(nested.get("stepName"), payload.get("stepName"));
        String text = firstText(payload.get("text"), nested.get("text"), payload.get("message"), stepName);
        if (StringUtils.hasText(stepName)) {
            item.put("stepName", stepName);
        }
        if (StringUtils.hasText(text)) {
            item.put("text", text);
        }
        String status = firstText(payload.get("status"), nested.get("status"));
        if (StringUtils.hasText(status)) {
            item.put("status", status);
        }
        Map<String, Object> outputs = asMap(firstNonEmpty(payload.get("outputs"), nested.get("outputs")));
        if (!outputs.isEmpty()) {
            item.put("outputs", outputs);
        }
        return item;
    }

    private Map<String, Object> unwrapTaskPayload(Map<String, Object> payload) {
        Map<String, Object> task = asMap(payload.get("task"));
        return task.isEmpty() ? payload : task;
    }

    private String resolveTaskId(Map<String, Object> payload) {
        return firstText(payload.get("taskId"), payload.get("runId"));
    }

    private String resolveTaskTitle(Map<String, Object> payload, Map<String, Object> state) {
        return firstText(payload.get("title"), payload.get("artifactCode"), payload.get("sourceLabel"),
                resolveToolCode(payload, state), "后台任务");
    }

    private FrontendStage resolveTaskStage(Map<String, Object> payload) {
        String phase = FrontendTaskStateSupport.normalizedPhase(payload);
        return switch (phase) {
            case "WAITING_APPROVAL" -> FrontendStage.WAITING_APPROVAL;
            case "DONE" -> FrontendStage.DONE;
            case "ERROR" -> FrontendStage.ERROR;
            default -> FrontendStage.EXECUTING;
        };
    }

    private Map<String, Object> summaryPayload(
            Map<String, Object> payload,
            Map<String, Object> values,
            Map<String, Object> formPayload) {
        Map<String, Object> formSummary = asMap(payload.get("formSummary"));
        if (!formSummary.isEmpty()) {
            return formSummary;
        }

        List<Map<String, Object>> fields = asListOfMaps(formPayload.get("fields"));
        List<Map<String, Object>> summaryItems = new ArrayList<>();
        List<Map<String, Object>> secondaryItems = new ArrayList<>();
        for (Map<String, Object> field : fields) {
            Map<String, Object> displayConfig = asMap(field.get("displayConfig"));
            boolean showInSummary = !displayConfig.containsKey("showInSummary")
                    || Boolean.TRUE.equals(displayConfig.get("showInSummary"));
            if (!showInSummary) {
                continue;
            }
            Object rawValue = values.get(field.get("name"));
            if (rawValue == null) {
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("label", firstText(field.get("title"), field.get("name")));
            item.put("value", String.valueOf(rawValue));
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
        Comparator<Map<String, Object>> comparator = Comparator.comparingInt(item -> intValue(item.get("order"), 999));
        summaryItems.sort(comparator);
        secondaryItems.sort(comparator);

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("summaryItems", summaryItems);
        summary.put("secondaryItems", secondaryItems);
        return summary;
    }

    private List<Map<String, Object>> buildFields(
            Map<String, Object> primaryPayload,
            Map<String, Object> fallbackPayload,
            Map<String, Object> values) {
        List<Map<String, Object>> enrichedSlots = asListOfMaps(primaryPayload.get("enrichedSlots"));
        if (enrichedSlots.isEmpty()) {
            enrichedSlots = asListOfMaps(fallbackPayload.get("enrichedSlots"));
        }
        Set<String> missingNames = new LinkedHashSet<>();
        for (Map<String, Object> missing : normalizeFieldList(fallbackPayload.get("missing"))) {
            String missingName = asText(missing.get("name"));
            if (StringUtils.hasText(missingName)) {
                missingNames.add(missingName);
            }
        }

        List<Map<String, Object>> fields = new ArrayList<>();
        for (Map<String, Object> enrichedSlot : enrichedSlots) {
            Map<String, Object> definition = asMap(enrichedSlot.get("definition"));
            String name = firstText(enrichedSlot.get("name"), definition.get("name"));
            if (!StringUtils.hasText(name)) {
                continue;
            }
            Map<String, Object> field = new LinkedHashMap<>();
            field.put("name", name);
            field.put("title", firstText(definition.get("title"), name));
            field.put("type", asText(definition.get("type")));
            field.put("description", asText(definition.get("description")));
            field.put("required", Boolean.TRUE.equals(definition.get("required")));
            field.put("priority", asText(definition.get("priority")));
            field.put("askMode", asText(definition.get("askMode")));
            field.put("uiComponent", asText(definition.get("uiComponent")));
            field.put("displayConfig", asMap(definition.get("displayConfig")));
            field.put("validation", asMap(definition.get("validation")));
            field.put("computed", asMap(definition.get("computed")));
            field.put("editable", resolveEditable(definition));
            field.put("value", values.get(name));
            field.put("missing", missingNames.contains(name));
            Object options = enrichedSlot.containsKey("options")
                    ? enrichedSlot.get("options")
                    : definition.get("options");
            field.put("options", normalizeOptions(options));
            field.put("optionsLoaded", enrichedSlot.get("optionsLoaded"));
            if (StringUtils.hasText(asText(enrichedSlot.get("optionsError")))) {
                field.put("optionsError", enrichedSlot.get("optionsError"));
            }
            fields.add(field);
        }
        return fields;
    }

    private boolean resolveEditable(Map<String, Object> definition) {
        Map<String, Object> displayConfig = asMap(definition.get("displayConfig"));
        if (displayConfig.containsKey("inlineEditable")) {
            return !Boolean.FALSE.equals(displayConfig.get("inlineEditable"));
        }
        Map<String, Object> computed = asMap(definition.get("computed"));
        return !Boolean.TRUE.equals(computed.get("enabled"));
    }

    private List<Map<String, Object>> normalizeFieldList(Object raw) {
        List<Map<String, Object>> normalized = new ArrayList<>();
        for (Map<String, Object> item : asListOfMaps(raw)) {
            Map<String, Object> normalizedItem = new LinkedHashMap<>();
            normalizedItem.put("name", asText(item.get("name")));
            if (StringUtils.hasText(asText(item.get("title")))) {
                normalizedItem.put("title", item.get("title"));
            }
            if (StringUtils.hasText(asText(item.get("priority")))) {
                normalizedItem.put("priority", item.get("priority"));
            }
            if (StringUtils.hasText(asText(item.get("askMode")))) {
                normalizedItem.put("askMode", item.get("askMode"));
            }
            if (!normalizedItem.isEmpty()) {
                normalized.add(normalizedItem);
            }
        }
        return normalized;
    }

    @SuppressWarnings("unchecked")
    private List<Object> normalizeOptions(Object rawOptions) {
        Object sanitized = StructuredValueSanitizer.sanitize(rawOptions);
        if (sanitized instanceof List<?> list) {
            return new ArrayList<>((List<Object>) list);
        }
        return List.of();
    }

    private Map<String, Object> selectedValues(Map<String, Object> primaryPayload, Map<String, Object> fallbackPayload) {
        Map<String, Object> values = asMap(primaryPayload.get("collected"));
        if (values.isEmpty()) {
            values = asMap(primaryPayload.get("allCollected"));
        }
        if (values.isEmpty()) {
            values = asMap(fallbackPayload.get("collected"));
        }
        if (values.isEmpty()) {
            values = asMap(fallbackPayload.get("allCollected"));
        }
        return values;
    }

    private FrontendStage resolveStage(Map<String, Object> payload, FrontendStage fallback) {
        String phase = asText(payload.get("phase"));
        if (!StringUtils.hasText(phase)) {
            phase = asText(payload.get("status"));
        }
        if (!StringUtils.hasText(phase)) {
            return fallback;
        }
        return switch (phase.trim().toUpperCase()) {
            case "UNDERSTANDING" -> FrontendStage.UNDERSTANDING;
            case "COLLECTING" -> FrontendStage.COLLECTING;
            case "READY_TO_CONFIRM", "CONFIRMING" -> FrontendStage.CONFIRMING;
            case "WAITING_APPROVAL" -> FrontendStage.WAITING_APPROVAL;
            case "EXECUTING", "RUNNING" -> FrontendStage.EXECUTING;
            case "DONE", "COMPLETE", "COMPLETED" -> FrontendStage.DONE;
            case "ERROR", "FAILED" -> FrontendStage.ERROR;
            default -> fallback;
        };
    }

    private FrontendStage resolveResultStage(Map<String, Object> payload) {
        if (isWaitingApprovalPayload(payload)) {
            return FrontendStage.WAITING_APPROVAL;
        }
        boolean success = !Boolean.FALSE.equals(payload.get("success"))
                && !StringUtils.hasText(asText(payload.get("error")));
        return success ? FrontendStage.DONE : FrontendStage.ERROR;
    }

    private FrontendStage resolveExecutionStage(Map<String, Object> payload) {
        return "WAITING_APPROVAL".equalsIgnoreCase(resolveExecutionStatus(payload))
                ? FrontendStage.WAITING_APPROVAL
                : FrontendStage.EXECUTING;
    }

    private String resolveArtifactTaskStatus(Map<String, Object> payload) {
        if (isWaitingApprovalPayload(payload)) {
            return "WAITING_APPROVAL";
        }
        return "RUNNING";
    }

    private String resolveExecutionStatus(Map<String, Object> payload) {
        if (payload == null || payload.isEmpty()) {
            return "RUNNING";
        }
        String lifecycleStatus = asText(payload.get("lifecycleStatus"));
        if (StringUtils.hasText(lifecycleStatus)) {
            if ("WAITING_APPROVAL".equalsIgnoreCase(lifecycleStatus)) {
                return "WAITING_APPROVAL";
            }
            if ("COMPLETED".equalsIgnoreCase(lifecycleStatus) || "SKIPPED".equalsIgnoreCase(lifecycleStatus)) {
                return "COMPLETED";
            }
            if ("FAILED".equalsIgnoreCase(lifecycleStatus)) {
                return "FAILED";
            }
        }
        String eventType = asText(payload.get("eventType"));
        if ("STEP_WAITING_APPROVAL".equalsIgnoreCase(eventType)) {
            return "WAITING_APPROVAL";
        }
        return firstText(payload.get("status"), "RUNNING");
    }

    private boolean isWaitingApprovalPayload(Map<String, Object> payload) {
        if (payload == null || payload.isEmpty()) {
            return false;
        }
        return "WAITING_APPROVAL".equalsIgnoreCase(asText(payload.get("lifecycleStatus")))
                || "WAITING_APPROVAL".equalsIgnoreCase(asText(payload.get("status")))
                || "STEP_WAITING_APPROVAL".equalsIgnoreCase(asText(payload.get("eventType")));
    }

    private String extractMessageText(Map<String, Object> payload, String rawOutput) {
        Object text = payload.get("message");
        if (text == null) {
            text = payload.get("content");
        }
        if (text == null && StringUtils.hasText(rawOutput)) {
            text = rawOutput;
        }
        return text != null ? String.valueOf(text) : "";
    }

    private String resolveToolCode(Map<String, Object> payload, Map<String, Object> state) {
        String toolCode = firstText(payload.get("toolCode"), payload.get("artifactCode"), payload.get("sourceCode"));
        if (StringUtils.hasText(toolCode)) {
            return toolCode;
        }
        Object matchedToolMeta = state != null ? state.get(AssistantStateKeys.MATCHED_TOOL_META) : null;
        if (matchedToolMeta instanceof ToolMetaSnapshot snapshot) {
            return firstText(snapshot.getToolCode());
        }
        if (matchedToolMeta instanceof ToolMeta toolMeta) {
            return firstText(toolMeta.getToolCode(), toolMeta.getToolName());
        }
        Map<String, Object> matchedToolMetaMap = asMap(matchedToolMeta);
        return firstText(matchedToolMetaMap.get("toolCode"), matchedToolMetaMap.get("tool_code"), matchedToolMetaMap.get("code"));
    }

    private String extractAssistantUid(Map<String, Object> state) {
        if (state == null || state.isEmpty()) {
            return null;
        }
        return asText(state.get(AssistantStateKeys.ASSISTANT_UID));
    }

    private FrontendEvent newEvent(
            String threadId,
            FrontendEventType eventType,
            FrontendStage stage,
            Map<String, Object> payload) {
        return new FrontendEvent(
                V3ProtocolAdapter.PROTOCOL_VERSION,
                UUID.randomUUID().toString(),
                threadId,
                Instant.now().toString(),
                eventType,
                stage,
                payload != null ? payload : Map.of());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            return StructuredValueSanitizer.sanitizeMap((Map<String, Object>) map);
        }
        return new LinkedHashMap<>();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> asListOfMaps(Object value) {
        if (!(value instanceof List<?> list) || list.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> result = new ArrayList<>(list.size());
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                result.add(new LinkedHashMap<>((Map<String, Object>) map));
            }
        }
        return result;
    }

    private String asText(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return StringUtils.hasText(text) ? text : null;
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

    private int intValue(Object value, int fallback) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        String text = asText(value);
        if (!StringUtils.hasText(text)) {
            return fallback;
        }
        try {
            return Integer.parseInt(text);
        }
        catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private Object firstNonEmpty(Object... values) {
        if (values == null) {
            return null;
        }
        for (Object value : values) {
            if (value == null) {
                continue;
            }
            if (value instanceof Map<?, ?> map && map.isEmpty()) {
                continue;
            }
            if (value instanceof List<?> list && list.isEmpty()) {
                continue;
            }
            if (value instanceof String text && !StringUtils.hasText(text)) {
                continue;
            }
            return value;
        }
        return null;
    }

    private boolean isTerminalTaskStatus(String status) {
        String normalized = asText(status);
        return "COMPLETED".equalsIgnoreCase(normalized)
                || "DONE".equalsIgnoreCase(normalized)
                || "FAILED".equalsIgnoreCase(normalized)
                || "ERROR".equalsIgnoreCase(normalized);
    }
}
