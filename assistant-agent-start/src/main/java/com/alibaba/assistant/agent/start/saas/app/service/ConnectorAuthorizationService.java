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

import com.alibaba.assistant.agent.start.saas.infrastructure.connector.ConnectorAuthProvider;
import com.alibaba.assistant.agent.start.saas.infrastructure.connector.ResolvedConnectorAuth;
import com.alibaba.assistant.agent.start.saas.infrastructure.mybatis.dataobject.ConnectorAuthDO;
import com.alibaba.assistant.agent.start.saas.infrastructure.mybatis.dataobject.ConnectorDO;
import com.alibaba.assistant.agent.start.saas.infrastructure.mybatis.dataobject.UserBindingDO;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Resolve authorization for connector invocation.
 *
 * @author Assistant Agent Team
 * @since 1.0.0
 */
@Service
public class ConnectorAuthorizationService {

    private final List<ConnectorAuthProvider> connectorAuthProviders;

    public ConnectorAuthorizationService(List<ConnectorAuthProvider> connectorAuthProviders) {
        this.connectorAuthProviders = connectorAuthProviders;
    }

    /**
     * Resolve auth for a connector invoke.
     *
     * @param connector connector
     * @param connectorAuth auth config
     * @param userBinding user binding, nullable
     * @return resolved auth values
     */
    public ResolvedConnectorAuth resolve(ConnectorDO connector, ConnectorAuthDO connectorAuth, UserBindingDO userBinding) {
        for (ConnectorAuthProvider provider : connectorAuthProviders) {
            if (provider.supports(connectorAuth.getAuthType())) {
                return provider.resolve(connector, connectorAuth, userBinding);
            }
        }
        throw new IllegalArgumentException("no connector auth provider found");
    }
}
