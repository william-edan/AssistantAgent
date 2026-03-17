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
package com.alibaba.assistant.agent.runtime.agent;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AgentProfileResolverTest {

    @Test
    void shouldDefaultToFormFlowWhenNoRolePackageContextPresent() {
        AgentProfileResolver resolver = new AgentProfileResolver(new AgentPromptTemplateFactory());

        AgentProfile profile = resolver.resolve(Map.of(
                "thread_id", "thread-1",
                "assistant_uid", "assistant-1001"));

        assertEquals(AgentProfile.FORM_FLOW, profile.profileCode());
    }
}
