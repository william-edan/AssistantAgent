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
package com.alibaba.assistant.agent.controlplane.agentapp;

import com.alibaba.assistant.agent.controlplane.space.PlatformSpace;
import com.alibaba.assistant.agent.controlplane.space.PlatformSpaceService;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Optional;

/**
 * Control-plane facade for resolving and updating agent-app publication source policy.
 */
@Service
public class AgentAppPublicationPolicyService {

    private static final String DEFAULT_ENVIRONMENT = "prod";

    private final PlatformSpaceService platformSpaceService;

    private final AgentAppService agentAppService;

    private final AgentAppGrantService agentAppGrantService;

    public AgentAppPublicationPolicyService(
            PlatformSpaceService platformSpaceService,
            AgentAppService agentAppService,
            AgentAppGrantService agentAppGrantService) {
        this.platformSpaceService = platformSpaceService;
        this.agentAppService = agentAppService;
        this.agentAppGrantService = agentAppGrantService;
    }

    /**
     * Resolve publication-source policy for a concrete space and agent app.
     *
     * @param spaceCode space code
     * @param environment environment, defaults to prod when blank
     * @param agentAppCode agent app code
     * @return resolved view when both space and app exist
     */
    public Optional<ResolvedAgentAppPublicationSourcePolicy> getPublicationSourcePolicy(
            String spaceCode,
            String environment,
            String agentAppCode) {
        return resolveTarget(spaceCode, environment, agentAppCode)
                .map(target -> new ResolvedAgentAppPublicationSourcePolicy(
                        target.spaceId(),
                        target.spaceCode(),
                        target.environment(),
                        target.agentAppId(),
                        target.agentAppCode(),
                        agentAppGrantService.findPublicationSourcePolicy(target.agentAppId())
                                .orElse(defaultPolicy())));
    }

    /**
     * Replace publication-source policy for a concrete space and agent app.
     *
     * @param spaceCode space code
     * @param environment environment, defaults to prod when blank
     * @param agentAppCode agent app code
     * @param policy replacement typed policy
     * @return resolved view of the updated policy when target exists and update succeeds
     */
    public Optional<ResolvedAgentAppPublicationSourcePolicy> replacePublicationSourcePolicy(
            String spaceCode,
            String environment,
            String agentAppCode,
            AgentAppPublicationSourcePolicy policy) {
        if (policy == null) {
            return Optional.empty();
        }
        return resolveTarget(spaceCode, environment, agentAppCode)
                .filter(target -> agentAppGrantService.replacePublicationSourcePolicy(target.agentAppId(), policy))
                .map(target -> new ResolvedAgentAppPublicationSourcePolicy(
                        target.spaceId(),
                        target.spaceCode(),
                        target.environment(),
                        target.agentAppId(),
                        target.agentAppCode(),
                        policy.isEmpty() ? defaultPolicy() : policy));
    }

    private Optional<ResolvedTarget> resolveTarget(String spaceCode, String environment, String agentAppCode) {
        if (!StringUtils.hasText(spaceCode) || !StringUtils.hasText(agentAppCode)) {
            return Optional.empty();
        }
        String normalizedEnvironment = normalizeEnvironment(environment);
        Optional<PlatformSpace> space = platformSpaceService.findActiveByCode(spaceCode.trim(), normalizedEnvironment);
        if (space.isEmpty()) {
            return Optional.empty();
        }
        Optional<AgentApp> agentApp = agentAppService.findActiveByCode(space.get().getId(), agentAppCode.trim());
        if (agentApp.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new ResolvedTarget(
                space.get().getId(),
                space.get().getSpaceCode(),
                StringUtils.hasText(space.get().getEnvironment()) ? space.get().getEnvironment() : normalizedEnvironment,
                agentApp.get().getId(),
                agentApp.get().getAgentAppCode()));
    }

    private AgentAppPublicationSourcePolicy defaultPolicy() {
        return new AgentAppPublicationSourcePolicy("MERGE", List.of(), List.of());
    }

    private String normalizeEnvironment(String requestedEnvironment) {
        return StringUtils.hasText(requestedEnvironment) ? requestedEnvironment.trim() : DEFAULT_ENVIRONMENT;
    }

    private record ResolvedTarget(
            Long spaceId,
            String spaceCode,
            String environment,
            Long agentAppId,
            String agentAppCode) {
    }
}
