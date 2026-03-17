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
package com.alibaba.assistant.agent.controlplane.rolepackage;

import com.alibaba.assistant.agent.controlplane.agentapp.AgentApp;
import com.alibaba.assistant.agent.controlplane.agentapp.AgentAppGrantService;
import com.alibaba.assistant.agent.controlplane.agentapp.AgentAppPublicationSourcePolicy;
import com.alibaba.assistant.agent.controlplane.agentapp.AgentAppService;
import com.alibaba.assistant.agent.controlplane.space.PlatformSpace;
import com.alibaba.assistant.agent.controlplane.space.PlatformSpaceService;
import com.alibaba.assistant.agent.controlplane.toolregistry.ToolMeta;
import com.alibaba.assistant.agent.controlplane.toolregistry.ToolMetaService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RolePackageManagementServiceTest {

    @Test
    void shouldRejectToolScopeOutsideAgentAppPublicationPolicy() {
        PlatformSpaceService platformSpaceService = mock(PlatformSpaceService.class);
        AgentAppService agentAppService = mock(AgentAppService.class);
        AgentAppGrantService agentAppGrantService = mock(AgentAppGrantService.class);
        ToolMetaService toolMetaService = mock(ToolMetaService.class);
        RolePackageService rolePackageService = mock(RolePackageService.class);
        RolePackagePublisher rolePackagePublisher = mock(RolePackagePublisher.class);
        RolePackageManagementService service = new RolePackageManagementService(
                platformSpaceService,
                agentAppService,
                agentAppGrantService,
                toolMetaService,
                rolePackageService,
                rolePackagePublisher);

        PlatformSpace space = new PlatformSpace();
        space.setId(10L);
        space.setSpaceCode("enterprise-default");
        space.setEnvironment("prod");
        AgentApp agentApp = new AgentApp();
        agentApp.setId(12L);
        agentApp.setSpaceId(10L);
        agentApp.setAgentAppCode("finance-agent");
        ToolMeta toolMeta = new ToolMeta();
        toolMeta.setToolCode("gougu_oa.leave_application");
        toolMeta.setStatus("enabled");
        when(platformSpaceService.findActiveByCode("enterprise-default", "prod")).thenReturn(Optional.of(space));
        when(agentAppService.findActiveByCode(10L, "finance-agent")).thenReturn(Optional.of(agentApp));
        when(agentAppGrantService.findPublicationSourcePolicy(12L)).thenReturn(Optional.of(
                new AgentAppPublicationSourcePolicy("exclusive", List.of("mcp-gateway"), List.of("tool-meta-catalog"))));
        when(toolMetaService.findLatestEnabledByToolCode(any(), any())).thenReturn(Optional.of(toolMeta));
        when(rolePackageService.getRolePackage("digital-admin", "v1", 10L, "finance-agent")).thenReturn(Optional.of(
                new ResolvedRolePackageManagementView(
                        101L,
                        "enterprise-default",
                        "prod",
                        "finance-agent",
                        "digital-admin",
                        "数字行政助理",
                        "负责审批、排期和通知。",
                        "v1",
                        "DRAFT",
                        List.of(),
                        List.of(new ResolvedRolePackageManagementView.RoleToolScopeView(
                                null,
                                "gougu_oa.leave_application",
                                "REQUIRED")),
                        List.of(),
                        List.of())));

        assertThrows(IllegalStateException.class, () -> service.publishRolePackage(
                "enterprise-default",
                "prod",
                "finance-agent",
                "digital-admin",
                "v1"));
    }
}
