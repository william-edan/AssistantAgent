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
package com.alibaba.assistant.agent.controlplane.identity;

import com.alibaba.assistant.agent.controlplane.agentapp.AgentAppService;
import com.alibaba.assistant.agent.controlplane.identity.mapper.LocalUserAccountMapper;
import com.alibaba.assistant.agent.controlplane.space.PlatformSpace;
import com.alibaba.assistant.agent.controlplane.space.PlatformSpaceService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 迁移模式下本地用户控制面权限策略管理服务。
 */
@Service
public class LocalUserControlPlaneAccessPolicyService {

    private static final String DEFAULT_ENVIRONMENT = "prod";

    private static final String STATUS_ACTIVE = "active";

    private static final String ROLE_SPACE_ADMIN = "assistant_space_admin";

    private static final String ROLE_AGENT_APP_ADMIN = "assistant_agent_app_admin";

    private static final String SCOPE_TYPE_SPACE = "space";

    private static final String SCOPE_TYPE_AGENT_APP = "agent_app";

    private final PlatformSpaceService platformSpaceService;

    private final AgentAppService agentAppService;

    private final LocalUserGrantService localUserGrantService;

    private final LocalUserAccountMapper localUserAccountMapper;

    public LocalUserControlPlaneAccessPolicyService(
            PlatformSpaceService platformSpaceService,
            AgentAppService agentAppService,
            LocalUserGrantService localUserGrantService,
            LocalUserAccountMapper localUserAccountMapper) {
        this.platformSpaceService = platformSpaceService;
        this.agentAppService = agentAppService;
        this.localUserGrantService = localUserGrantService;
        this.localUserAccountMapper = localUserAccountMapper;
    }

    /**
     * 查询指定空间下本地用户的控制面权限策略。
     *
     * @param spaceCode 空间编码
     * @param environment 环境，默认 prod
     * @param localUserId 本地迁移用户主键
     * @return 当空间和用户都存在时返回解析后的策略视图
     */
    public Optional<ResolvedLocalUserControlPlaneAccessPolicy> getPolicy(
            String spaceCode,
            String environment,
            Long localUserId) {
        return resolveTarget(spaceCode, environment, localUserId)
                .map(target -> new ResolvedLocalUserControlPlaneAccessPolicy(
                        target.user().getId(),
                        target.user().getUsername(),
                        target.user().getDisplayName(),
                        target.space().getId(),
                        target.space().getSpaceCode(),
                        target.space().getEnvironment(),
                        new LocalUserControlPlaneAccessPolicy(
                                localUserGrantService.hasGrant(
                                        target.user().getId(),
                                        LocalUserGrantService.GRANT_TYPE_ROLE,
                                        ROLE_SPACE_ADMIN,
                                        SCOPE_TYPE_SPACE,
                                        target.space().getSpaceCode()),
                                listAgentAppAdminCodes(target.user().getId(), target.space().getSpaceCode(), target.space().getEnvironment()))));
    }

    /**
     * 替换指定空间下本地用户的控制面权限策略。
     *
     * @param spaceCode 空间编码
     * @param environment 环境，默认 prod
     * @param localUserId 本地迁移用户主键
     * @param policy 新的权限策略
     * @return 当目标存在且替换成功时返回最新策略视图
     */
    public Optional<ResolvedLocalUserControlPlaneAccessPolicy> replacePolicy(
            String spaceCode,
            String environment,
            Long localUserId,
            LocalUserControlPlaneAccessPolicy policy) {
        if (policy == null) {
            return Optional.empty();
        }
        return resolveTarget(spaceCode, environment, localUserId)
                .flatMap(target -> replaceResolvedPolicy(target, policy));
    }

    private Optional<ResolvedLocalUserControlPlaneAccessPolicy> replaceResolvedPolicy(
            ResolvedTarget target,
            LocalUserControlPlaneAccessPolicy policy) {
        for (String agentAppCode : policy.agentAppAdminCodes()) {
            if (agentAppService.findActiveByCode(target.space().getId(), agentAppCode).isEmpty()) {
                return Optional.empty();
            }
        }

        localUserGrantService.remove(spaceAdminGrantQuery(target.user().getId(), target.space().getSpaceCode()));
        localUserGrantService.remove(agentAppGrantQuery(target.user().getId(), target.space().getSpaceCode(), target.space().getEnvironment()));

        List<LocalUserGrant> newGrants = new ArrayList<>();
        if (policy.spaceAdmin()) {
            newGrants.add(buildGrant(
                    target.user().getId(),
                    ROLE_SPACE_ADMIN,
                    SCOPE_TYPE_SPACE,
                    target.space().getSpaceCode()));
        }
        for (String agentAppCode : policy.agentAppAdminCodes()) {
            newGrants.add(buildGrant(
                    target.user().getId(),
                    ROLE_AGENT_APP_ADMIN,
                    SCOPE_TYPE_AGENT_APP,
                    buildAgentAppScope(target.space().getSpaceCode(), target.space().getEnvironment(), agentAppCode)));
        }
        if (!newGrants.isEmpty()) {
            localUserGrantService.saveBatch(newGrants);
        }
        return Optional.of(new ResolvedLocalUserControlPlaneAccessPolicy(
                target.user().getId(),
                target.user().getUsername(),
                target.user().getDisplayName(),
                target.space().getId(),
                target.space().getSpaceCode(),
                target.space().getEnvironment(),
                policy));
    }

    private Optional<ResolvedTarget> resolveTarget(String spaceCode, String environment, Long localUserId) {
        if (!StringUtils.hasText(spaceCode) || localUserId == null) {
            return Optional.empty();
        }
        String normalizedEnvironment = normalizeEnvironment(environment);
        Optional<PlatformSpace> space = platformSpaceService.findActiveByCode(spaceCode.trim(), normalizedEnvironment);
        if (space.isEmpty()) {
            return Optional.empty();
        }
        LocalUserAccount user = localUserAccountMapper.selectById(localUserId);
        if (user == null || !STATUS_ACTIVE.equalsIgnoreCase(user.getStatus())) {
            return Optional.empty();
        }
        if (!StringUtils.hasText(user.getDisplayName())) {
            user.setDisplayName(user.getUsername());
        }
        return Optional.of(new ResolvedTarget(user, space.get()));
    }

    private List<String> listAgentAppAdminCodes(Long localUserId, String spaceCode, String environment) {
        String scopePrefix = buildAgentAppScope(spaceCode, environment, "");
        LambdaQueryWrapper<LocalUserGrant> query = new LambdaQueryWrapper<>();
        query.eq(LocalUserGrant::getLocalUserId, localUserId);
        query.eq(LocalUserGrant::getGrantType, LocalUserGrantService.GRANT_TYPE_ROLE);
        query.eq(LocalUserGrant::getGrantCode, ROLE_AGENT_APP_ADMIN);
        query.eq(LocalUserGrant::getScopeType, SCOPE_TYPE_AGENT_APP);
        query.eq(LocalUserGrant::getStatus, STATUS_ACTIVE);
        query.orderByAsc(LocalUserGrant::getId);
        return localUserGrantService.list(query).stream()
                .map(LocalUserGrant::getScopeCode)
                .filter(StringUtils::hasText)
                .filter(scopeCode -> scopeCode.startsWith(scopePrefix))
                .map(scopeCode -> scopeCode.substring(scopePrefix.length()))
                .filter(StringUtils::hasText)
                .distinct()
                .toList();
    }

    private LambdaQueryWrapper<LocalUserGrant> spaceAdminGrantQuery(Long localUserId, String spaceCode) {
        LambdaQueryWrapper<LocalUserGrant> query = new LambdaQueryWrapper<>();
        query.eq(LocalUserGrant::getLocalUserId, localUserId);
        query.eq(LocalUserGrant::getGrantType, LocalUserGrantService.GRANT_TYPE_ROLE);
        query.eq(LocalUserGrant::getGrantCode, ROLE_SPACE_ADMIN);
        query.eq(LocalUserGrant::getScopeType, SCOPE_TYPE_SPACE);
        query.eq(LocalUserGrant::getScopeCode, spaceCode);
        return query;
    }

    private LambdaQueryWrapper<LocalUserGrant> agentAppGrantQuery(Long localUserId, String spaceCode, String environment) {
        LambdaQueryWrapper<LocalUserGrant> query = new LambdaQueryWrapper<>();
        query.eq(LocalUserGrant::getLocalUserId, localUserId);
        query.eq(LocalUserGrant::getGrantType, LocalUserGrantService.GRANT_TYPE_ROLE);
        query.eq(LocalUserGrant::getGrantCode, ROLE_AGENT_APP_ADMIN);
        query.eq(LocalUserGrant::getScopeType, SCOPE_TYPE_AGENT_APP);
        query.likeRight(LocalUserGrant::getScopeCode, buildAgentAppScope(spaceCode, environment, ""));
        return query;
    }

    private LocalUserGrant buildGrant(Long localUserId, String roleCode, String scopeType, String scopeCode) {
        LocalUserGrant grant = new LocalUserGrant();
        grant.setLocalUserId(localUserId);
        grant.setGrantType(LocalUserGrantService.GRANT_TYPE_ROLE);
        grant.setGrantCode(roleCode);
        grant.setScopeType(scopeType);
        grant.setScopeCode(scopeCode);
        grant.setStatus(STATUS_ACTIVE);
        return grant;
    }

    private String buildAgentAppScope(String spaceCode, String environment, String agentAppCode) {
        String normalizedAgentAppCode = StringUtils.hasText(agentAppCode) ? agentAppCode.trim() : "";
        return spaceCode + "/" + environment + "/" + normalizedAgentAppCode;
    }

    private String normalizeEnvironment(String requestedEnvironment) {
        return StringUtils.hasText(requestedEnvironment) ? requestedEnvironment.trim() : DEFAULT_ENVIRONMENT;
    }

    private record ResolvedTarget(LocalUserAccount user, PlatformSpace space) {
    }
}
