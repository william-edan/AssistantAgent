/*
 * Copyright 2024-2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 */
package com.alibaba.assistant.agent.controlplane.rolepackage;

import com.alibaba.assistant.agent.controlplane.rolepackage.mapper.RolePackageMapper;
import com.alibaba.assistant.agent.controlplane.rolepackage.mapper.RoleProactiveTaskMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RoleProactiveTaskQueryServiceTest {

    @Test
    void shouldResolvePublishedTasksByAgentAppScope() {
        RolePackageMapper packages = mock(RolePackageMapper.class);
        RoleProactiveTaskMapper tasks = mock(RoleProactiveTaskMapper.class);
        RoleProactiveTaskQueryService service = new RoleProactiveTaskQueryService(packages, tasks);

        when(packages.listPublished()).thenReturn(List.of(pkg(101L, "finance-agent"), pkg(102L, "ops-agent")));
        when(tasks.listByRolePackageId(101L)).thenReturn(List.of(
                task("approval_cleanup", "{\"channel\":\"email\"}", "enabled"),
                task("disabled", null, "draft")));
        when(tasks.listByRolePackageId(102L)).thenReturn(List.of(task("meeting_coordination", "{\"room\":\"A-1\"}", "enabled")));

        List<RoleProactiveTaskQueryService.PublishedRoleProactiveTask> result = service.listPublishedTasks();

        assertEquals(2, result.size());
        assertEquals("finance-agent", result.get(0).agentAppCode());
        assertEquals("approval_cleanup", result.get(0).taskCode());
        assertTrue(result.get(1).taskPayload().containsKey("room"));
    }

    private RolePackage pkg(Long id, String app) {
        RolePackage rolePackage = new RolePackage();
        rolePackage.setId(id);
        rolePackage.setSpaceId(10L);
        rolePackage.setAgentAppCode(app);
        rolePackage.setRoleCode("digital-admin");
        rolePackage.setVersion("v1");
        rolePackage.setStatus("published");
        return rolePackage;
    }

    private RoleProactiveTask task(String code, String payload, String status) {
        RoleProactiveTask task = new RoleProactiveTask();
        task.setTaskCode(code);
        task.setCronExpr("0 */5 * * * *");
        task.setArtifactCode("office1." + code);
        task.setTaskPayloadJson(payload);
        task.setStatus(status);
        return task;
    }
}
