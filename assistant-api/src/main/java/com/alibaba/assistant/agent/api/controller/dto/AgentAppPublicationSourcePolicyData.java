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

import com.alibaba.assistant.agent.controlplane.agentapp.ResolvedAgentAppPublicationSourcePolicy;

import java.util.List;

/**
 * Response body data for an agent-app publication source policy.
 */
public record AgentAppPublicationSourcePolicyData(
        Long spaceId,
        String spaceCode,
        String environment,
        Long agentAppId,
        String agentAppCode,
        String sourceSelectionMode,
        List<String> allowedSourceIds,
        List<String> blockedSourceIds) {

    /**
     * Creates a response DTO from the resolved control-plane policy view.
     *
     * @param resolved resolved control-plane publication source policy
     * @return serialized response data
     */
    public static AgentAppPublicationSourcePolicyData from(ResolvedAgentAppPublicationSourcePolicy resolved) {
        return new AgentAppPublicationSourcePolicyData(
                resolved.spaceId(),
                resolved.spaceCode(),
                resolved.environment(),
                resolved.agentAppId(),
                resolved.agentAppCode(),
                resolved.policy().sourceSelectionMode(),
                resolved.policy().allowedSourceIds(),
                resolved.policy().blockedSourceIds());
    }
}
