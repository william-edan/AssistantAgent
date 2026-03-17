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
package com.alibaba.assistant.agent.runtime.observability;

import com.alibaba.assistant.agent.runtime.execution.ExecutionEvent;
import com.alibaba.assistant.agent.runtime.execution.ExecutionEventType;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Estimates execution cost from runtime events until model billing is wired in.
 */
@Component
public class CostAccountant {

    public double estimate(List<ExecutionEvent> executionEvents) {
        if (executionEvents == null || executionEvents.isEmpty()) {
            return 0D;
        }
        double accumulated = 0D;
        for (ExecutionEvent event : executionEvents) {
            Map<String, Object> payload = event != null ? event.payload() : Map.of();
            Object explicitCost = payload.get("cost");
            if (explicitCost instanceof Number number) {
                accumulated += number.doubleValue();
                continue;
            }
            Object totalTokens = payload.get("totalTokens");
            if (totalTokens instanceof Number number) {
                accumulated += number.doubleValue();
                continue;
            }
            if (event != null && event.eventType() == ExecutionEventType.STEP_COMPLETED) {
                accumulated += 1D;
            }
        }
        return accumulated;
    }
}
