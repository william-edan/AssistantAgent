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
package com.alibaba.assistant.agent.runtime.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeConfigViewTest {

    @Test
    void shouldReadRuntimeNamespaceValues() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("assistant.runtime.fast-intent.enabled", "true")
                .withProperty("assistant.runtime.prompt.dynamic-enabled", "false")
                .withProperty("assistant.runtime.prompt.react-enabled", "false")
                .withProperty("assistant.runtime.prompt.codeact-enabled", "false")
                .withProperty("assistant.runtime.prompt.max-tools-in-prompt", "12")
                .withProperty("assistant.runtime.learning.async", "false")
                .withProperty("assistant.runtime.search.enabled", "false")
                .withProperty("assistant.runtime.policy-guard.enabled", "false")
                .withProperty("assistant.runtime.budget.max-tool-calls", "9")
                .withProperty("assistant.runtime.budget.max-latency-ms", "15000");

        AssistantRuntimeProperties runtime = bindRuntime(environment);
        RuntimeConfigView view = new RuntimeConfigView(runtime);

        assertTrue(view.fastIntentEnabled());
        assertFalse(view.promptDynamicEnabled());
        assertFalse(view.promptReactEnabled());
        assertFalse(view.promptCodeactEnabled());
        assertEquals(12, view.promptMaxToolsInPrompt());
        assertFalse(view.learningAsyncEnabled());
        assertFalse(view.searchEnabled());
        assertFalse(view.policyGuardEnabled());
        assertEquals(9, view.budgetMaxToolCalls());
        assertEquals(15000L, view.budgetMaxLatencyMs());
    }

    @Test
    void shouldFallbackToPropertyDefaultsWhenValuesMissing() {
        AssistantRuntimeProperties runtime = bindRuntime(new MockEnvironment());
        RuntimeConfigView view = new RuntimeConfigView(runtime);

        assertFalse(view.fastIntentEnabled());
        assertTrue(view.promptDynamicEnabled());
        assertTrue(view.promptReactEnabled());
        assertTrue(view.promptCodeactEnabled());
        assertEquals(20, view.promptMaxToolsInPrompt());
        assertTrue(view.learningAsyncEnabled());
        assertTrue(view.searchEnabled());
        assertTrue(view.policyGuardEnabled());
        assertEquals(6, view.budgetMaxToolCalls());
        assertEquals(12000L, view.budgetMaxLatencyMs());
    }

    private AssistantRuntimeProperties bindRuntime(MockEnvironment environment) {
        return Binder.get(environment)
                .bind("assistant.runtime", Bindable.of(AssistantRuntimeProperties.class))
                .orElseGet(AssistantRuntimeProperties::new);
    }
}
