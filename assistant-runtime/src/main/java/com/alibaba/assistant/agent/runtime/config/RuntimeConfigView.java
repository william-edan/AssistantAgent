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

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Read-only view over {@code assistant.runtime.*} feature flags and budgets.
 *
 * @author Assistant Agent Team
 * @since 1.0.0
 */
@Component
@Profile("migration")
public class RuntimeConfigView {

    private final AssistantRuntimeProperties runtimeProperties;

    public RuntimeConfigView(AssistantRuntimeProperties runtimeProperties) {
        this.runtimeProperties = runtimeProperties;
    }

    public boolean fastIntentEnabled() {
        return runtimeProperties.getFastIntent().isEnabled();
    }

    public boolean promptDynamicEnabled() {
        return runtimeProperties.getPrompt().isDynamicEnabled();
    }

    public boolean promptReactEnabled() {
        return runtimeProperties.getPrompt().isReactEnabled();
    }

    public boolean promptCodeactEnabled() {
        return runtimeProperties.getPrompt().isCodeactEnabled();
    }

    public int promptMaxToolsInPrompt() {
        return runtimeProperties.getPrompt().getMaxToolsInPrompt();
    }

    public boolean learningAsyncEnabled() {
        return runtimeProperties.getLearning().isAsync();
    }

    public boolean searchEnabled() {
        return runtimeProperties.getSearch().isEnabled();
    }

    public boolean policyGuardEnabled() {
        return runtimeProperties.getPolicyGuard().isEnabled();
    }

    public long budgetMaxLatencyMs() {
        return runtimeProperties.getBudget().getMaxLatencyMs();
    }

    public int budgetMaxToolCalls() {
        return runtimeProperties.getBudget().getMaxToolCalls();
    }
}
