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
package com.alibaba.assistant.agent.controlplane.agentapp;

import com.alibaba.assistant.agent.controlplane.agentapp.mapper.AgentAppGrantMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class AgentAppGrantService extends ServiceImpl<AgentAppGrantMapper, AgentAppGrant> {

    private static final String TARGET_TYPE_PUBLICATION_SOURCE = "publication_source";

    private static final String TARGET_TYPE_PUBLICATION_SOURCE_POLICY = "publication_source_policy";

    private static final String GRANT_MODE_ALLOW = "allow";

    private static final String GRANT_MODE_DENY = "deny";

    private final ObjectMapper objectMapper;

    public AgentAppGrantService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * List all grants for an agent app ordered by id.
     *
     * @param agentAppId agent app id
     * @return ordered grant rows
     */
    public List<AgentAppGrant> listByAgentAppId(Long agentAppId) {
        if (agentAppId == null) {
            return Collections.emptyList();
        }

        LambdaQueryWrapper<AgentAppGrant> query = new LambdaQueryWrapper<>();
        query.eq(AgentAppGrant::getAgentAppId, agentAppId);
        query.orderByAsc(AgentAppGrant::getId);
        return list(query);
    }

    /**
     * Resolve the typed publication-source policy for an agent app.
     *
     * @param agentAppId agent app id
     * @return typed publication-source policy if present
     */
    public Optional<AgentAppPublicationSourcePolicy> findPublicationSourcePolicy(Long agentAppId) {
        if (agentAppId == null) {
            return Optional.empty();
        }

        List<AgentAppGrant> grants = listByAgentAppId(agentAppId);
        if (grants.isEmpty()) {
            return Optional.empty();
        }

        String selectionMode = "MERGE";
        LinkedHashSet<String> allowedSourceIds = new LinkedHashSet<>();
        LinkedHashSet<String> blockedSourceIds = new LinkedHashSet<>();
        for (AgentAppGrant grant : grants) {
            if (grant == null || !StringUtils.hasText(grant.getTargetType())) {
                continue;
            }
            String targetType = grant.getTargetType().trim().toLowerCase();
            if (TARGET_TYPE_PUBLICATION_SOURCE.equals(targetType)) {
                collectPublicationSourceGrant(grant, allowedSourceIds, blockedSourceIds);
                continue;
            }
            if (TARGET_TYPE_PUBLICATION_SOURCE_POLICY.equals(targetType)) {
                selectionMode = parseSelectionMode(grant.getConstraintsJson()).orElse(selectionMode);
            }
        }
        allowedSourceIds.removeAll(blockedSourceIds);
        AgentAppPublicationSourcePolicy policy = new AgentAppPublicationSourcePolicy(
                selectionMode,
                List.copyOf(allowedSourceIds),
                List.copyOf(blockedSourceIds));
        return policy.isEmpty() ? Optional.empty() : Optional.of(policy);
    }

    /**
     * Replace publication-source grants for an agent app using a typed policy definition.
     *
     * @param agentAppId agent app id
     * @param policy typed publication-source policy
     * @return true when the replacement operation completes successfully
     */
    public boolean replacePublicationSourcePolicy(Long agentAppId, AgentAppPublicationSourcePolicy policy) {
        if (agentAppId == null || policy == null) {
            return false;
        }

        LambdaQueryWrapper<AgentAppGrant> deleteQuery = new LambdaQueryWrapper<>();
        deleteQuery.eq(AgentAppGrant::getAgentAppId, agentAppId);
        deleteQuery.in(AgentAppGrant::getTargetType, TARGET_TYPE_PUBLICATION_SOURCE, TARGET_TYPE_PUBLICATION_SOURCE_POLICY);
        remove(deleteQuery);

        List<AgentAppGrant> grants = buildPublicationSourceGrants(agentAppId, policy);
        if (grants.isEmpty()) {
            return true;
        }
        return saveBatch(grants);
    }

    private void collectPublicationSourceGrant(
            AgentAppGrant grant,
            LinkedHashSet<String> allowedSourceIds,
            LinkedHashSet<String> blockedSourceIds) {
        if (!StringUtils.hasText(grant.getTargetCode()) || !StringUtils.hasText(grant.getGrantMode())) {
            return;
        }
        String targetCode = grant.getTargetCode().trim().toLowerCase();
        String grantMode = grant.getGrantMode().trim().toLowerCase();
        if (GRANT_MODE_DENY.equals(grantMode)) {
            blockedSourceIds.add(targetCode);
            return;
        }
        if (GRANT_MODE_ALLOW.equals(grantMode)) {
            allowedSourceIds.add(targetCode);
        }
    }

    private Optional<String> parseSelectionMode(String constraintsJson) {
        if (!StringUtils.hasText(constraintsJson)) {
            return Optional.empty();
        }
        try {
            JsonNode root = objectMapper.readTree(constraintsJson);
            if (root == null) {
                return Optional.empty();
            }
            String raw = textValue(root, "sourceSelectionMode");
            if (!StringUtils.hasText(raw)) {
                raw = textValue(root, "source_selection_mode");
            }
            return StringUtils.hasText(raw)
                    ? Optional.of(raw.trim().toUpperCase())
                    : Optional.empty();
        }
        catch (Exception ignored) {
            return Optional.empty();
        }
    }

    private String textValue(JsonNode root, String fieldName) {
        JsonNode node = root.get(fieldName);
        if (node == null || node.isNull()) {
            return null;
        }
        String text = node.asText(null);
        return StringUtils.hasText(text) ? text.trim() : null;
    }

    private List<AgentAppGrant> buildPublicationSourceGrants(Long agentAppId, AgentAppPublicationSourcePolicy policy) {
        List<AgentAppGrant> grants = new ArrayList<>();
        for (String sourceId : policy.allowedSourceIds()) {
            grants.add(buildGrant(agentAppId, TARGET_TYPE_PUBLICATION_SOURCE, sourceId, GRANT_MODE_ALLOW, null));
        }
        for (String sourceId : policy.blockedSourceIds()) {
            grants.add(buildGrant(agentAppId, TARGET_TYPE_PUBLICATION_SOURCE, sourceId, GRANT_MODE_DENY, null));
        }
        if (!"MERGE".equals(policy.sourceSelectionMode())) {
            grants.add(buildGrant(
                    agentAppId,
                    TARGET_TYPE_PUBLICATION_SOURCE_POLICY,
                    "default",
                    GRANT_MODE_ALLOW,
                    serializeConstraints(policy.sourceSelectionMode())));
        }
        return grants;
    }

    private AgentAppGrant buildGrant(
            Long agentAppId,
            String targetType,
            String targetCode,
            String grantMode,
            String constraintsJson) {
        AgentAppGrant grant = new AgentAppGrant();
        grant.setAgentAppId(agentAppId);
        grant.setTargetType(targetType);
        grant.setTargetCode(targetCode);
        grant.setGrantMode(grantMode);
        grant.setConstraintsJson(constraintsJson);
        return grant;
    }

    private String serializeConstraints(String sourceSelectionMode) {
        try {
            return objectMapper.writeValueAsString(Map.of("sourceSelectionMode", sourceSelectionMode));
        }
        catch (Exception ignored) {
            return "{\"sourceSelectionMode\":\"" + sourceSelectionMode + "\"}";
        }
    }

}
