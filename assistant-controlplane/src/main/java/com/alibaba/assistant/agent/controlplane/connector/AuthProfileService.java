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

import com.alibaba.assistant.agent.controlplane.connector.mapper.AuthProfileMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * 认证配置查询服务。
 *
 * @author Assistant Agent Team
 * @since 1.0.0
 */
@Service
public class AuthProfileService extends ServiceImpl<AuthProfileMapper, AuthProfile> {

    private static final String STATUS_ACTIVE = "active";

    /**
     * 查询某个连接器下处于启用状态的认证配置。
     *
     * @param connectorId 连接器主键
     * @return 按主键升序排列的启用认证配置
     */
    public List<AuthProfile> listActiveByConnector(Long connectorId) {
        if (connectorId == null) {
            return Collections.emptyList();
        }

        LambdaQueryWrapper<AuthProfile> query = new LambdaQueryWrapper<>();
        query.eq(AuthProfile::getConnectorId, connectorId);
        query.eq(AuthProfile::getStatus, STATUS_ACTIVE);
        query.orderByAsc(AuthProfile::getId);
        return list(query);
    }

    /**
     * 按编码查询某个连接器下最新的启用认证配置。
     */
    public Optional<AuthProfile> findLatestActiveByCode(Long connectorId, String authProfileCode) {
        if (connectorId == null || !StringUtils.hasText(authProfileCode)) {
            return Optional.empty();
        }

        LambdaQueryWrapper<AuthProfile> query = new LambdaQueryWrapper<>();
        query.eq(AuthProfile::getConnectorId, connectorId);
        query.eq(AuthProfile::getAuthProfileCode, authProfileCode.trim());
        query.eq(AuthProfile::getStatus, STATUS_ACTIVE);
        query.orderByDesc(AuthProfile::getId);
        return Optional.ofNullable(getOne(query, false));
    }
}
