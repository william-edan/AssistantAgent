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
import com.alibaba.assistant.agent.start.saas.controller.dto.CreateUserBindingRequest;
import com.alibaba.assistant.agent.start.saas.controller.dto.UserBindingResponse;
import com.alibaba.assistant.agent.start.saas.infrastructure.mybatis.dataobject.UserBindingDO;
import com.alibaba.assistant.agent.start.saas.infrastructure.mybatis.mapper.UserBindingMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Application service for user binding management.
 *
 * @author Assistant Agent Team
 * @since 1.0.0
 */
@Service
public class UserBindingAppService {

    private static final Logger log = LoggerFactory.getLogger(UserBindingAppService.class);

    private final UserBindingMapper userBindingMapper;

    public UserBindingAppService(UserBindingMapper userBindingMapper) {
        this.userBindingMapper = userBindingMapper;
    }

    /**
     * Create user binding.
     *
     * @param tenantId tenant id
     * @param request request
     * @return response
     */
    @Transactional(rollbackFor = Exception.class)
    public UserBindingResponse createBinding(String tenantId, CreateUserBindingRequest request) {
        return runWithTenant(tenantId, () -> {
            UserBindingDO existed = userBindingMapper.selectOne(Wrappers.lambdaQuery(UserBindingDO.class)
                    .eq(UserBindingDO::getPlatformUserId, request.getPlatformUserId())
                    .eq(UserBindingDO::getSystemCode, request.getSystemCode()));
            if (existed != null) {
                throw new IllegalArgumentException("user binding already exists");
            }

            UserBindingDO binding = new UserBindingDO();
            binding.setTenantId(tenantId);
            binding.setPlatformUserId(request.getPlatformUserId());
            binding.setSystemCode(request.getSystemCode());
            binding.setExternalUserId(request.getExternalUserId());
            binding.setBindingMode(request.getBindingMode());
            binding.setStatus(request.getStatus() == null || request.getStatus().isBlank() ? "ACTIVE" : request.getStatus());
            binding.setCreatedBy(getOperator(request.getOperator()));
            binding.setUpdatedBy(getOperator(request.getOperator()));
            userBindingMapper.insert(binding);

            log.info("UserBindingAppService#createBinding - reason=user binding created, tenantId={}, platformUserId={}, systemCode={}",
                    tenantId, request.getPlatformUserId(), request.getSystemCode());
            return toResponse(binding);
        });
    }

    /**
     * List bindings by platform user id.
     *
     * @param tenantId tenant id
     * @param platformUserId platform user id
     * @return list
     */
    public List<UserBindingResponse> listBindings(String tenantId, String platformUserId) {
        return runWithTenant(tenantId, () -> userBindingMapper.selectList(Wrappers.lambdaQuery(UserBindingDO.class)
                        .eq(UserBindingDO::getPlatformUserId, platformUserId)
                        .orderByDesc(UserBindingDO::getId))
                .stream()
                .map(this::toResponse)
                .collect(Collectors.toList()));
    }

    /**
     * Delete binding by id (logical delete).
     *
     * @param tenantId tenant id
     * @param id binding id
     */
    @Transactional(rollbackFor = Exception.class)
    public void deleteBinding(String tenantId, Long id) {
        runWithTenant(tenantId, () -> {
            int affected = userBindingMapper.deleteById(id);
            if (affected == 0) {
                throw new IllegalArgumentException("user binding not found");
            }
            log.info("UserBindingAppService#deleteBinding - reason=user binding deleted, tenantId={}, id={}",
                    tenantId, id);
            return null;
        });
    }

    private UserBindingResponse toResponse(UserBindingDO binding) {
        UserBindingResponse response = new UserBindingResponse();
        response.setId(binding.getId());
        response.setPlatformUserId(binding.getPlatformUserId());
        response.setSystemCode(binding.getSystemCode());
        response.setExternalUserId(binding.getExternalUserId());
        response.setBindingMode(binding.getBindingMode());
        response.setStatus(binding.getStatus());
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
