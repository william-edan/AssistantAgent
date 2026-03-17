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

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RolePackagePublisherTest {

    @Test
    void shouldPublishDigitalAdminAssistantWithFourCoreScenarios() {
        RolePackageService rolePackageService = mock(RolePackageService.class);
        RolePackagePublisher publisher = new RolePackagePublisher(rolePackageService);
        ResolvedRolePackageManagementView publishedView = new ResolvedRolePackageManagementView(
                101L,
                "default",
                "prod",
                "admin-agent",
                "digital-admin",
                "数字行政助理",
                "负责会议协调、周报收集、审批清理和值班请假协同。",
                "v1",
                "published",
                List.of(
                        new ResolvedRolePackageManagementView.RoleScenarioView("meeting_coordination", "会议协调", null, Map.of()),
                        new ResolvedRolePackageManagementView.RoleScenarioView("weekly_report_collection", "周报收集", null, Map.of()),
                        new ResolvedRolePackageManagementView.RoleScenarioView("approval_cleanup", "审批清理", null, Map.of()),
                        new ResolvedRolePackageManagementView.RoleScenarioView("leave_duty_coordination", "请假值班协调", null, Map.of())),
                List.of(),
                List.of(),
                List.of());
        when(rolePackageService.publish(10L, "admin-agent", "digital-admin", "v1"))
                .thenReturn(Optional.of(publishedView));

        Optional<ResolvedRolePackageManagementView> result = publisher.publish(10L, "admin-agent", "digital-admin", "v1");

        assertTrue(result.isPresent());
        assertEquals(4, result.get().scenarios().size());
        assertEquals(
                List.of("meeting_coordination", "weekly_report_collection", "approval_cleanup", "leave_duty_coordination"),
                result.get().scenarios().stream().map(ResolvedRolePackageManagementView.RoleScenarioView::scenarioCode).toList());
    }
}
