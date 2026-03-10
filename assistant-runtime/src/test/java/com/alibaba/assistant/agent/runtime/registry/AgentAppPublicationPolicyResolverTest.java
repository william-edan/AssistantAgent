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
package com.alibaba.assistant.agent.runtime.registry;

import com.alibaba.assistant.agent.controlplane.agentapp.AgentApp;
import com.alibaba.assistant.agent.controlplane.agentapp.AgentAppGrantService;
import com.alibaba.assistant.agent.controlplane.agentapp.AgentAppPublicationSourcePolicy;
import com.alibaba.assistant.agent.controlplane.agentapp.AgentAppService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentAppPublicationPolicyResolverTest {

    @Test
    void shouldResolveDefaultPublicationSourcePolicyFromAgentAppGrants() {
        AgentAppService agentAppService = mock(AgentAppService.class);
        AgentAppGrantService agentAppGrantService = mock(AgentAppGrantService.class);
        AgentAppPublicationPolicyResolver resolver = new AgentAppPublicationPolicyResolver(
                agentAppService,
                agentAppGrantService);

        AgentApp app = new AgentApp();
        app.setId(7L);
        app.setSpaceId(1L);
        app.setAgentAppCode("finance-agent");
        when(agentAppService.findActiveByCode(1L, "finance-agent")).thenReturn(Optional.of(app));
        when(agentAppGrantService.findPublicationSourcePolicy(7L)).thenReturn(Optional.of(
                new AgentAppPublicationSourcePolicy(
                        "exclusive",
                        List.of("artifact-catalog", "mcp-gateway"),
                        List.of("legacy-bridge"))));

        Optional<AgentAppPublicationPolicyResolver.PublicationSourcePolicy> policy =
                resolver.resolve(1L, "finance-agent");

        assertTrue(policy.isPresent());
        assertEquals(ToolPublicationProvider.SourceSelectionMode.EXCLUSIVE, policy.get().sourceSelectionMode());
        assertEquals(List.of("artifact-catalog", "mcp-gateway"), policy.get().requestedSourceIds());
        assertEquals(List.of("legacy-bridge"), policy.get().blockedSourceIds());
    }

    @Test
    void shouldReturnEmptyWhenAppDoesNotExist() {
        AgentAppService agentAppService = mock(AgentAppService.class);
        AgentAppGrantService agentAppGrantService = mock(AgentAppGrantService.class);
        AgentAppPublicationPolicyResolver resolver = new AgentAppPublicationPolicyResolver(
                agentAppService,
                agentAppGrantService);
        when(agentAppService.findActiveByCode(1L, "finance-agent")).thenReturn(Optional.empty());

        assertTrue(resolver.resolve(1L, "finance-agent").isEmpty());
    }
}
