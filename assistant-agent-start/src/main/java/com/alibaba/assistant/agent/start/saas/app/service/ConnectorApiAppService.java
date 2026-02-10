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
import com.alibaba.assistant.agent.start.saas.controller.dto.ConnectorApiResponse;
import com.alibaba.assistant.agent.start.saas.controller.dto.CreateConnectorApiRequest;
import com.alibaba.assistant.agent.start.saas.infrastructure.mybatis.dataobject.ConnectorApiDO;
import com.alibaba.assistant.agent.start.saas.infrastructure.mybatis.dataobject.ConnectorDO;
import com.alibaba.assistant.agent.start.saas.infrastructure.mybatis.mapper.ConnectorApiMapper;
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
 * Application service for connector API registry.
 *
 * @author Assistant Agent Team
 * @since 1.0.0
 */
@Service
public class ConnectorApiAppService {

    private static final Logger log = LoggerFactory.getLogger(ConnectorApiAppService.class);

    private final ConnectorMapper connectorMapper;

    private final ConnectorApiMapper connectorApiMapper;

    public ConnectorApiAppService(ConnectorMapper connectorMapper, ConnectorApiMapper connectorApiMapper) {
        this.connectorMapper = connectorMapper;
        this.connectorApiMapper = connectorApiMapper;
    }

    /**
     * Create connector API registration.
     *
     * @param tenantId tenant id
     * @param connectorId connector id
     * @param request request
     * @return response
     */
    @Transactional(rollbackFor = Exception.class)
    public ConnectorApiResponse createConnectorApi(String tenantId, Long connectorId, CreateConnectorApiRequest request) {
        return runWithTenant(tenantId, () -> {
            ConnectorDO connector = connectorMapper.selectById(connectorId);
            if (connector == null) {
                throw new IllegalArgumentException("connector not found");
            }

            ConnectorApiDO existed = connectorApiMapper.selectOne(Wrappers.lambdaQuery(ConnectorApiDO.class)
                    .eq(ConnectorApiDO::getConnectorId, connectorId)
                    .eq(ConnectorApiDO::getApiCode, request.getApiCode()));
            if (existed != null) {
                throw new IllegalArgumentException("connector api already exists");
            }

            ConnectorApiDO api = new ConnectorApiDO();
            api.setTenantId(tenantId);
            api.setConnectorId(connectorId);
            api.setApiCode(request.getApiCode());
            api.setDisplayName(request.getDisplayName());
            api.setHttpMethod(request.getHttpMethod().toUpperCase());
            api.setPathTemplate(request.getPathTemplate());
            api.setRequestSchemaJson(request.getRequestSchemaJson());
            api.setResponseSchemaJson(request.getResponseSchemaJson());
            api.setStatus(getStatus(request.getStatus()));
            api.setCreatedBy(getOperator(request.getOperator()));
            api.setUpdatedBy(getOperator(request.getOperator()));
            connectorApiMapper.insert(api);

            log.info("ConnectorApiAppService#createConnectorApi - reason=connector api created, tenantId={}, connectorId={}, apiCode={}",
                    tenantId, connectorId, request.getApiCode());
            return toResponse(api);
        });
    }

    /**
     * List APIs under connector.
     *
     * @param tenantId tenant id
     * @param connectorId connector id
     * @return api list
     */
    public List<ConnectorApiResponse> listConnectorApis(String tenantId, Long connectorId) {
        return runWithTenant(tenantId, () -> {
            ConnectorDO connector = connectorMapper.selectById(connectorId);
            if (connector == null) {
                throw new IllegalArgumentException("connector not found");
            }

            return connectorApiMapper.selectList(Wrappers.lambdaQuery(ConnectorApiDO.class)
                            .eq(ConnectorApiDO::getConnectorId, connectorId)
                            .orderByDesc(ConnectorApiDO::getId))
                    .stream()
                    .map(this::toResponse)
                    .collect(Collectors.toList());
        });
    }

    private ConnectorApiResponse toResponse(ConnectorApiDO api) {
        ConnectorApiResponse response = new ConnectorApiResponse();
        response.setId(api.getId());
        response.setConnectorId(api.getConnectorId());
        response.setApiCode(api.getApiCode());
        response.setDisplayName(api.getDisplayName());
        response.setHttpMethod(api.getHttpMethod());
        response.setPathTemplate(api.getPathTemplate());
        response.setRequestSchemaJson(api.getRequestSchemaJson());
        response.setResponseSchemaJson(api.getResponseSchemaJson());
        response.setStatus(api.getStatus());
        return response;
    }

    private String getOperator(String operator) {
        return operator == null || operator.isBlank() ? "system" : operator;
    }

    private String getStatus(String status) {
        return status == null || status.isBlank() ? "ACTIVE" : status;
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
