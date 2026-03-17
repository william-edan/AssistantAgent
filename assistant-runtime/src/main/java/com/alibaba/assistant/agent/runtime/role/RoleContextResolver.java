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
package com.alibaba.assistant.agent.runtime.role;

import com.alibaba.assistant.agent.controlplane.rolepackage.ResolvedRolePackageManagementView;
import com.alibaba.assistant.agent.controlplane.rolepackage.RolePackageService;
import com.alibaba.assistant.agent.runtime.agent.AssistantStateKeys;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.agent.tools.ToolContextConstants;
import org.springframework.context.annotation.Profile;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Resolves role-package context from runtime attributes.
 */
@Component
@Profile("migration")
public class RoleContextResolver {

    private final RolePackageService rolePackageService;

    public RoleContextResolver(RolePackageService rolePackageService) {
        this.rolePackageService = rolePackageService;
    }

    /**
     * Resolve role context from a flat attribute map or tool-context map.
     */
    public Optional<RoleContext> resolve(@Nullable Map<String, Object> attributes) {
        Map<String, Object> state = flattenState(attributes);
        String roleCode = readText(state, AssistantStateKeys.ROLE_PACKAGE_CODE, "role_package_code", "roleCode");
        String version = readText(state, AssistantStateKeys.ROLE_PACKAGE_VERSION, "role_package_version", "roleVersion");
        String agentAppCode = readText(state, AssistantStateKeys.AGENT_APP_CODE, "agent_app_code", "agentAppCode", "appName");
        Long spaceId = readLong(state, AssistantStateKeys.SPACE_ID, "space_id", "spaceId");
        String scenarioCode = readText(state, AssistantStateKeys.ROLE_SCENARIO_CODE, "role_scenario_code", "roleScenarioCode");
        if (!StringUtils.hasText(roleCode) || !StringUtils.hasText(agentAppCode) || spaceId == null) {
            return Optional.empty();
        }
        return rolePackageService.getRolePackage(roleCode, version, spaceId, agentAppCode)
                .map(view -> new RoleContext(
                        spaceId,
                        view.agentAppCode(),
                        view.roleCode(),
                        view.version(),
                        view.displayName(),
                        view.persona(),
                        scenarioCode,
                        view.scenarios(),
                        view.toolScopes()));
    }

    Map<String, Object> flattenState(@Nullable Map<String, Object> attributes) {
        if (attributes == null || attributes.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> flattened = new LinkedHashMap<>(attributes);
        Object state = attributes.get(ToolContextConstants.AGENT_STATE_CONTEXT_KEY);
        if (state instanceof OverAllState overAllState && overAllState.data() != null && !overAllState.data().isEmpty()) {
            flattened.putAll(overAllState.data());
        }
        return flattened;
    }

    private String readText(Map<String, Object> source, String... keys) {
        for (String key : keys) {
            if (!StringUtils.hasText(key) || !source.containsKey(key)) {
                continue;
            }
            Object value = source.get(key);
            if (value == null) {
                continue;
            }
            String text = String.valueOf(value).trim();
            if (StringUtils.hasText(text)) {
                return text;
            }
        }
        return null;
    }

    private Long readLong(Map<String, Object> source, String... keys) {
        String text = readText(source, keys);
        if (!StringUtils.hasText(text)) {
            return null;
        }
        try {
            return Long.valueOf(text);
        }
        catch (NumberFormatException ignored) {
            return null;
        }
    }

    /**
     * Immutable role context used by prompt, routing and tool filtering.
     */
    public record RoleContext(
            Long spaceId,
            String agentAppCode,
            String roleCode,
            String version,
            String displayName,
            String persona,
            String activeScenarioCode,
            List<ResolvedRolePackageManagementView.RoleScenarioView> scenarios,
            List<ResolvedRolePackageManagementView.RoleToolScopeView> toolScopes) {

        public RoleContext {
            scenarios = scenarios == null ? List.of() : List.copyOf(scenarios);
            toolScopes = toolScopes == null ? List.of() : List.copyOf(toolScopes);
        }

        public RoleContext withScenario(String scenarioCode) {
            return new RoleContext(
                    spaceId,
                    agentAppCode,
                    roleCode,
                    version,
                    displayName,
                    persona,
                    scenarioCode,
                    scenarios,
                    toolScopes);
        }

        public Optional<ResolvedRolePackageManagementView.RoleScenarioView> findScenario(String scenarioCode) {
            if (!StringUtils.hasText(scenarioCode)) {
                return Optional.empty();
            }
            return scenarios.stream()
                    .filter(scenario -> scenario != null && scenarioCode.trim().equalsIgnoreCase(scenario.scenarioCode()))
                    .findFirst();
        }
    }
}
