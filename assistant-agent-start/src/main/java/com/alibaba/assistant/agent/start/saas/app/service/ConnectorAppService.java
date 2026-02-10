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

import com.alibaba.assistant.agent.start.saas.context.SaaSTenantContextHolder;
import com.alibaba.assistant.agent.start.saas.controller.dto.ConnectorResponse;
import com.alibaba.assistant.agent.start.saas.controller.dto.ConnectorTestResponse;
import com.alibaba.assistant.agent.start.saas.controller.dto.CreateConnectorRequest;
import com.alibaba.assistant.agent.start.saas.controller.dto.UpdateConnectorAuthRequest;
import com.alibaba.assistant.agent.start.saas.infrastructure.mybatis.dataobject.ConnectorAuthDO;
import com.alibaba.assistant.agent.start.saas.infrastructure.mybatis.dataobject.ConnectorDO;
import com.alibaba.assistant.agent.start.saas.infrastructure.mybatis.mapper.ConnectorAuthMapper;
import com.alibaba.assistant.agent.start.saas.infrastructure.mybatis.mapper.ConnectorMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Application service for connector management.
 *
 * @author Assistant Agent Team
 * @since 1.0.0
 */
@Service
public class ConnectorAppService {

    private static final Logger log = LoggerFactory.getLogger(ConnectorAppService.class);

    private final ConnectorMapper connectorMapper;

    private final ConnectorAuthMapper connectorAuthMapper;

    public ConnectorAppService(ConnectorMapper connectorMapper, ConnectorAuthMapper connectorAuthMapper) {
        this.connectorMapper = connectorMapper;
        this.connectorAuthMapper = connectorAuthMapper;
    }

    /**
     * Create connector.
     *
     * @param tenantId tenant id
     * @param request request
     * @return response
     */
    @Transactional(rollbackFor = Exception.class)
    public ConnectorResponse createConnector(String tenantId, CreateConnectorRequest request) {
        return runWithTenant(tenantId, () -> {
            ConnectorDO existed = connectorMapper.selectOne(Wrappers.lambdaQuery(ConnectorDO.class)
                    .eq(ConnectorDO::getConnectorCode, request.getConnectorCode()));
            if (existed != null) {
                throw new IllegalArgumentException("connector already exists");
            }

            ConnectorDO connector = new ConnectorDO();
            connector.setTenantId(tenantId);
            connector.setConnectorCode(request.getConnectorCode());
            connector.setDisplayName(request.getDisplayName());
            connector.setConnectorType(request.getConnectorType());
            connector.setBaseUrl(request.getBaseUrl());
            connector.setStatus("ACTIVE");
            connector.setCreatedBy(getOperator(request.getOperator()));
            connector.setUpdatedBy(getOperator(request.getOperator()));
            connectorMapper.insert(connector);

            log.info("ConnectorAppService#createConnector - reason=connector created, tenantId={}, connectorCode={}",
                    tenantId, request.getConnectorCode());
            return toResponse(connector);
        });
    }

    /**
     * Upsert connector auth config.
     *
     * @param tenantId tenant id
     * @param connectorId connector id
     * @param request request
     */
    @Transactional(rollbackFor = Exception.class)
    public void updateAuth(String tenantId, Long connectorId, UpdateConnectorAuthRequest request) {
        runWithTenant(tenantId, () -> {
            ConnectorDO connector = connectorMapper.selectById(connectorId);
            if (connector == null) {
                throw new IllegalArgumentException("connector not found");
            }

            ConnectorAuthDO auth = connectorAuthMapper.selectOne(Wrappers.lambdaQuery(ConnectorAuthDO.class)
                    .eq(ConnectorAuthDO::getConnectorId, connectorId));

            if (auth == null) {
                auth = new ConnectorAuthDO();
                auth.setTenantId(tenantId);
                auth.setConnectorId(connectorId);
                auth.setAuthType(request.getAuthType());
                auth.setAuthConfigJson(request.getAuthConfigJson());
                auth.setStatus("ACTIVE");
                auth.setCreatedBy(getOperator(request.getOperator()));
                auth.setUpdatedBy(getOperator(request.getOperator()));
                connectorAuthMapper.insert(auth);
            }
            else {
                auth.setAuthType(request.getAuthType());
                auth.setAuthConfigJson(request.getAuthConfigJson());
                auth.setStatus("ACTIVE");
                auth.setUpdatedBy(getOperator(request.getOperator()));
                connectorAuthMapper.updateById(auth);
            }

            log.info("ConnectorAppService#updateAuth - reason=connector auth updated, tenantId={}, connectorId={}",
                    tenantId, connectorId);
            return null;
        });
    }

    /**
     * Test connector availability.
     *
     * @param tenantId tenant id
     * @param connectorId connector id
     * @return test result
     */
    public ConnectorTestResponse testConnector(String tenantId, Long connectorId) {
        return runWithTenant(tenantId, () -> {
            ConnectorDO connector = connectorMapper.selectById(connectorId);
            if (connector == null) {
                throw new IllegalArgumentException("connector not found");
            }
            ConnectorAuthDO auth = connectorAuthMapper.selectOne(Wrappers.lambdaQuery(ConnectorAuthDO.class)
                    .eq(ConnectorAuthDO::getConnectorId, connectorId));

            ConnectorTestResponse response = new ConnectorTestResponse();
            response.setConnectorId(connectorId);
            response.setOk(auth != null && "ACTIVE".equals(connector.getStatus()));
            response.setMessage(auth == null ? "auth not configured" : "connector config is valid");
            return response;
        });
    }

    /**
     * List connectors.
     *
     * @param tenantId tenant id
     * @return list
     */
    public List<ConnectorResponse> listConnectors(String tenantId) {
        return runWithTenant(tenantId, () -> connectorMapper.selectList(Wrappers.lambdaQuery(ConnectorDO.class)
                        .orderByDesc(ConnectorDO::getId))
                .stream()
                .map(this::toResponse)
                .collect(Collectors.toList()));
    }

    private ConnectorResponse toResponse(ConnectorDO connector) {
        ConnectorResponse response = new ConnectorResponse();
        response.setId(connector.getId());
        response.setConnectorCode(connector.getConnectorCode());
        response.setDisplayName(connector.getDisplayName());
        response.setConnectorType(connector.getConnectorType());
        response.setBaseUrl(connector.getBaseUrl());
        response.setStatus(connector.getStatus());
        return response;
    }

    private String getOperator(String operator) {
        return operator == null || operator.isBlank() ? "system" : operator;
    }

    private <T> T runWithTenant(String tenantId, Supplier<T> supplier) {
        String old = SaaSTenantContextHolder.getTenantId();
        SaaSTenantContextHolder.setTenantId(tenantId);
        try {
            return supplier.get();
        }
        finally {
            if (old == null || old.isBlank()) {
                SaaSTenantContextHolder.clear();
            }
            else {
                SaaSTenantContextHolder.setTenantId(old);
            }
        }
    }
}
