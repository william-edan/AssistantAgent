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
package com.alibaba.assistant.agent.controlplane.connector;

import com.alibaba.assistant.agent.controlplane.connector.mapper.ConnectorMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Optional;

/**
 * Service for connector lookup operations.
 *
 * @author Assistant Agent Team
 * @since 1.0.0
 */
@Service
public class ConnectorService extends ServiceImpl<ConnectorMapper, Connector> {

    private static final String STATUS_ACTIVE = "active";

    private static final String DEFAULT_ENVIRONMENT = "prod";

    public Optional<Connector> findLatestActiveByCode(Long spaceId, String connectorCode) {
        if (spaceId == null || !StringUtils.hasText(connectorCode)) {
            return Optional.empty();
        }

        LambdaQueryWrapper<Connector> query = new LambdaQueryWrapper<>();
        query.eq(Connector::getSpaceId, spaceId);
        query.eq(Connector::getConnectorCode, connectorCode);
        query.eq(Connector::getStatus, STATUS_ACTIVE);
        query.orderByDesc(Connector::getVersion);
        query.orderByDesc(Connector::getId);
        return Optional.ofNullable(getOne(query, false));
    }

    public Optional<Connector> findLatestActiveByCodeAndEnvironment(Long spaceId, String environment, String connectorCode) {
        if (spaceId == null || !StringUtils.hasText(connectorCode)) {
            return Optional.empty();
        }

        LambdaQueryWrapper<Connector> query = new LambdaQueryWrapper<>();
        query.eq(Connector::getSpaceId, spaceId);
        query.eq(Connector::getEnvironment, normalizeEnvironment(environment));
        query.eq(Connector::getConnectorCode, connectorCode.trim());
        query.eq(Connector::getStatus, STATUS_ACTIVE);
        query.orderByDesc(Connector::getVersion);
        query.orderByDesc(Connector::getId);
        return Optional.ofNullable(getOne(query, false));
    }

    public List<Connector> listLatestActiveBySpace(Long spaceId, String environment) {
        if (spaceId == null) {
            return List.of();
        }
        LambdaQueryWrapper<Connector> query = new LambdaQueryWrapper<>();
        query.eq(Connector::getSpaceId, spaceId);
        query.eq(Connector::getEnvironment, normalizeEnvironment(environment));
        query.eq(Connector::getStatus, STATUS_ACTIVE);
        query.orderByAsc(Connector::getConnectorCode);
        query.orderByDesc(Connector::getVersion);
        query.orderByDesc(Connector::getId);
        return list(query);
    }

    private String normalizeEnvironment(String environment) {
        return StringUtils.hasText(environment) ? environment.trim() : DEFAULT_ENVIRONMENT;
    }
}
