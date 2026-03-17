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
package com.alibaba.assistant.agent.api.security;

import com.alibaba.assistant.agent.controlplane.identity.LocalUserGrantService;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * Scoped authorization bridge for migration-mode control-plane APIs.
 */
@Service
@Profile("migration")
public class MigrationControlPlaneAuthorizationService {

    private static final String ROLE_CONTROLPLANE_ADMIN = "assistant_controlplane_admin";

    private static final String ROLE_SPACE_ADMIN = "assistant_space_admin";

    private static final String ROLE_AGENT_APP_ADMIN = "assistant_agent_app_admin";

    private static final String SCOPE_TYPE_SPACE = "space";

    private static final String SCOPE_TYPE_AGENT_APP = "agent_app";

    private final LocalUserGrantService localUserGrantService;

    public MigrationControlPlaneAuthorizationService(LocalUserGrantService localUserGrantService) {
        this.localUserGrantService = localUserGrantService;
    }

    /**
     * Whether the authenticated user can read connector/action/workflow catalog views under a space.
     */
    public boolean canViewSpaceCatalog(
            AuthenticatedUserContext authenticatedUser,
            String spaceCode,
            String environment) {
        Long localUserId = parseLocalUserId(authenticatedUser == null ? null : authenticatedUser.userId());
        String normalizedSpaceCode = normalizeRequired(spaceCode);
        String normalizedEnvironment = normalizeRequired(environment);
        if (localUserId == null || !StringUtils.hasText(normalizedSpaceCode) || !StringUtils.hasText(normalizedEnvironment)) {
            return false;
        }
        return hasGlobalControlPlaneAdmin(localUserId) || hasSpaceAdmin(localUserId, normalizedSpaceCode);
    }

    /**
     * Whether the authenticated user can manage connector/auth/workflow catalog records under a space.
     */
    public boolean canManageSpaceCatalog(
            AuthenticatedUserContext authenticatedUser,
            String spaceCode,
            String environment) {
        return canViewSpaceCatalog(authenticatedUser, spaceCode, environment);
    }

    /**
     * Whether the authenticated user can manage the target agent-app publication policy.
     */
    public boolean canManageAgentAppPublicationPolicy(
            AuthenticatedUserContext authenticatedUser,
            String spaceCode,
            String environment,
            String agentAppCode) {
        Long localUserId = parseLocalUserId(authenticatedUser == null ? null : authenticatedUser.userId());
        String normalizedSpaceCode = normalizeRequired(spaceCode);
        String normalizedEnvironment = normalizeRequired(environment);
        String normalizedAgentAppCode = normalizeRequired(agentAppCode);
        if (localUserId == null
                || !StringUtils.hasText(normalizedSpaceCode)
                || !StringUtils.hasText(normalizedEnvironment)
                || !StringUtils.hasText(normalizedAgentAppCode)) {
            return false;
        }
        if (hasGlobalControlPlaneAdmin(localUserId) || hasSpaceAdmin(localUserId, normalizedSpaceCode)) {
            return true;
        }
        String agentAppScopeCode = normalizedSpaceCode + "/" + normalizedEnvironment + "/" + normalizedAgentAppCode;
        return localUserGrantService.hasGrant(
                localUserId,
                LocalUserGrantService.GRANT_TYPE_PERMISSION,
                AuthenticatedUserAuthorityMapper.PERMISSION_CONTROLPLANE,
                SCOPE_TYPE_AGENT_APP,
                agentAppScopeCode)
                || localUserGrantService.hasGrant(
                        localUserId,
                        LocalUserGrantService.GRANT_TYPE_ROLE,
                        ROLE_AGENT_APP_ADMIN,
                        SCOPE_TYPE_AGENT_APP,
                        agentAppScopeCode);
    }

    /**
     * Whether the authenticated user can manage role-package resources under an agent app.
     */
    public boolean canManageAgentAppRolePackages(
            AuthenticatedUserContext authenticatedUser,
            String spaceCode,
            String environment,
            String agentAppCode) {
        return canManageAgentAppPublicationPolicy(authenticatedUser, spaceCode, environment, agentAppCode);
    }

    /**
     * Whether the authenticated user can manage approval queue operations in the target space.
     */
    public boolean canManageSpaceExecutionApprovals(
            AuthenticatedUserContext authenticatedUser,
            String spaceCode,
            String environment) {
        return canViewSpaceCatalog(authenticatedUser, spaceCode, environment);
    }

    /**
     * Whether the authenticated user can manage local-user control-plane grants in the target space.
     */
    public boolean canManageLocalUserControlPlaneAccessPolicy(
            AuthenticatedUserContext authenticatedUser,
            String spaceCode,
            String environment) {
        return canViewSpaceCatalog(authenticatedUser, spaceCode, environment);
    }

    private boolean hasGlobalControlPlaneAdmin(Long localUserId) {
        return localUserGrantService.hasGrant(
                localUserId,
                LocalUserGrantService.GRANT_TYPE_PERMISSION,
                AuthenticatedUserAuthorityMapper.PERMISSION_CONTROLPLANE)
                || localUserGrantService.hasGrant(
                        localUserId,
                        LocalUserGrantService.GRANT_TYPE_ROLE,
                        ROLE_CONTROLPLANE_ADMIN);
    }

    private boolean hasSpaceAdmin(Long localUserId, String normalizedSpaceCode) {
        return localUserGrantService.hasGrant(
                localUserId,
                LocalUserGrantService.GRANT_TYPE_PERMISSION,
                AuthenticatedUserAuthorityMapper.PERMISSION_CONTROLPLANE,
                SCOPE_TYPE_SPACE,
                normalizedSpaceCode)
                || localUserGrantService.hasGrant(
                        localUserId,
                        LocalUserGrantService.GRANT_TYPE_ROLE,
                        ROLE_SPACE_ADMIN,
                        SCOPE_TYPE_SPACE,
                        normalizedSpaceCode);
    }

    private Long parseLocalUserId(String rawUserId) {
        if (!StringUtils.hasText(rawUserId)) {
            return null;
        }
        try {
            return Long.parseLong(rawUserId.trim());
        }
        catch (NumberFormatException ignored) {
            return null;
        }
    }

    private String normalizeRequired(String value) {
        return StringUtils.hasText(value) ? value.trim() : "";
    }
}
