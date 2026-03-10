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
package com.alibaba.assistant.agent.controlplane.identity;

import com.alibaba.assistant.agent.controlplane.agentapp.AgentApp;
import com.alibaba.assistant.agent.controlplane.agentapp.AgentAppService;
import com.alibaba.assistant.agent.controlplane.identity.mapper.LocalUserAccountMapper;
import com.alibaba.assistant.agent.controlplane.space.PlatformSpace;
import com.alibaba.assistant.agent.controlplane.space.PlatformSpaceService;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LocalUserControlPlaneAccessPolicyServiceTest {

    @Mock
    private PlatformSpaceService platformSpaceService;

    @Mock
    private AgentAppService agentAppService;

    @Mock
    private LocalUserGrantService localUserGrantService;

    @Mock
    private LocalUserAccountMapper localUserAccountMapper;

    private LocalUserControlPlaneAccessPolicyService service;

    @BeforeEach
    void setUp() {
        service = new LocalUserControlPlaneAccessPolicyService(
                platformSpaceService,
                agentAppService,
                localUserGrantService,
                localUserAccountMapper);
    }

    @Test
    void shouldResolveLocalUserScopedControlPlaneAccessPolicy() {
        when(platformSpaceService.findActiveByCode("enterprise-default", "prod"))
                .thenReturn(Optional.of(space(9L, "enterprise-default", "prod")));
        when(localUserAccountMapper.selectById(1001L)).thenReturn(localUser(1001L, "admin"));
        when(localUserGrantService.hasGrant(1001L, "role", "assistant_space_admin", "space", "enterprise-default"))
                .thenReturn(true);
        when(localUserGrantService.list(any(Wrapper.class)))
                .thenReturn(List.of(
                        grant("assistant_agent_app_admin", "agent_app", "enterprise-default/prod/finance-agent"),
                        grant("assistant_agent_app_admin", "agent_app", "enterprise-default/prod/hr-agent"),
                        grant("assistant_agent_app_admin", "agent_app", "other-space/prod/ignored-agent")));

        ResolvedLocalUserControlPlaneAccessPolicy resolved = service
                .getPolicy("enterprise-default", "prod", 1001L)
                .orElseThrow();

        assertEquals(1001L, resolved.localUserId());
        assertEquals("admin", resolved.username());
        assertTrue(resolved.policy().spaceAdmin());
        assertEquals(List.of("finance-agent", "hr-agent"), resolved.policy().agentAppAdminCodes());
    }

    @Test
    void shouldReplaceScopedControlPlaneAccessPolicy() {
        when(platformSpaceService.findActiveByCode("enterprise-default", "prod"))
                .thenReturn(Optional.of(space(9L, "enterprise-default", "prod")));
        when(localUserAccountMapper.selectById(1001L)).thenReturn(localUser(1001L, "admin"));
        when(agentAppService.findActiveByCode(9L, "finance-agent"))
                .thenReturn(Optional.of(agentApp(7L, 9L, "finance-agent")));
        when(localUserGrantService.remove(any(Wrapper.class))).thenReturn(true);
        when(localUserGrantService.saveBatch(any(Collection.class))).thenReturn(true);

        ResolvedLocalUserControlPlaneAccessPolicy resolved = service
                .replacePolicy(
                        "enterprise-default",
                        "prod",
                        1001L,
                        new LocalUserControlPlaneAccessPolicy(true, List.of("finance-agent")))
                .orElseThrow();

        assertTrue(resolved.policy().spaceAdmin());
        assertEquals(List.of("finance-agent"), resolved.policy().agentAppAdminCodes());

        ArgumentCaptor<Collection<LocalUserGrant>> grantCaptor = ArgumentCaptor.forClass(Collection.class);
        verify(localUserGrantService).saveBatch(grantCaptor.capture());
        assertEquals(2, grantCaptor.getValue().size());
    }

    private PlatformSpace space(Long id, String code, String environment) {
        PlatformSpace space = new PlatformSpace();
        space.setId(id);
        space.setSpaceCode(code);
        space.setEnvironment(environment);
        space.setStatus("active");
        return space;
    }

    private LocalUserAccount localUser(Long id, String username) {
        LocalUserAccount user = new LocalUserAccount();
        user.setId(id);
        user.setUsername(username);
        user.setDisplayName("管理员");
        user.setStatus("active");
        return user;
    }

    private LocalUserGrant grant(String roleCode, String scopeType, String scopeCode) {
        LocalUserGrant grant = new LocalUserGrant();
        grant.setGrantType("role");
        grant.setGrantCode(roleCode);
        grant.setScopeType(scopeType);
        grant.setScopeCode(scopeCode);
        grant.setStatus("active");
        return grant;
    }

    private AgentApp agentApp(Long id, Long spaceId, String code) {
        AgentApp agentApp = new AgentApp();
        agentApp.setId(id);
        agentApp.setSpaceId(spaceId);
        agentApp.setAgentAppCode(code);
        agentApp.setStatus("active");
        return agentApp;
    }
}
