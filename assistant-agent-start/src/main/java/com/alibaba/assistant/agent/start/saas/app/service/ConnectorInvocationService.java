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
package com.alibaba.assistant.agent.start.saas.app.service;

import com.alibaba.assistant.agent.start.saas.infrastructure.connector.ConnectorInvokeContext;
import com.alibaba.assistant.agent.start.saas.infrastructure.connector.ConnectorInvoker;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * Route connector invocation by connector type.
 *
 * @author Assistant Agent Team
 * @since 1.0.0
 */
@Service
public class ConnectorInvocationService {

    private final List<ConnectorInvoker> connectorInvokers;

    public ConnectorInvocationService(List<ConnectorInvoker> connectorInvokers) {
        this.connectorInvokers = connectorInvokers;
    }

    /**
     * Invoke connector API through matched invoker.
     *
     * @param context invocation context
     * @return output payload
     */
    public Map<String, Object> invoke(ConnectorInvokeContext context) {
        for (ConnectorInvoker invoker : connectorInvokers) {
            if (invoker.supports(context.getConnector().getConnectorType())) {
                return invoker.invoke(context);
            }
        }
        throw new IllegalArgumentException("no connector invoker found");
    }
}
