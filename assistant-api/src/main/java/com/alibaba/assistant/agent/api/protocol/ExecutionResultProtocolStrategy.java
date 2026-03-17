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
package com.alibaba.assistant.agent.api.protocol;

import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Adapts artifact execution results to task/result events.
 */
@Component
@Profile("migration")
@Order(120)
public class ExecutionResultProtocolStrategy implements ProtocolStrategy {

    private final ProtocolPayloadSupport payloadSupport;

    public ExecutionResultProtocolStrategy(ProtocolPayloadSupport payloadSupport) {
        this.payloadSupport = payloadSupport;
    }

    @Override
    public boolean supports(String normalizedToolName, Map<String, Object> payload) {
        return "artifact_execute".equals(normalizedToolName) || "execute_code".equals(normalizedToolName);
    }

    @Override
    public List<FrontendEvent> adapt(
            String threadId,
            String normalizedToolName,
            Map<String, Object> payload,
            Map<String, Object> state) {
        return payloadSupport.adaptExecutionResult(threadId, payload, state);
    }

    @Override
    public Map<String, Object> projectThreadState(
            String normalizedToolName,
            Map<String, Object> payload,
            Map<String, Object> state) {
        return payloadSupport.projectResultState(payload, state);
    }
}
