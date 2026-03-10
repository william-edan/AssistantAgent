/*
 * Copyright 2024-2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
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
package com.alibaba.assistant.agent.runtime.registry;

import com.alibaba.assistant.agent.controlplane.agentapp.AgentApp;
import com.alibaba.assistant.agent.controlplane.agentapp.AgentAppGrantService;
import com.alibaba.assistant.agent.controlplane.agentapp.AgentAppPublicationSourcePolicy;
import com.alibaba.assistant.agent.controlplane.agentapp.AgentAppService;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Optional;

/**
 * Resolves default publication source policy from typed agent-app control-plane policy.
 */
@Component
public class AgentAppPublicationPolicyResolver {

    private final AgentAppService agentAppService;

    private final AgentAppGrantService agentAppGrantService;

    public AgentAppPublicationPolicyResolver(
            AgentAppService agentAppService,
            AgentAppGrantService agentAppGrantService) {
        this.agentAppService = agentAppService;
        this.agentAppGrantService = agentAppGrantService;
    }

    /**
     * Resolve the default runtime publication source policy for an app in a space.
     */
    public Optional<PublicationSourcePolicy> resolve(@Nullable Long spaceId, @Nullable String agentAppCode) {
        if (spaceId == null || !StringUtils.hasText(agentAppCode)) {
            return Optional.empty();
        }
        Optional<AgentApp> agentApp = agentAppService.findActiveByCode(spaceId, agentAppCode.trim());
        if (agentApp.isEmpty()) {
            return Optional.empty();
        }

        return agentAppGrantService.findPublicationSourcePolicy(agentApp.get().getId())
                .map(this::toRuntimePolicy)
                .filter(policy -> !policy.isEmpty());
    }

    private PublicationSourcePolicy toRuntimePolicy(AgentAppPublicationSourcePolicy policy) {
        return new PublicationSourcePolicy(
                ToolPublicationProvider.SourceSelectionMode.fromValue(policy.sourceSelectionMode()),
                policy.allowedSourceIds(),
                policy.blockedSourceIds());
    }

    /**
     * Default source-selection policy resolved from app grants.
     */
    public record PublicationSourcePolicy(
            ToolPublicationProvider.SourceSelectionMode sourceSelectionMode,
            List<String> requestedSourceIds,
            List<String> blockedSourceIds) {

        public PublicationSourcePolicy {
            sourceSelectionMode = sourceSelectionMode != null
                    ? sourceSelectionMode : ToolPublicationProvider.SourceSelectionMode.MERGE;
            requestedSourceIds = requestedSourceIds != null ? List.copyOf(requestedSourceIds) : List.of();
            blockedSourceIds = blockedSourceIds != null ? List.copyOf(blockedSourceIds) : List.of();
        }

        boolean isEmpty() {
            return sourceSelectionMode == ToolPublicationProvider.SourceSelectionMode.MERGE
                    && requestedSourceIds.isEmpty()
                    && blockedSourceIds.isEmpty();
        }
    }
}
