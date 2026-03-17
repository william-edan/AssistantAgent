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
package com.alibaba.assistant.agent.runtime.role;

import com.alibaba.assistant.agent.controlplane.rolepackage.ResolvedRolePackageManagementView;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ScenarioRouterTest {

    @Test
    void shouldResolveMeetingCoordinationBeforeFallbackToModel() {
        RoleContextResolver roleContextResolver = mock(RoleContextResolver.class);
        ScenarioRouter router = new ScenarioRouter(roleContextResolver);
        Map<String, Object> attrs = Map.of("role_package_code", "digital-admin");
        when(roleContextResolver.resolve(attrs)).thenReturn(Optional.of(new RoleContextResolver.RoleContext(
                10L,
                "finance-agent",
                "digital-admin",
                "v1",
                "数字行政助理",
                "负责审批、排期和通知。",
                null,
                List.of(
                        new ResolvedRolePackageManagementView.RoleScenarioView(
                                "leave-approval",
                                "请假审批",
                                "处理请假审批和补充资料",
                                Map.of("keywords", List.of("请假", "休假", "审批"))),
                        new ResolvedRolePackageManagementView.RoleScenarioView(
                                "meeting-coordination",
                                "会议协调",
                                "处理会议排期、会议室安排和通知",
                                Map.of("keywords", List.of("会议", "会议室", "排期", "通知")))),
                List.of())));

        String scenarioCode = router.resolveScenario(attrs, "帮我安排明天下午的会议室，并给参会人发通知").orElse(null);

        assertEquals("meeting-coordination", scenarioCode);
    }
}
