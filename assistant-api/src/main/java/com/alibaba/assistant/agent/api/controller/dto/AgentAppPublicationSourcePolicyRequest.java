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

import com.alibaba.assistant.agent.controlplane.agentapp.AgentAppPublicationSourcePolicy;

import java.util.List;

/**
 * Request payload for replacing an agent-app publication source policy.
 */
public record AgentAppPublicationSourcePolicyRequest(
        String sourceSelectionMode,
        List<String> allowedSourceIds,
        List<String> blockedSourceIds) {

    /**
     * Returns the empty default request used when callers omit the body.
     *
     * @return default merge policy request
     */
    public static AgentAppPublicationSourcePolicyRequest empty() {
        return new AgentAppPublicationSourcePolicyRequest("MERGE", List.of(), List.of());
    }

    /**
     * Converts the request to the normalized control-plane policy model.
     *
     * @return normalized publication source policy
     */
    public AgentAppPublicationSourcePolicy toPolicy() {
        return new AgentAppPublicationSourcePolicy(sourceSelectionMode, allowedSourceIds, blockedSourceIds);
    }
}
