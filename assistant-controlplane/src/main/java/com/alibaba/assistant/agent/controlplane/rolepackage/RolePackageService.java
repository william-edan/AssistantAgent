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

import com.alibaba.assistant.agent.controlplane.rolepackage.mapper.RoleKpiMetricMapper;
import com.alibaba.assistant.agent.controlplane.rolepackage.mapper.RolePackageMapper;
import com.alibaba.assistant.agent.controlplane.rolepackage.mapper.RoleProactiveTaskMapper;
import com.alibaba.assistant.agent.controlplane.rolepackage.mapper.RoleScenarioMapper;
import com.alibaba.assistant.agent.controlplane.rolepackage.mapper.RoleToolScopeMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Persistence service for agent-app scoped role packages.
 */
@Service
public class RolePackageService {

    private static final String STATUS_DRAFT = "draft";

    private static final String STATUS_PUBLISHED = "published";

    private static final String STATUS_ARCHIVED = "archived";

    private final RolePackageMapper rolePackageMapper;

    private final RoleScenarioMapper roleScenarioMapper;

    private final RoleProactiveTaskMapper roleProactiveTaskMapper;

    private final RoleToolScopeMapper roleToolScopeMapper;

    private final RoleKpiMetricMapper roleKpiMetricMapper;

    private final ObjectMapper objectMapper;

    public RolePackageService(
            RolePackageMapper rolePackageMapper,
            RoleScenarioMapper roleScenarioMapper,
            RoleProactiveTaskMapper roleProactiveTaskMapper,
            RoleToolScopeMapper roleToolScopeMapper,
            RoleKpiMetricMapper roleKpiMetricMapper) {
        this(
                rolePackageMapper,
                roleScenarioMapper,
                roleProactiveTaskMapper,
                roleToolScopeMapper,
                roleKpiMetricMapper,
                new ObjectMapper());
    }

    @Autowired
    public RolePackageService(
            RolePackageMapper rolePackageMapper,
            RoleScenarioMapper roleScenarioMapper,
            RoleProactiveTaskMapper roleProactiveTaskMapper,
            RoleToolScopeMapper roleToolScopeMapper,
            RoleKpiMetricMapper roleKpiMetricMapper,
            ObjectMapper objectMapper) {
        this.rolePackageMapper = rolePackageMapper;
        this.roleScenarioMapper = roleScenarioMapper;
        this.roleProactiveTaskMapper = roleProactiveTaskMapper;
        this.roleToolScopeMapper = roleToolScopeMapper;
        this.roleKpiMetricMapper = roleKpiMetricMapper;
        this.objectMapper = objectMapper;
    }

    /**
     * Create or update a role package version.
     */
    @Transactional
    public Optional<ResolvedRolePackageManagementView> upsertDraft(
            Long spaceId,
            String agentAppCode,
            RolePackageUpsertCommand command) {
        if (spaceId == null || !StringUtils.hasText(agentAppCode) || command == null || !StringUtils.hasText(command.roleCode())) {
            return Optional.empty();
        }
        validateScenarioReferences(command);

        RolePackage probe = buildLookup(spaceId, agentAppCode, command.roleCode(), command.version());
        Optional<RolePackage> existing = rolePackageMapper.selectLatest(probe);
        RolePackage target = existing.orElseGet(RolePackage::new);
        LocalDateTime now = LocalDateTime.now();
        if (target.getId() == null) {
            target.setSpaceId(spaceId);
            target.setAgentAppCode(normalize(agentAppCode));
            target.setRoleCode(normalize(command.roleCode()));
            target.setCreatedAt(now);
        }
        target.setDisplayName(normalize(command.displayName()));
        target.setPersona(normalize(command.persona()));
        target.setVersion(normalize(command.version()));
        target.setStatus(normalizeStatus(command.status(), existing.map(RolePackage::getStatus).orElse(STATUS_DRAFT)));
        target.setPublishedAt(STATUS_PUBLISHED.equals(target.getStatus()) ? now : null);
        target.setUpdatedAt(now);

        boolean persisted = target.getId() == null
                ? rolePackageMapper.insert(target) > 0
                : rolePackageMapper.updateById(target) > 0;
        if (!persisted) {
            return Optional.empty();
        }

        replaceChildren(target.getId(), command);
        return Optional.of(toResolved(target, command));
    }

    /**
     * Get a role package version, falling back to the latest version.
     */
    public Optional<ResolvedRolePackageManagementView> getRolePackage(
            String roleCode,
            String version,
            Long spaceId,
            String agentAppCode) {
        if (spaceId == null || !StringUtils.hasText(agentAppCode) || !StringUtils.hasText(roleCode)) {
            return Optional.empty();
        }
        return rolePackageMapper.selectLatest(buildLookup(spaceId, agentAppCode, roleCode, version))
                .map(rolePackage -> toResolved(null, null, rolePackage));
    }

    /**
     * List the latest version for each role package under an agent app.
     */
    public List<ResolvedRolePackageManagementView> listRolePackages(Long spaceId, String agentAppCode) {
        if (spaceId == null || !StringUtils.hasText(agentAppCode)) {
            return List.of();
        }
        Map<String, RolePackage> latestByRoleCode = new LinkedHashMap<>();
        for (RolePackage rolePackage : rolePackageMapper.listByAgentApp(spaceId, normalize(agentAppCode))) {
            if (rolePackage == null || !StringUtils.hasText(rolePackage.getRoleCode())) {
                continue;
            }
            latestByRoleCode.putIfAbsent(rolePackage.getRoleCode(), rolePackage);
        }
        return latestByRoleCode.values().stream()
                .map(rolePackage -> toResolved(null, null, rolePackage))
                .toList();
    }

    /**
     * Publish a specific role package version and archive older published versions.
     */
    @Transactional
    public Optional<ResolvedRolePackageManagementView> publish(Long spaceId, String agentAppCode, String roleCode, String version) {
        if (spaceId == null
                || !StringUtils.hasText(agentAppCode)
                || !StringUtils.hasText(roleCode)
                || !StringUtils.hasText(version)) {
            return Optional.empty();
        }

        Optional<RolePackage> targetOptional = rolePackageMapper.selectLatest(buildLookup(spaceId, agentAppCode, roleCode, version));
        if (targetOptional.isEmpty()) {
            return Optional.empty();
        }

        RolePackage target = targetOptional.get();
        LocalDateTime now = LocalDateTime.now();
        for (RolePackage candidate : rolePackageMapper.selectVersions(buildLookup(spaceId, agentAppCode, roleCode, null))) {
            if (candidate == null || candidate.getId() == null || candidate.getId().equals(target.getId())) {
                continue;
            }
            if (!STATUS_PUBLISHED.equals(normalizeStatus(candidate.getStatus(), null))) {
                continue;
            }
            candidate.setStatus(STATUS_ARCHIVED);
            candidate.setPublishedAt(null);
            candidate.setUpdatedAt(now);
            rolePackageMapper.updateById(candidate);
        }

        target.setStatus(STATUS_PUBLISHED);
        target.setPublishedAt(now);
        target.setUpdatedAt(now);
        if (rolePackageMapper.updateById(target) <= 0) {
            return Optional.empty();
        }
        return Optional.of(toResolved(null, null, target));
    }

    private void replaceChildren(Long rolePackageId, RolePackageUpsertCommand command) {
        roleScenarioMapper.deleteByRolePackageId(rolePackageId);
        roleProactiveTaskMapper.deleteByRolePackageId(rolePackageId);
        roleToolScopeMapper.deleteByRolePackageId(rolePackageId);
        roleKpiMetricMapper.deleteByRolePackageId(rolePackageId);

        insertScenarios(rolePackageId, command.scenarios());
        insertToolScopes(rolePackageId, command.toolScopes());
        insertProactiveTasks(rolePackageId, command.proactiveTasks());
        insertKpiMetrics(rolePackageId, command.kpiMetrics());
    }

    private void insertScenarios(Long rolePackageId, List<RolePackageUpsertCommand.RoleScenarioInput> scenarios) {
        for (int index = 0; index < scenarios.size(); index++) {
            RolePackageUpsertCommand.RoleScenarioInput input = scenarios.get(index);
            RoleScenario scenario = new RoleScenario();
            scenario.setRolePackageId(rolePackageId);
            scenario.setScenarioCode(normalize(input.scenarioCode()));
            scenario.setDisplayName(normalize(input.displayName()));
            scenario.setDescription(normalize(input.description()));
            scenario.setRoutingHintsJson(serializeJson(input.routingHints()));
            scenario.setSortOrder(index);
            roleScenarioMapper.insert(scenario);
        }
    }

    private void insertToolScopes(Long rolePackageId, List<RolePackageUpsertCommand.RoleToolScopeInput> toolScopes) {
        for (RolePackageUpsertCommand.RoleToolScopeInput input : toolScopes) {
            RoleToolScope toolScope = new RoleToolScope();
            toolScope.setRolePackageId(rolePackageId);
            toolScope.setScenarioCode(normalize(input.scenarioCode()));
            toolScope.setToolCode(normalize(input.toolCode()));
            toolScope.setScopeMode(normalizeUpper(input.scopeMode()));
            roleToolScopeMapper.insert(toolScope);
        }
    }

    private void insertProactiveTasks(Long rolePackageId, List<RolePackageUpsertCommand.RoleProactiveTaskInput> proactiveTasks) {
        for (RolePackageUpsertCommand.RoleProactiveTaskInput input : proactiveTasks) {
            RoleProactiveTask proactiveTask = new RoleProactiveTask();
            proactiveTask.setRolePackageId(rolePackageId);
            proactiveTask.setTaskCode(normalize(input.taskCode()));
            proactiveTask.setCronExpr(normalize(input.cronExpr()));
            proactiveTask.setArtifactCode(normalize(input.artifactCode()));
            proactiveTask.setScenarioCode(normalize(input.scenarioCode()));
            proactiveTask.setTaskPayloadJson(serializeJson(input.taskPayload()));
            proactiveTask.setStatus(normalizeStatus(input.status(), STATUS_DRAFT));
            roleProactiveTaskMapper.insert(proactiveTask);
        }
    }

    private void insertKpiMetrics(Long rolePackageId, List<RolePackageUpsertCommand.RoleKpiMetricInput> kpiMetrics) {
        for (RolePackageUpsertCommand.RoleKpiMetricInput input : kpiMetrics) {
            RoleKpiMetric metric = new RoleKpiMetric();
            metric.setRolePackageId(rolePackageId);
            metric.setMetricCode(normalize(input.metricCode()));
            metric.setDisplayName(normalize(input.displayName()));
            metric.setTargetValue(normalize(input.targetValue()));
            metric.setMetricDefinitionJson(serializeJson(input.metricDefinition()));
            roleKpiMetricMapper.insert(metric);
        }
    }

    private void validateScenarioReferences(RolePackageUpsertCommand command) {
        Set<String> scenarioCodes = command.scenarios().stream()
                .map(RolePackageUpsertCommand.RoleScenarioInput::scenarioCode)
                .filter(StringUtils::hasText)
                .map(this::normalize)
                .collect(Collectors.toSet());
        for (RolePackageUpsertCommand.RoleToolScopeInput toolScope : command.toolScopes()) {
            requireScenarioReference(toolScope.scenarioCode(), scenarioCodes, "tool_scope");
        }
        for (RolePackageUpsertCommand.RoleProactiveTaskInput proactiveTask : command.proactiveTasks()) {
            requireScenarioReference(proactiveTask.scenarioCode(), scenarioCodes, "proactive_task");
        }
    }

    private void requireScenarioReference(String scenarioCode, Set<String> scenarioCodes, String relation) {
        if (!StringUtils.hasText(scenarioCode)) {
            return;
        }
        String normalizedScenarioCode = normalize(scenarioCode);
        if (!scenarioCodes.contains(normalizedScenarioCode)) {
            throw new IllegalArgumentException("unknown_" + relation + "_scenario:" + normalizedScenarioCode);
        }
    }

    private ResolvedRolePackageManagementView toResolved(RolePackage rolePackage, RolePackageUpsertCommand command) {
        return new ResolvedRolePackageManagementView(
                rolePackage.getId(),
                null,
                null,
                rolePackage.getAgentAppCode(),
                rolePackage.getRoleCode(),
                rolePackage.getDisplayName(),
                rolePackage.getPersona(),
                rolePackage.getVersion(),
                rolePackage.getStatus(),
                command.scenarios().stream()
                        .map(input -> new ResolvedRolePackageManagementView.RoleScenarioView(
                                input.scenarioCode(),
                                input.displayName(),
                                input.description(),
                                input.routingHints() == null ? Map.of() : input.routingHints()))
                        .toList(),
                command.toolScopes().stream()
                        .map(input -> new ResolvedRolePackageManagementView.RoleToolScopeView(
                                input.scenarioCode(),
                                input.toolCode(),
                                normalizeUpper(input.scopeMode())))
                        .toList(),
                command.proactiveTasks().stream()
                        .map(input -> new ResolvedRolePackageManagementView.RoleProactiveTaskView(
                                input.taskCode(),
                                input.cronExpr(),
                                input.artifactCode(),
                                input.scenarioCode(),
                                input.taskPayload() == null ? Map.of() : input.taskPayload(),
                                normalizeUpper(normalizeStatus(input.status(), STATUS_DRAFT))))
                        .toList(),
                command.kpiMetrics().stream()
                        .map(input -> new ResolvedRolePackageManagementView.RoleKpiMetricView(
                                input.metricCode(),
                                input.displayName(),
                                input.targetValue(),
                                input.metricDefinition() == null ? Map.of() : input.metricDefinition()))
                        .toList());
    }
    private ResolvedRolePackageManagementView toResolved(String spaceCode, String environment, RolePackage rolePackage) {
        return new ResolvedRolePackageManagementView(
                rolePackage.getId(),
                spaceCode,
                environment,
                rolePackage.getAgentAppCode(),
                rolePackage.getRoleCode(),
                rolePackage.getDisplayName(),
                rolePackage.getPersona(),
                rolePackage.getVersion(),
                rolePackage.getStatus(),
                roleScenarioMapper.listByRolePackageId(rolePackage.getId()).stream()
                        .map(this::toScenarioView)
                        .toList(),
                roleToolScopeMapper.listByRolePackageId(rolePackage.getId()).stream()
                        .map(this::toToolScopeView)
                        .toList(),
                roleProactiveTaskMapper.listByRolePackageId(rolePackage.getId()).stream()
                        .map(this::toProactiveTaskView)
                        .toList(),
                roleKpiMetricMapper.listByRolePackageId(rolePackage.getId()).stream()
                        .map(this::toKpiMetricView)
                        .toList());
    }

    private ResolvedRolePackageManagementView.RoleScenarioView toScenarioView(RoleScenario scenario) {
        return new ResolvedRolePackageManagementView.RoleScenarioView(
                scenario.getScenarioCode(),
                scenario.getDisplayName(),
                scenario.getDescription(),
                parseMap(scenario.getRoutingHintsJson()));
    }

    private ResolvedRolePackageManagementView.RoleToolScopeView toToolScopeView(RoleToolScope toolScope) {
        return new ResolvedRolePackageManagementView.RoleToolScopeView(
                toolScope.getScenarioCode(),
                toolScope.getToolCode(),
                normalizeUpper(toolScope.getScopeMode()));
    }

    private ResolvedRolePackageManagementView.RoleProactiveTaskView toProactiveTaskView(RoleProactiveTask proactiveTask) {
        return new ResolvedRolePackageManagementView.RoleProactiveTaskView(
                proactiveTask.getTaskCode(),
                proactiveTask.getCronExpr(),
                proactiveTask.getArtifactCode(),
                proactiveTask.getScenarioCode(),
                parseMap(proactiveTask.getTaskPayloadJson()),
                normalizeUpper(proactiveTask.getStatus()));
    }

    private ResolvedRolePackageManagementView.RoleKpiMetricView toKpiMetricView(RoleKpiMetric metric) {
        return new ResolvedRolePackageManagementView.RoleKpiMetricView(
                metric.getMetricCode(),
                metric.getDisplayName(),
                metric.getTargetValue(),
                parseMap(metric.getMetricDefinitionJson()));
    }

    private Map<String, Object> parseMap(String json) {
        if (!StringUtils.hasText(json)) {
            return Map.of();
        }
        try {
            Map<String, Object> parsed = objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {
            });
            return parsed == null ? Map.of() : parsed;
        }
        catch (Exception ignored) {
            return Map.of();
        }
    }

    private String serializeJson(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Map<?, ?> map && map.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        }
        catch (Exception ignored) {
            return null;
        }
    }

    private RolePackage buildLookup(Long spaceId, String agentAppCode, String roleCode, String version) {
        RolePackage lookup = new RolePackage();
        lookup.setSpaceId(spaceId);
        lookup.setAgentAppCode(normalize(agentAppCode));
        lookup.setRoleCode(normalize(roleCode));
        lookup.setVersion(normalize(version));
        return lookup;
    }

    private String normalize(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private String normalizeUpper(String value) {
        return StringUtils.hasText(value) ? value.trim().toUpperCase(Locale.ROOT) : null;
    }

    private String normalizeStatus(String value, String fallback) {
        String candidate = StringUtils.hasText(value) ? value.trim().toLowerCase(Locale.ROOT) : normalize(fallback);
        return StringUtils.hasText(candidate) ? candidate : STATUS_DRAFT;
    }
}




