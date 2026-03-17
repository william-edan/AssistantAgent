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
import com.alibaba.assistant.agent.prompt.PromptContribution;
import com.alibaba.assistant.agent.prompt.PromptContributorContext;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RolePromptContributorTest {

    @Test
    void shouldInjectPersonaWhenThreadBoundToRolePackage() {
        RoleContextResolver roleContextResolver = mock(RoleContextResolver.class);
        ScenarioRouter scenarioRouter = mock(ScenarioRouter.class);
        RolePromptContributor contributor = new RolePromptContributor(roleContextResolver, scenarioRouter);
        Map<String, Object> attrs = Map.of("role_package_code", "digital-admin", "input", "帮我安排明天下午会议室");
        when(roleContextResolver.resolve(attrs)).thenReturn(Optional.of(new RoleContextResolver.RoleContext(
                10L,
                "finance-agent",
                "digital-admin",
                "v1",
                "数字行政助理",
                "负责审批、排期和通知。",
                null,
                List.of(new ResolvedRolePackageManagementView.RoleScenarioView(
                        "meeting-coordination",
                        "会议协调",
                        "处理会议排期与通知",
                        Map.of("keywords", List.of("会议室", "排期", "会议")))),
                List.of())));
        when(scenarioRouter.resolveScenario(attrs, "帮我安排明天下午会议室")).thenReturn(Optional.of("meeting-coordination"));

        PromptContribution contribution = contributor.contribute(context(attrs, List.of(new UserMessage("帮我安排明天下午会议室"))));

        assertFalse(contribution.isEmpty());
        assertTrue(contribution.messagesToAppend().get(0).getText().contains("数字行政助理"));
        assertTrue(contribution.messagesToAppend().get(0).getText().contains("负责审批、排期和通知"));
        assertTrue(contribution.messagesToAppend().get(0).getText().contains("会议协调"));
    }

    private PromptContributorContext context(Map<String, Object> attrs, List<Message> messages) {
        return new PromptContributorContext() {
            @Override
            public List<Message> getMessages() {
                return messages;
            }

            @Override
            public Optional<org.springframework.ai.chat.messages.SystemMessage> getSystemMessage() {
                return Optional.empty();
            }

            @Override
            public Map<String, Object> getAttributes() {
                return attrs;
            }

            @Override
            public Optional<String> getPhase() {
                return Optional.of("REACT");
            }
        };
    }
}
