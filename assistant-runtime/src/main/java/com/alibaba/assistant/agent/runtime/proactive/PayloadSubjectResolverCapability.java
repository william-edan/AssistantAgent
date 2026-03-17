/*
 * Copyright 2024-2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 */
package com.alibaba.assistant.agent.runtime.proactive;

import com.alibaba.assistant.agent.controlplane.rolepackage.RoleProactiveTaskQueryService;
import com.alibaba.assistant.agent.controlplane.rolepackage.SubjectResolverCapability;
import com.alibaba.assistant.agent.runtime.agent.AssistantStateKeys;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Resolves subject metadata embedded in proactive task payloads.
 */
@Component
class PayloadSubjectResolverCapability implements SubjectResolverCapability {

    private static final String SUBJECT_KEY = "subject";

    @Override
    public boolean supports(RoleProactiveTaskQueryService.PublishedRoleProactiveTask task) {
        return task != null && asMap(task.taskPayload().get(SUBJECT_KEY)) != null;
    }

    @Override
    public Map<String, Object> resolveSubjectArguments(RoleProactiveTaskQueryService.PublishedRoleProactiveTask task) {
        Map<String, Object> subject = task != null ? asMap(task.taskPayload().get(SUBJECT_KEY)) : null;
        if (subject == null || subject.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> resolved = new LinkedHashMap<>();
        putIfHasText(resolved, AssistantStateKeys.ASSISTANT_UID,
                firstNonBlank(text(subject, "assistantUid"), text(subject, AssistantStateKeys.ASSISTANT_UID)));
        putIfHasText(resolved, AssistantStateKeys.PLATFORM_PRINCIPAL_ID,
                firstNonBlank(text(subject, "platformPrincipalId"), text(subject, AssistantStateKeys.PLATFORM_PRINCIPAL_ID)));
        putIfHasText(resolved, AssistantStateKeys.PLATFORM_PRINCIPAL_TYPE,
                firstNonBlank(text(subject, "platformPrincipalType"), text(subject, AssistantStateKeys.PLATFORM_PRINCIPAL_TYPE)));
        putIfHasText(resolved, AssistantStateKeys.EXECUTION_SUBJECT_ID,
                firstNonBlank(text(subject, "subjectId"), text(subject, AssistantStateKeys.EXECUTION_SUBJECT_ID)));
        putIfHasText(resolved, AssistantStateKeys.EXECUTION_SUBJECT_TYPE,
                firstNonBlank(text(subject, "subjectType"), text(subject, AssistantStateKeys.EXECUTION_SUBJECT_TYPE)));
        mergeNestedMap(resolved, asMap(subject.get("arguments")));
        mergeNestedMap(resolved, asMap(subject.get("attributes")));
        return resolved.isEmpty() ? Map.of() : Map.copyOf(resolved);
    }

    private void mergeNestedMap(Map<String, Object> target, Map<String, Object> source) {
        if (target == null || source == null || source.isEmpty()) {
            return;
        }
        target.putAll(source);
    }

    private void putIfHasText(Map<String, Object> target, String key, String value) {
        if (target != null && StringUtils.hasText(key) && StringUtils.hasText(value)) {
            target.put(key, value);
        }
    }

    private String text(Map<String, Object> source, String key) {
        if (source == null || !StringUtils.hasText(key)) {
            return null;
        }
        Object value = source.get(key);
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return StringUtils.hasText(text) ? text : null;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> normalized = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry.getKey() == null) {
                    continue;
                }
                normalized.put(String.valueOf(entry.getKey()), entry.getValue());
            }
            return normalized;
        }
        return null;
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value;
            }
        }
        return null;
    }
}
