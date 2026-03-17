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

import com.alibaba.assistant.agent.controlplane.rolepackage.mapper.RoleKpiMetricMapper;
import com.alibaba.assistant.agent.controlplane.rolepackage.mapper.RolePackageMapper;
import com.alibaba.assistant.agent.controlplane.rolepackage.mapper.RoleProactiveTaskMapper;
import com.alibaba.assistant.agent.controlplane.rolepackage.mapper.RoleScenarioMapper;
import com.alibaba.assistant.agent.controlplane.rolepackage.mapper.RoleToolScopeMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RolePackageServiceTest {

    @Test
    void shouldCreateDraftRolePackageWithScenariosAndToolScope() {
        RolePackageMapper rolePackageMapper = mock(RolePackageMapper.class);
        RoleScenarioMapper roleScenarioMapper = mock(RoleScenarioMapper.class);
        RoleProactiveTaskMapper roleProactiveTaskMapper = mock(RoleProactiveTaskMapper.class);
        RoleToolScopeMapper roleToolScopeMapper = mock(RoleToolScopeMapper.class);
        RoleKpiMetricMapper roleKpiMetricMapper = mock(RoleKpiMetricMapper.class);
        RolePackageService service = new RolePackageService(
                rolePackageMapper,
                roleScenarioMapper,
                roleProactiveTaskMapper,
                roleToolScopeMapper,
                roleKpiMetricMapper);

        when(rolePackageMapper.insert(any(RolePackage.class))).thenAnswer(invocation -> {
            RolePackage rolePackage = invocation.getArgument(0);
            rolePackage.setId(101L);
            return 1;
        });
        when(roleScenarioMapper.insert(any(RoleScenario.class))).thenReturn(1);
        when(roleToolScopeMapper.insert(any(RoleToolScope.class))).thenReturn(1);

        Optional<ResolvedRolePackageManagementView> result = service.upsertDraft(
                10L,
                "finance-agent",
                new RolePackageUpsertCommand(
                        "digital-admin",
                        "数字行政助理",
                        "负责审批、排期和通知。",
                        "v1",
                        "draft",
                        List.of(new RolePackageUpsertCommand.RoleScenarioInput(
                                "leave-approval",
                                "请假审批",
                                "处理请假审批",
                                Map.of("intent", "leave"))),
                        List.of(new RolePackageUpsertCommand.RoleToolScopeInput(
                                null,
                                "gougu_oa.leave_application",
                                "required")),
                        List.of(),
                        List.of()));

        assertTrue(result.isPresent());
        assertEquals("finance-agent", result.get().agentAppCode());
        assertEquals("digital-admin", result.get().roleCode());
        assertEquals("DRAFT", result.get().status());
        assertEquals(1, result.get().scenarios().size());
        assertEquals(1, result.get().toolScopes().size());
    }

    @Test
    void shouldBindPublishedRolePackageToManagedAgentApp() {
        RolePackageMapper rolePackageMapper = mock(RolePackageMapper.class);
        RoleScenarioMapper roleScenarioMapper = mock(RoleScenarioMapper.class);
        RoleProactiveTaskMapper roleProactiveTaskMapper = mock(RoleProactiveTaskMapper.class);
        RoleToolScopeMapper roleToolScopeMapper = mock(RoleToolScopeMapper.class);
        RoleKpiMetricMapper roleKpiMetricMapper = mock(RoleKpiMetricMapper.class);
        RolePackageService service = new RolePackageService(
                rolePackageMapper,
                roleScenarioMapper,
                roleProactiveTaskMapper,
                roleToolScopeMapper,
                roleKpiMetricMapper);

        RolePackage draft = rolePackage(101L, 10L, "finance-agent", "digital-admin", "v1", "draft");
        when(rolePackageMapper.selectLatest(any())).thenReturn(Optional.of(draft));
        when(rolePackageMapper.selectVersions(any())).thenReturn(List.of(draft));
        when(rolePackageMapper.updateById(any(RolePackage.class))).thenReturn(1);

        Optional<ResolvedRolePackageManagementView> result = service.publish(10L, "finance-agent", "digital-admin", "v1");

        assertTrue(result.isPresent());
        assertEquals("finance-agent", result.get().agentAppCode());
        assertEquals("digital-admin", result.get().roleCode());
        assertEquals("PUBLISHED", result.get().status());
    }

    @Test
    void shouldPublishOnlyOneVersionPerAgentAppAndRole() {
        RolePackageMapper rolePackageMapper = mock(RolePackageMapper.class);
        RoleScenarioMapper roleScenarioMapper = mock(RoleScenarioMapper.class);
        RoleProactiveTaskMapper roleProactiveTaskMapper = mock(RoleProactiveTaskMapper.class);
        RoleToolScopeMapper roleToolScopeMapper = mock(RoleToolScopeMapper.class);
        RoleKpiMetricMapper roleKpiMetricMapper = mock(RoleKpiMetricMapper.class);
        RolePackageService service = new RolePackageService(
                rolePackageMapper,
                roleScenarioMapper,
                roleProactiveTaskMapper,
                roleToolScopeMapper,
                roleKpiMetricMapper);

        RolePackage published = rolePackage(100L, 10L, "finance-agent", "digital-admin", "v0", "published");
        RolePackage draft = rolePackage(101L, 10L, "finance-agent", "digital-admin", "v1", "draft");
        when(rolePackageMapper.selectLatest(any())).thenReturn(Optional.of(draft));
        when(rolePackageMapper.selectVersions(any())).thenReturn(List.of(published, draft));
        when(rolePackageMapper.updateById(any(RolePackage.class))).thenReturn(1);

        Optional<ResolvedRolePackageManagementView> result = service.publish(10L, "finance-agent", "digital-admin", "v1");

        assertTrue(result.isPresent());
        assertEquals("PUBLISHED", result.get().status());
    }

    private RolePackage rolePackage(
            Long id,
            Long spaceId,
            String agentAppCode,
            String roleCode,
            String version,
            String status) {
        RolePackage rolePackage = new RolePackage();
        rolePackage.setId(id);
        rolePackage.setSpaceId(spaceId);
        rolePackage.setAgentAppCode(agentAppCode);
        rolePackage.setRoleCode(roleCode);
        rolePackage.setVersion(version);
        rolePackage.setStatus(status);
        rolePackage.setDisplayName("数字行政助理");
        rolePackage.setPersona("负责审批、排期和通知。");
        return rolePackage;
    }
}
