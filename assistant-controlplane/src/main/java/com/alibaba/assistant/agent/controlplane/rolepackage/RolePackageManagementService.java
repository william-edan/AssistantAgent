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
package com.alibaba.assistant.agent.controlplane.rolepackage;

import com.alibaba.assistant.agent.controlplane.agentapp.AgentApp;
import com.alibaba.assistant.agent.controlplane.agentapp.AgentAppGrantService;
import com.alibaba.assistant.agent.controlplane.agentapp.AgentAppPublicationSourcePolicy;
import com.alibaba.assistant.agent.controlplane.agentapp.AgentAppService;
import com.alibaba.assistant.agent.controlplane.space.PlatformSpace;
import com.alibaba.assistant.agent.controlplane.space.PlatformSpaceService;
import com.alibaba.assistant.agent.controlplane.toolregistry.ToolMetaService;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Optional;

/**
 * Agent-app scoped management facade for role packages.
 */
@Service
public class RolePackageManagementService {

    private static final String DEFAULT_ENVIRONMENT = "prod";

    private static final String ROLE_PACKAGE_PUBLICATION_SOURCE = "tool-meta-catalog";

    private final PlatformSpaceService platformSpaceService;

    private final AgentAppService agentAppService;

    private final AgentAppGrantService agentAppGrantService;

    private final ToolMetaService toolMetaService;

    private final RolePackageService rolePackageService;

    private final RolePackagePublisher rolePackagePublisher;

    public RolePackageManagementService(
            PlatformSpaceService platformSpaceService,
            AgentAppService agentAppService,
            AgentAppGrantService agentAppGrantService,
            ToolMetaService toolMetaService,
            RolePackageService rolePackageService,
            RolePackagePublisher rolePackagePublisher) {
        this.platformSpaceService = platformSpaceService;
        this.agentAppService = agentAppService;
        this.agentAppGrantService = agentAppGrantService;
        this.toolMetaService = toolMetaService;
        this.rolePackageService = rolePackageService;
        this.rolePackagePublisher = rolePackagePublisher;
    }

    /**
     * List role packages under a managed agent app.
     */
    public List<ResolvedRolePackageManagementView> listRolePackages(String spaceCode, String environment, String agentAppCode) {
        Optional<Resolution> resolution = resolve(spaceCode, environment, agentAppCode);
        if (resolution.isEmpty()) {
            return List.of();
        }
        return rolePackageService.listRolePackages(
                        resolution.get().space().getId(),
                        resolution.get().agentApp().getAgentAppCode())
                .stream()
                .map(view -> withContext(resolution.get(), view))
                .toList();
    }

    /**
     * Get a specific role package version.
     */
    public Optional<ResolvedRolePackageManagementView> getRolePackage(
            String spaceCode,
            String environment,
            String agentAppCode,
            String roleCode,
            String version) {
        Optional<Resolution> resolution = resolve(spaceCode, environment, agentAppCode);
        if (resolution.isEmpty()) {
            return Optional.empty();
        }
        return rolePackageService.getRolePackage(
                        roleCode,
                        version,
                        resolution.get().space().getId(),
                        resolution.get().agentApp().getAgentAppCode())
                .map(view -> withContext(resolution.get(), view));
    }

    /**
     * Upsert a draft role package version.
     */
    public Optional<ResolvedRolePackageManagementView> upsertRolePackage(
            String spaceCode,
            String environment,
            String agentAppCode,
            String roleCode,
            RolePackageUpsertCommand command) {
        if (!StringUtils.hasText(roleCode) || command == null) {
            return Optional.empty();
        }
        Optional<Resolution> resolution = resolve(spaceCode, environment, agentAppCode);
        if (resolution.isEmpty()) {
            return Optional.empty();
        }
        return rolePackageService.upsertDraft(
                        resolution.get().space().getId(),
                        resolution.get().agentApp().getAgentAppCode(),
                        command.withRoleCode(roleCode))
                .map(view -> withContext(resolution.get(), view));
    }

    /**
     * Publish a role package version after validating publication scope.
     */
    public Optional<ResolvedRolePackageManagementView> publishRolePackage(
            String spaceCode,
            String environment,
            String agentAppCode,
            String roleCode,
            String version) {
        Optional<Resolution> resolution = resolve(spaceCode, environment, agentAppCode);
        if (resolution.isEmpty()) {
            return Optional.empty();
        }
        ResolvedRolePackageManagementView rolePackage = rolePackageService.getRolePackage(
                        roleCode,
                        version,
                        resolution.get().space().getId(),
                        resolution.get().agentApp().getAgentAppCode())
                .orElseThrow(() -> new IllegalStateException("role_package_not_found"));
        validatePublicationPolicy(resolution.get().agentApp().getId(), rolePackage);
        return rolePackagePublisher.publish(
                        resolution.get().space().getId(),
                        resolution.get().agentApp().getAgentAppCode(),
                        roleCode,
                        version)
                .map(view -> withContext(resolution.get(), view));
    }

    private void validatePublicationPolicy(Long agentAppId, ResolvedRolePackageManagementView rolePackage) {
        AgentAppPublicationSourcePolicy policy = agentAppGrantService.findPublicationSourcePolicy(agentAppId)
                .orElse(new AgentAppPublicationSourcePolicy("MERGE", List.of(), List.of()));
        if (policy.blockedSourceIds().contains(ROLE_PACKAGE_PUBLICATION_SOURCE)) {
            throw new IllegalStateException("role_package_source_blocked");
        }
        if (!policy.allowedSourceIds().isEmpty() && !policy.allowedSourceIds().contains(ROLE_PACKAGE_PUBLICATION_SOURCE)) {
            throw new IllegalStateException("role_package_source_not_allowed");
        }
        for (ResolvedRolePackageManagementView.RoleToolScopeView toolScope : rolePackage.toolScopes()) {
            if (!StringUtils.hasText(toolScope.toolCode())
                    || toolMetaService.findLatestEnabledByToolCode(null, toolScope.toolCode()).isEmpty()) {
                throw new IllegalStateException("role_package_tool_unavailable:" + toolScope.toolCode());
            }
        }
    }

    private Optional<Resolution> resolve(String spaceCode, String environment, String agentAppCode) {
        if (!StringUtils.hasText(spaceCode) || !StringUtils.hasText(agentAppCode)) {
            return Optional.empty();
        }
        String normalizedEnvironment = StringUtils.hasText(environment) ? environment.trim() : DEFAULT_ENVIRONMENT;
        Optional<PlatformSpace> space = platformSpaceService.findActiveByCode(spaceCode.trim(), normalizedEnvironment);
        if (space.isEmpty()) {
            return Optional.empty();
        }
        Optional<AgentApp> agentApp = agentAppService.findActiveByCode(space.get().getId(), agentAppCode.trim());
        if (agentApp.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new Resolution(space.get(), agentApp.get(), normalizedEnvironment));
    }

    private ResolvedRolePackageManagementView withContext(
            Resolution resolution,
            ResolvedRolePackageManagementView rolePackage) {
        return new ResolvedRolePackageManagementView(
                rolePackage.id(),
                resolution.space().getSpaceCode(),
                resolution.environment(),
                rolePackage.agentAppCode(),
                rolePackage.roleCode(),
                rolePackage.displayName(),
                rolePackage.persona(),
                rolePackage.version(),
                rolePackage.status(),
                rolePackage.scenarios(),
                rolePackage.toolScopes(),
                rolePackage.proactiveTasks(),
                rolePackage.kpiMetrics());
    }

    private record Resolution(PlatformSpace space, AgentApp agentApp, String environment) {
    }
}
