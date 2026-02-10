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

import com.alibaba.assistant.agent.start.saas.infrastructure.mybatis.dataobject.ConnectorAuthDO;
import com.alibaba.assistant.agent.start.saas.infrastructure.mybatis.dataobject.ConnectorDO;
import com.alibaba.assistant.agent.start.saas.infrastructure.mybatis.dataobject.UserBindingDO;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Default connector auth provider.
 *
 * @author Assistant Agent Team
 * @since 1.0.0
 */
@Component
@Order(9999)
public class DefaultConnectorAuthProvider implements ConnectorAuthProvider {

    private static final String AUTH_TYPE_SESSION = "SESSION";

    private static final String AUTH_TYPE_BEARER = "BEARER";

    private static final String AUTH_TYPE_BASIC = "BASIC";

    private final ObjectMapper objectMapper;

    public DefaultConnectorAuthProvider(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean supports(String authType) {
        return true;
    }

    @Override
    public ResolvedConnectorAuth resolve(ConnectorDO connector, ConnectorAuthDO connectorAuth, UserBindingDO userBinding) {
        ResolvedConnectorAuth auth = new ResolvedConnectorAuth();
        String authType = connectorAuth.getAuthType() == null ? "" : connectorAuth.getAuthType().trim().toUpperCase();
        JsonNode configNode = toNode(connectorAuth.getAuthConfigJson());

        if (AUTH_TYPE_SESSION.equals(authType)) {
            String cookieName = text(configNode, "sessionCookieName", "PHPSESSID");
            String token = text(configNode, "sessionToken", null);
            if (token == null) {
                token = text(configNode, "token", null);
            }
            if (token != null && !token.isBlank()) {
                auth.getCookies().put(cookieName, token);
            }
        }
        else if (AUTH_TYPE_BEARER.equals(authType)) {
            String token = text(configNode, "accessToken", null);
            if (token == null) {
                token = text(configNode, "token", null);
            }
            if (token != null && !token.isBlank()) {
                auth.getHeaders().put("Authorization", "Bearer " + token);
            }
        }
        else if (AUTH_TYPE_BASIC.equals(authType)) {
            String username = text(configNode, "username", "");
            String password = text(configNode, "password", "");
            String raw = username + ":" + password;
            String encoded = Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
            auth.getHeaders().put("Authorization", "Basic " + encoded);
        }

        if (userBinding != null && userBinding.getExternalUserId() != null) {
            auth.getHeaders().put("X-External-User-Id", userBinding.getExternalUserId());
        }
        return auth;
    }

    private JsonNode toNode(String json) {
        if (json == null || json.isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            return objectMapper.readTree(json);
        }
        catch (Exception ex) {
            return objectMapper.createObjectNode();
        }
    }

    private String text(JsonNode node, String field, String defaultValue) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return defaultValue;
        }
        String text = value.asText();
        return text == null || text.isBlank() ? defaultValue : text;
    }
}
