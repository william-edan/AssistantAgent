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
package com.alibaba.assistant.agent.api.controller.dto;

import com.alibaba.assistant.agent.controlplane.workflow.ResolvedWorkflowStepManagementView;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Managed workflow-step payload.
 */
public record ManagedWorkflowStepData(
        String stepId,
        String stepName,
        String stepType,
        String connectorCode,
        String targetRef,
        List<String> allowedAuthProfiles,
        List<String> bindingStrategies,
        Map<String, Object> inputMapping,
        Map<String, Object> outputMapping,
        List<String> dependsOn,
        Map<String, Object> condition,
        Map<String, Object> joinPolicy,
        Map<String, Object> retryPolicy,
        Map<String, Object> timeoutPolicy,
        Map<String, Object> approvalGate,
        String compensationTargetRef,
        Map<String, Object> resumePolicy,
        Integer stepOrder,
        String status) {

    public static ManagedWorkflowStepData from(ResolvedWorkflowStepManagementView resolved) {
        return new ManagedWorkflowStepData(
                resolved.stepId(),
                resolved.stepName(),
                upper(resolved.stepType()),
                resolved.connectorCode(),
                resolved.targetRef(),
                resolved.allowedAuthProfiles(),
                resolved.bindingStrategies(),
                resolved.inputMapping(),
                resolved.outputMapping(),
                resolved.dependsOn(),
                resolved.condition(),
                resolved.joinPolicy(),
                resolved.retryPolicy(),
                resolved.timeoutPolicy(),
                resolved.approvalGate(),
                resolved.compensationTargetRef(),
                resolved.resumePolicy(),
                resolved.stepOrder(),
                upper(resolved.status()));
    }

    private static String upper(String value) {
        return StringUtils.hasText(value) ? value.trim().toUpperCase(Locale.ROOT) : "";
    }
}
