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
package com.alibaba.assistant.agent.runtime.proactive;

import com.alibaba.assistant.agent.controlplane.rolepackage.RoleProactiveTaskQueryService;
import com.alibaba.assistant.agent.controlplane.rolepackage.SubjectResolverCapability;
import com.alibaba.assistant.agent.execution.persistence.ProactiveRunLease;
import com.alibaba.assistant.agent.runtime.agent.AssistantStateKeys;
import com.alibaba.assistant.agent.runtime.execution.ArtifactRunDispatcher;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RoleProactiveTaskDispatcherTest {

    @Test
    void shouldResolveSubjectCapabilityIntoArtifactArguments() {
        ArtifactRunDispatcher artifactRunDispatcher = mock(ArtifactRunDispatcher.class);
        SubjectResolverCapability subjectResolver = mock(SubjectResolverCapability.class);
        RoleProactiveTaskDispatcher dispatcher = new RoleProactiveTaskDispatcher(
                artifactRunDispatcher,
                List.of(subjectResolver));

        RoleProactiveTaskQueryService.PublishedRoleProactiveTask task = new RoleProactiveTaskQueryService.PublishedRoleProactiveTask(
                10L,
                "prod",
                "admin-agent",
                "digital-admin",
                "v1",
                "weekly_report_collection",
                "0 */10 * * * *",
                "office1.weekly_report_collection",
                "weekly_report_collection",
                Map.of(
                        "report_type", "weekly",
                        "subject", Map.of(
                                "platformPrincipalId", "digital-admin-duty-bot",
                                "platformPrincipalType", "service_account",
                                "subjectType", "service_account",
                                "subjectId", "office1.bot")));
        ProactiveRunLease lease = new ProactiveRunLease();
        lease.setId(9L);

        when(subjectResolver.supports(task)).thenReturn(true);
        when(subjectResolver.resolveSubjectArguments(task)).thenReturn(Map.of(
                "platform_principal_id", "digital-admin-duty-bot",
                "platform_principal_type", "service_account",
                "execution_subject_type", "service_account",
                "execution_subject_id", "office1.bot"));

        dispatcher.dispatch(task, lease);

        ArgumentCaptor<Map<String, Object>> argumentsCaptor = ArgumentCaptor.forClass(Map.class);
        ArgumentCaptor<Map<String, Object>> contextCaptor = ArgumentCaptor.forClass(Map.class);
        verify(artifactRunDispatcher).dispatch(eq("office1.weekly_report_collection"),
                argumentsCaptor.capture(),
                contextCaptor.capture(),
                eq(lease));

        assertEquals("weekly", argumentsCaptor.getValue().get("report_type"));
        assertEquals("office1.bot", argumentsCaptor.getValue().get("execution_subject_id"));
        assertEquals("digital-admin-duty-bot", argumentsCaptor.getValue().get("platform_principal_id"));
        assertEquals("admin-agent", contextCaptor.getValue().get(AssistantStateKeys.AGENT_APP_CODE));
        assertEquals("digital-admin", contextCaptor.getValue().get(AssistantStateKeys.ROLE_PACKAGE_CODE));
        assertTrue(contextCaptor.getValue().containsKey(AssistantStateKeys.ROLE_SCENARIO_CODE));
    }
}
