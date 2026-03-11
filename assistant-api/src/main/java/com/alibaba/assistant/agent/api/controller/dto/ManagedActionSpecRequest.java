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

import com.alibaba.assistant.agent.controlplane.action.ActionSpecUpsertCommand;

import java.util.List;
import java.util.Map;

/**
 * Request payload for action-spec create/update operations.
 */
public record ManagedActionSpecRequest(
        Map<String, Object> operationBinding,
        List<String> allowedAuthProfiles,
        String defaultAuthProfileCode,
        List<String> bindingStrategies,
        Map<String, Object> inputSchema,
        Map<String, Object> outputSchema,
        Map<String, Object> idempotencyPolicy,
        String riskLevel,
        Long approvalPolicyId,
        String sideEffectLevel,
        Map<String, Object> observabilityProfile,
        String status) {

    public ActionSpecUpsertCommand toCommand() {
        return new ActionSpecUpsertCommand(
                operationBinding,
                allowedAuthProfiles,
                defaultAuthProfileCode,
                bindingStrategies,
                inputSchema,
                outputSchema,
                idempotencyPolicy,
                riskLevel,
                approvalPolicyId,
                sideEffectLevel,
                observabilityProfile,
                status);
    }
}
