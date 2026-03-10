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
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentAppPublicationPolicyServiceTest {

    @Test
    void shouldReturnEmptyWhenSpaceDoesNotExist() {
        PlatformSpaceService platformSpaceService = mock(PlatformSpaceService.class);
        AgentAppService agentAppService = mock(AgentAppService.class);
        AgentAppGrantService agentAppGrantService = mock(AgentAppGrantService.class);
        AgentAppPublicationPolicyService service = new AgentAppPublicationPolicyService(
                platformSpaceService,
                agentAppService,
                agentAppGrantService);
        when(platformSpaceService.findActiveByCode("enterprise-default", "prod")).thenReturn(Optional.empty());

        assertTrue(service.getPublicationSourcePolicy("enterprise-default", null, "finance-agent").isEmpty());
    }

    @Test
    void shouldReturnDefaultMergePolicyWhenAgentAppHasNoCustomPublicationPolicy() {
        PlatformSpaceService platformSpaceService = mock(PlatformSpaceService.class);
        AgentAppService agentAppService = mock(AgentAppService.class);
        AgentAppGrantService agentAppGrantService = mock(AgentAppGrantService.class);
        AgentAppPublicationPolicyService service = new AgentAppPublicationPolicyService(
                platformSpaceService,
                agentAppService,
                agentAppGrantService);
        PlatformSpace space = new PlatformSpace();
        space.setId(9L);
        space.setSpaceCode("enterprise-default");
        space.setEnvironment("prod");
        AgentApp app = new AgentApp();
        app.setId(7L);
        app.setAgentAppCode("finance-agent");
        when(platformSpaceService.findActiveByCode("enterprise-default", "prod")).thenReturn(Optional.of(space));
        when(agentAppService.findActiveByCode(9L, "finance-agent")).thenReturn(Optional.of(app));
        when(agentAppGrantService.findPublicationSourcePolicy(7L)).thenReturn(Optional.empty());

        ResolvedAgentAppPublicationSourcePolicy result = service
                .getPublicationSourcePolicy("enterprise-default", null, "finance-agent")
                .orElseThrow();

        assertEquals("enterprise-default", result.spaceCode());
        assertEquals("prod", result.environment());
        assertEquals("finance-agent", result.agentAppCode());
        assertEquals("MERGE", result.policy().sourceSelectionMode());
        assertTrue(result.policy().allowedSourceIds().isEmpty());
        assertTrue(result.policy().blockedSourceIds().isEmpty());
    }

    @Test
    void shouldReplacePolicyForResolvedAgentApp() {
        PlatformSpaceService platformSpaceService = mock(PlatformSpaceService.class);
        AgentAppService agentAppService = mock(AgentAppService.class);
        AgentAppGrantService agentAppGrantService = mock(AgentAppGrantService.class);
        AgentAppPublicationPolicyService service = new AgentAppPublicationPolicyService(
                platformSpaceService,
                agentAppService,
                agentAppGrantService);
        PlatformSpace space = new PlatformSpace();
        space.setId(9L);
        space.setSpaceCode("enterprise-default");
        space.setEnvironment("test");
        AgentApp app = new AgentApp();
        app.setId(7L);
        app.setAgentAppCode("finance-agent");
        AgentAppPublicationSourcePolicy policy = new AgentAppPublicationSourcePolicy(
                "exclusive",
                List.of("artifact-catalog"),
                List.of("legacy-bridge"));
        when(platformSpaceService.findActiveByCode("enterprise-default", "test")).thenReturn(Optional.of(space));
        when(agentAppService.findActiveByCode(9L, "finance-agent")).thenReturn(Optional.of(app));
        when(agentAppGrantService.replacePublicationSourcePolicy(7L, policy)).thenReturn(true);

        ResolvedAgentAppPublicationSourcePolicy result = service
                .replacePublicationSourcePolicy("enterprise-default", "test", "finance-agent", policy)
                .orElseThrow();

        assertEquals("artifact-catalog", result.policy().allowedSourceIds().get(0));
        assertEquals("legacy-bridge", result.policy().blockedSourceIds().get(0));
        verify(agentAppGrantService).replacePublicationSourcePolicy(7L, policy);
    }
}
