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

import com.alibaba.assistant.agent.start.saas.app.model.ResolvedCapabilityInfo;
import com.alibaba.assistant.agent.start.saas.controller.dto.GuardedChatRequest;
import com.alibaba.assistant.agent.start.saas.infrastructure.mybatis.dataobject.ConnectorApiDO;
import com.alibaba.assistant.agent.start.saas.infrastructure.mybatis.dataobject.ConnectorAuthDO;
import com.alibaba.assistant.agent.start.saas.infrastructure.mybatis.dataobject.ConnectorDO;

import java.util.Map;

/**
 * Connector invocation context.
 *
 * @author Assistant Agent Team
 * @since 1.0.0
 */
public class ConnectorInvokeContext {

    private String tenantId;

    private String sessionId;

    private GuardedChatRequest request;

    private ConnectorDO connector;

    private ConnectorAuthDO connectorAuth;

    private ConnectorApiDO connectorApi;

    private ResolvedCapabilityInfo capability;

    private String stepCode;

    private Map<String, Object> stepInput;

    private Map<String, String> authHeaders;

    private Map<String, String> authCookies;

    private Map<String, String> stepHeaders;

    private String stepRequestMode;

    private String delegatedExternalUserId;

    public String getTenantId() {
        return tenantId;
    }

    public void setTenantId(String tenantId) {
        this.tenantId = tenantId;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public GuardedChatRequest getRequest() {
        return request;
    }

    public void setRequest(GuardedChatRequest request) {
        this.request = request;
    }

    public ConnectorDO getConnector() {
        return connector;
    }

    public void setConnector(ConnectorDO connector) {
        this.connector = connector;
    }

    public ConnectorAuthDO getConnectorAuth() {
        return connectorAuth;
    }

    public void setConnectorAuth(ConnectorAuthDO connectorAuth) {
        this.connectorAuth = connectorAuth;
    }

    public ConnectorApiDO getConnectorApi() {
        return connectorApi;
    }

    public void setConnectorApi(ConnectorApiDO connectorApi) {
        this.connectorApi = connectorApi;
    }

    public ResolvedCapabilityInfo getCapability() {
        return capability;
    }

    public void setCapability(ResolvedCapabilityInfo capability) {
        this.capability = capability;
    }

    public String getStepCode() {
        return stepCode;
    }

    public void setStepCode(String stepCode) {
        this.stepCode = stepCode;
    }

    public Map<String, Object> getStepInput() {
        return stepInput;
    }

    public void setStepInput(Map<String, Object> stepInput) {
        this.stepInput = stepInput;
    }

    public Map<String, String> getAuthHeaders() {
        return authHeaders;
    }

    public void setAuthHeaders(Map<String, String> authHeaders) {
        this.authHeaders = authHeaders;
    }

    public Map<String, String> getAuthCookies() {
        return authCookies;
    }

    public void setAuthCookies(Map<String, String> authCookies) {
        this.authCookies = authCookies;
    }

    public Map<String, String> getStepHeaders() {
        return stepHeaders;
    }

    public void setStepHeaders(Map<String, String> stepHeaders) {
        this.stepHeaders = stepHeaders;
    }

    public String getStepRequestMode() {
        return stepRequestMode;
    }

    public void setStepRequestMode(String stepRequestMode) {
        this.stepRequestMode = stepRequestMode;
    }

    public String getDelegatedExternalUserId() {
        return delegatedExternalUserId;
    }

    public void setDelegatedExternalUserId(String delegatedExternalUserId) {
        this.delegatedExternalUserId = delegatedExternalUserId;
    }
}
