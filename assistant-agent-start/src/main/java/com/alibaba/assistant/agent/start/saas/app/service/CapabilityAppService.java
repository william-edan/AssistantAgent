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

import com.alibaba.assistant.agent.start.saas.app.model.ResolvedCapabilityInfo;
import com.alibaba.assistant.agent.start.saas.context.SaaSTenantContextHolder;
import com.alibaba.assistant.agent.start.saas.controller.dto.CapabilityDetailResponse;
import com.alibaba.assistant.agent.start.saas.controller.dto.CreateCapabilityRequest;
import com.alibaba.assistant.agent.start.saas.controller.dto.CreateCapabilityVersionRequest;
import com.alibaba.assistant.agent.start.saas.controller.dto.PublishCapabilityRequest;
import com.alibaba.assistant.agent.start.saas.domain.model.CapabilityStatus;
import com.alibaba.assistant.agent.start.saas.infrastructure.mybatis.dataobject.CapabilityDO;
import com.alibaba.assistant.agent.start.saas.infrastructure.mybatis.dataobject.CapabilityVersionDO;
import com.alibaba.assistant.agent.start.saas.infrastructure.mybatis.dataobject.ConnectorApiDO;
import com.alibaba.assistant.agent.start.saas.infrastructure.mybatis.mapper.CapabilityMapper;
import com.alibaba.assistant.agent.start.saas.infrastructure.mybatis.mapper.CapabilityVersionMapper;
import com.alibaba.assistant.agent.start.saas.infrastructure.mybatis.mapper.ConnectorApiMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Application service for capability lifecycle.
 *
 * @author Assistant Agent Team
 * @since 1.0.0
 */
@Service
public class CapabilityAppService {

    private static final Logger log = LoggerFactory.getLogger(CapabilityAppService.class);

    private final CapabilityMapper capabilityMapper;

    private final CapabilityVersionMapper capabilityVersionMapper;

    private final ConnectorApiMapper connectorApiMapper;

    private final ObjectMapper objectMapper;

    public CapabilityAppService(CapabilityMapper capabilityMapper, CapabilityVersionMapper capabilityVersionMapper,
            ConnectorApiMapper connectorApiMapper, ObjectMapper objectMapper) {
        this.capabilityMapper = capabilityMapper;
        this.capabilityVersionMapper = capabilityVersionMapper;
        this.connectorApiMapper = connectorApiMapper;
        this.objectMapper = objectMapper;
    }

    /**
     * Create capability draft.
     *
     * @param tenantId tenant id
     * @param request request
     * @return detail
     */
    @Transactional(rollbackFor = Exception.class)
    public CapabilityDetailResponse createCapability(String tenantId, CreateCapabilityRequest request) {
        return runWithTenant(tenantId, () -> {
            CapabilityDO existed = capabilityMapper.selectOne(Wrappers.lambdaQuery(CapabilityDO.class)
                    .eq(CapabilityDO::getCapabilityId, request.getCapabilityId()));
            if (existed != null) {
                throw new IllegalArgumentException("capability already exists");
            }

            CapabilityDO capability = new CapabilityDO();
            capability.setTenantId(tenantId);
            capability.setCapabilityId(request.getCapabilityId());
            capability.setDisplayName(request.getDisplayName());
            capability.setDomainCode(request.getDomainCode());
            capability.setLatestVersion(0);
            capability.setStatus(CapabilityStatus.DRAFT.name());
            capability.setCreatedBy(getOperator(request.getOperator()));
            capability.setUpdatedBy(getOperator(request.getOperator()));
            capabilityMapper.insert(capability);

            log.info("CapabilityAppService#createCapability - reason=capability created, tenantId={}, capabilityId={}",
                    tenantId, request.getCapabilityId());
            return toResponse(capability);
        });
    }

    /**
     * Create capability version draft.
     *
     * @param tenantId tenant id
     * @param capabilityId capability id
     * @param request request
     * @return detail
     */
    @Transactional(rollbackFor = Exception.class)
    public CapabilityDetailResponse createVersion(String tenantId, String capabilityId, CreateCapabilityVersionRequest request) {
        return runWithTenant(tenantId, () -> {
            CapabilityDO capability = requireCapability(capabilityId);
            int nextVersion = capability.getLatestVersion() == null ? 1 : capability.getLatestVersion() + 1;
            List<String> apiCodes = extractApiCodesFromRouteConfig(request.getRouteConfigJson());
            verifyConnectorApisReady(request.getConnectorId(), apiCodes);

            CapabilityVersionDO version = new CapabilityVersionDO();
            version.setTenantId(tenantId);
            version.setCapabilityId(capabilityId);
            version.setVersionNo(nextVersion);
            version.setConnectorId(request.getConnectorId());
            version.setInputSchemaJson(request.getInputSchemaJson());
            version.setOutputSchemaJson(request.getOutputSchemaJson());
            version.setSlotSchemaJson(request.getSlotSchemaJson());
            version.setToolBindingJson(defaultToolBinding(request.getToolBindingJson()));
            version.setRouteConfigJson(request.getRouteConfigJson());
            version.setExecutionMode(request.getExecutionMode());
            version.setStatus(CapabilityStatus.DRAFT.name());
            version.setCreatedBy(getOperator(request.getOperator()));
            version.setUpdatedBy(getOperator(request.getOperator()));
            capabilityVersionMapper.insert(version);

            capability.setLatestVersion(nextVersion);
            capability.setUpdatedBy(getOperator(request.getOperator()));
            capabilityMapper.updateById(capability);

            log.info("CapabilityAppService#createVersion - reason=capability version created, tenantId={}, capabilityId={}, version={}",
                    tenantId, capabilityId, nextVersion);
            return toResponse(capability);
        });
    }

    /**
     * Publish target capability version.
     *
     * @param tenantId tenant id
     * @param capabilityId capability id
     * @param request request
     * @return detail
     */
    @Transactional(rollbackFor = Exception.class)
    public CapabilityDetailResponse publish(String tenantId, String capabilityId, PublishCapabilityRequest request) {
        return runWithTenant(tenantId, () -> {
            CapabilityDO capability = requireCapability(capabilityId);
            CapabilityVersionDO version = capabilityVersionMapper.selectOne(Wrappers.lambdaQuery(CapabilityVersionDO.class)
                    .eq(CapabilityVersionDO::getCapabilityId, capabilityId)
                    .eq(CapabilityVersionDO::getVersionNo, request.getVersionNo()));
            if (version == null) {
                throw new IllegalArgumentException("capability version not found");
            }

            version.setStatus(CapabilityStatus.PUBLISHED.name());
            version.setUpdatedBy(getOperator(request.getOperator()));
            capabilityVersionMapper.updateById(version);

            capability.setStatus(CapabilityStatus.PUBLISHED.name());
            capability.setLatestVersion(request.getVersionNo());
            capability.setUpdatedBy(getOperator(request.getOperator()));
            capabilityMapper.updateById(capability);

            log.info("CapabilityAppService#publish - reason=capability version published, tenantId={}, capabilityId={}, version={}",
                    tenantId, capabilityId, request.getVersionNo());
            return toResponse(capability);
        });
    }

    /**
     * Query capability detail.
     *
     * @param tenantId tenant id
     * @param capabilityId capability id
     * @return detail
     */
    public CapabilityDetailResponse getDetail(String tenantId, String capabilityId) {
        return runWithTenant(tenantId, () -> toResponse(requireCapability(capabilityId)));
    }

    /**
     * Resolve published capability version for guarded execution.
     *
     * @param tenantId tenant id
     * @param capabilityId capability id
     * @param requestedVersion requested version, nullable
     * @return resolved info when found and published
     */
    public Optional<ResolvedCapabilityInfo> resolvePublishedVersion(
            String tenantId, String capabilityId, Integer requestedVersion) {
        return runWithTenant(tenantId, () -> {
            CapabilityDO capability = capabilityMapper.selectOne(Wrappers.lambdaQuery(CapabilityDO.class)
                    .eq(CapabilityDO::getCapabilityId, capabilityId));
            if (capability == null || !CapabilityStatus.PUBLISHED.name().equals(capability.getStatus())) {
                return Optional.empty();
            }

            int versionNo = requestedVersion == null ? capability.getLatestVersion() : requestedVersion;
            CapabilityVersionDO version = capabilityVersionMapper.selectOne(Wrappers.lambdaQuery(CapabilityVersionDO.class)
                    .eq(CapabilityVersionDO::getCapabilityId, capabilityId)
                    .eq(CapabilityVersionDO::getVersionNo, versionNo)
                    .eq(CapabilityVersionDO::getStatus, CapabilityStatus.PUBLISHED.name()));
            if (version == null) {
                return Optional.empty();
            }

            ResolvedCapabilityInfo info = new ResolvedCapabilityInfo();
            info.setCapabilityId(capabilityId);
            info.setVersionNo(version.getVersionNo());
            info.setConnectorId(version.getConnectorId());
            info.setInputSchemaJson(version.getInputSchemaJson());
            info.setOutputSchemaJson(version.getOutputSchemaJson());
            info.setSlotSchemaJson(version.getSlotSchemaJson());
            info.setToolBindingJson(version.getToolBindingJson());
            info.setRouteConfigJson(version.getRouteConfigJson());
            info.setExecutionMode(version.getExecutionMode());
            return Optional.of(info);
        });
    }

    private CapabilityDO requireCapability(String capabilityId) {
        CapabilityDO capability = capabilityMapper.selectOne(Wrappers.lambdaQuery(CapabilityDO.class)
                .eq(CapabilityDO::getCapabilityId, capabilityId));
        if (capability == null) {
            throw new IllegalArgumentException("capability not found");
        }
        return capability;
    }

    private CapabilityDetailResponse toResponse(CapabilityDO capability) {
        CapabilityDetailResponse response = new CapabilityDetailResponse();
        response.setCapabilityId(capability.getCapabilityId());
        response.setDisplayName(capability.getDisplayName());
        response.setDomainCode(capability.getDomainCode());
        response.setLatestVersion(capability.getLatestVersion());
        response.setStatus(capability.getStatus());
        return response;
    }

    private String getOperator(String operator) {
        return operator == null || operator.isBlank() ? "system" : operator;
    }

    private void verifyConnectorApisReady(Long connectorId, List<String> apiCodes) {
        for (String apiCode : apiCodes) {
            ConnectorApiDO connectorApi = connectorApiMapper.selectOne(Wrappers.lambdaQuery(ConnectorApiDO.class)
                    .eq(ConnectorApiDO::getConnectorId, connectorId)
                    .eq(ConnectorApiDO::getApiCode, apiCode)
                    .eq(ConnectorApiDO::getStatus, "ACTIVE"));
            if (connectorApi == null) {
                throw new IllegalArgumentException("route step api is not registered: " + apiCode);
            }
        }
    }

    private List<String> extractApiCodesFromRouteConfig(String routeConfigJson) {
        try {
            JsonNode routeNode = objectMapper.readTree(routeConfigJson);
            JsonNode stepsNode = routeNode.get("steps");
            JsonNode nodesNode = routeNode.get("nodes");

            JsonNode routeItems = null;
            if (stepsNode != null && stepsNode.isArray() && !stepsNode.isEmpty()) {
                routeItems = stepsNode;
            }
            else if (nodesNode != null && nodesNode.isArray() && !nodesNode.isEmpty()) {
                routeItems = nodesNode;
            }
            if (routeItems == null) {
                throw new IllegalArgumentException("routeConfigJson.steps or routeConfigJson.nodes is required");
            }

            Set<String> deduplicated = new HashSet<>();
            List<String> apiCodes = new ArrayList<>();
            for (JsonNode stepNode : routeItems) {
                JsonNode apiCodeNode = stepNode.get("apiCode");
                if (apiCodeNode == null || apiCodeNode.asText().isBlank()) {
                    throw new IllegalArgumentException("route step apiCode is required");
                }
                String apiCode = apiCodeNode.asText();
                if (deduplicated.add(apiCode)) {
                    apiCodes.add(apiCode);
                }
            }
            return apiCodes;
        }
        catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("routeConfigJson must be valid json");
        }
    }

    private String defaultToolBinding(String toolBindingJson) {
        return (toolBindingJson == null || toolBindingJson.isBlank()) ? "{}" : toolBindingJson;
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
