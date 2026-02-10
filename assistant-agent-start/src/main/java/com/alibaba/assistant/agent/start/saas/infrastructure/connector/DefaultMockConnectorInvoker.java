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
package com.alibaba.assistant.agent.start.saas.infrastructure.connector;

import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * Default mock connector invoker.
 *
 * <p>It keeps core flow testable without requiring external system network.
 *
 * @author Assistant Agent Team
 * @since 1.0.0
 */
@Component
@Order(9999)
public class DefaultMockConnectorInvoker implements ConnectorInvoker {

    @Override
    public boolean supports(String connectorType) {
        return true;
    }

    @Override
    public Map<String, Object> invoke(ConnectorInvokeContext context) {
        Map<String, Object> result = new HashMap<>();
        result.put("connectorType", context.getConnector().getConnectorType());
        result.put("connectorCode", context.getConnector().getConnectorCode());
        result.put("apiCode", context.getConnectorApi().getApiCode());
        result.put("pathTemplate", context.getConnectorApi().getPathTemplate());
        result.put("simulated", true);
        result.put("echoInput", context.getStepInput() == null ? context.getRequest().getInput() : context.getStepInput());
        if ("/home/leaves/add".equals(context.getConnectorApi().getPathTemplate())) {
            result.put("data", Map.of("action_id", 1022));
        }
        if ("/api/check/submit_check".equals(context.getConnectorApi().getPathTemplate())) {
            result.put("data", Map.of("approved", true));
        }
        return result;
    }
}
