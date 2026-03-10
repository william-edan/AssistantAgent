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
package com.alibaba.assistant.agent.runtime.execution;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ExecutionEventStreamRegistryTest {

    @Test
    void shouldPublishEventToActiveThreadSubscription() {
        ExecutionEventStreamRegistry registry = new ExecutionEventStreamRegistry();
        ExecutionEventStreamRegistry.ExecutionEventSubscription subscription = registry.open("T-1");
        ExecutionEvent event = new ExecutionEvent(
                "RUN-1",
                "oa.leave.apply",
                "WORKFLOW",
                "create_leave",
                1L,
                ExecutionEventType.STEP_STARTED,
                ExecutionLifecycleStatus.RUNNING,
                Instant.parse("2026-03-10T13:00:00Z"),
                Map.of("stepName", "创建请假记录"));
        try {
            registry.publish("T-1", event);
            List<ExecutionEvent> events = subscription.flux().take(1).collectList().block();
            assertEquals(List.of(event), events);
        }
        finally {
            subscription.close();
        }
    }
}
