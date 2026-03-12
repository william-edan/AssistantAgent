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
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentAppManagementServiceTest {

    @Test
    void shouldFilterAgentAppsByKeywordUnderSpace() {
        PlatformSpaceService platformSpaceService = mock(PlatformSpaceService.class);
        AgentAppService agentAppService = mock(AgentAppService.class);
        AgentAppManagementService service = new AgentAppManagementService(
                platformSpaceService,
                agentAppService,
                new ObjectMapper());

        PlatformSpace space = new PlatformSpace();
        space.setId(10L);
        space.setSpaceCode("enterprise-default");
        space.setEnvironment("prod");
        AgentApp financeApp = agentApp(31L, 10L, "finance-agent", "Finance Agent", "active");
        financeApp.setPromptPolicyJson("{\"mode\":\"strict\"}");
        financeApp.setMemoryPolicyJson("{\"retention\":\"short\"}");
        financeApp.setApprovalStrategyJson("{\"required\":true}");
        AgentApp hrApp = agentApp(32L, 10L, "hr-agent", "HR Agent", "active");
        hrApp.setPromptPolicyJson("{\"mode\":\"lenient\"}");
        hrApp.setMemoryPolicyJson("{\"retention\":\"long\"}");
        hrApp.setApprovalStrategyJson("{\"required\":false}");

        when(platformSpaceService.findActiveByCode("enterprise-default", "prod")).thenReturn(Optional.of(space));
        when(agentAppService.listActiveBySpace(10L)).thenReturn(List.of(financeApp, hrApp));

        List<ResolvedAgentAppManagementView> result = service.listAgentApps("enterprise-default", "prod", "finance");

        assertEquals(1, result.size());
        assertEquals("finance-agent", result.get(0).agentAppCode());
        assertEquals("strict", result.get(0).promptPolicy().get("mode"));
        assertEquals(Boolean.TRUE, result.get(0).approvalStrategy().get("required"));
    }

    @Test
    void shouldGetAgentAppByCodeUnderSpace() {
        PlatformSpaceService platformSpaceService = mock(PlatformSpaceService.class);
        AgentAppService agentAppService = mock(AgentAppService.class);
        AgentAppManagementService service = new AgentAppManagementService(
                platformSpaceService,
                agentAppService,
                new ObjectMapper());

        PlatformSpace space = new PlatformSpace();
        space.setId(10L);
        space.setSpaceCode("enterprise-default");
        space.setEnvironment("prod");
        AgentApp app = agentApp(31L, 10L, "finance-agent", "Finance Agent", "active");
        app.setPromptPolicyJson("{\"mode\":\"strict\"}");
        app.setMemoryPolicyJson("{\"retention\":\"short\"}");
        app.setApprovalStrategyJson("{\"required\":true}");

        when(platformSpaceService.findActiveByCode("enterprise-default", "prod")).thenReturn(Optional.of(space));
        when(agentAppService.findActiveByCode(10L, "finance-agent")).thenReturn(Optional.of(app));

        Optional<ResolvedAgentAppManagementView> result = service.getAgentApp("enterprise-default", "prod", "finance-agent");

        assertTrue(result.isPresent());
        assertEquals("finance-agent", result.get().agentAppCode());
        assertEquals("strict", result.get().promptPolicy().get("mode"));
    }
    @Test
    void shouldCreateAgentAppWhenCodeDoesNotExist() {
        PlatformSpaceService platformSpaceService = mock(PlatformSpaceService.class);
        AgentAppService agentAppService = mock(AgentAppService.class);
        AgentAppManagementService service = new AgentAppManagementService(
                platformSpaceService,
                agentAppService,
                new ObjectMapper());

        PlatformSpace space = new PlatformSpace();
        space.setId(11L);
        space.setSpaceCode("enterprise-default");
        space.setEnvironment("prod");
        when(platformSpaceService.findActiveByCode("enterprise-default", "prod")).thenReturn(Optional.of(space));
        when(agentAppService.findActiveByCode(11L, "finance-agent")).thenReturn(Optional.empty());
        when(agentAppService.save(any(AgentApp.class))).thenReturn(true);

        Optional<ResolvedAgentAppManagementView> result = service.upsertAgentApp(
                "enterprise-default",
                "prod",
                "finance-agent",
                new AgentAppUpsertCommand(
                        "Finance Agent",
                        Map.of("mode", "strict"),
                        Map.of("retention", "short"),
                        Map.of("required", true),
                        "active"));

        assertTrue(result.isPresent());
        assertEquals("finance-agent", result.get().agentAppCode());
        assertEquals("Finance Agent", result.get().displayName());
        assertEquals("strict", result.get().promptPolicy().get("mode"));
    }

    @Test
    void shouldUpdateAgentAppWhenCodeExists() {
        PlatformSpaceService platformSpaceService = mock(PlatformSpaceService.class);
        AgentAppService agentAppService = mock(AgentAppService.class);
        AgentAppManagementService service = new AgentAppManagementService(
                platformSpaceService,
                agentAppService,
                new ObjectMapper());

        PlatformSpace space = new PlatformSpace();
        space.setId(12L);
        space.setSpaceCode("enterprise-default");
        space.setEnvironment("test");
        AgentApp existing = agentApp(41L, 12L, "finance-agent", "Finance Agent", "active");
        existing.setPromptPolicyJson("{\"mode\":\"lenient\"}");
        when(platformSpaceService.findActiveByCode("enterprise-default", "test")).thenReturn(Optional.of(space));
        when(agentAppService.findActiveByCode(12L, "finance-agent")).thenReturn(Optional.of(existing));
        when(agentAppService.updateById(any(AgentApp.class))).thenReturn(true);

        Optional<ResolvedAgentAppManagementView> result = service.upsertAgentApp(
                "enterprise-default",
                "test",
                "finance-agent",
                new AgentAppUpsertCommand(
                        "Finance Agent v2",
                        Map.of("mode", "strict"),
                        Map.of("retention", "long"),
                        Map.of("required", false),
                        "active"));

        assertTrue(result.isPresent());
        assertEquals("Finance Agent v2", result.get().displayName());
        assertEquals("strict", result.get().promptPolicy().get("mode"));
        assertEquals("long", result.get().memoryPolicy().get("retention"));
    }

    private AgentApp agentApp(Long id, Long spaceId, String code, String displayName, String status) {
        AgentApp app = new AgentApp();
        app.setId(id);
        app.setSpaceId(spaceId);
        app.setAgentAppCode(code);
        app.setDisplayName(displayName);
        app.setStatus(status);
        return app;
    }
}


