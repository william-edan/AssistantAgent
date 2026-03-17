/*
 * Copyright 2024-2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 */
package com.alibaba.assistant.agent.controlplane.rolepackage;

import com.alibaba.assistant.agent.controlplane.rolepackage.mapper.RolePackageMapper;
import com.alibaba.assistant.agent.controlplane.rolepackage.mapper.RoleProactiveTaskMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;

@Service
public class RoleProactiveTaskQueryService {

    private final RolePackageMapper rolePackageMapper;
    private final RoleProactiveTaskMapper roleProactiveTaskMapper;
    private final ObjectMapper objectMapper;

    public RoleProactiveTaskQueryService(
            RolePackageMapper rolePackageMapper,
            RoleProactiveTaskMapper roleProactiveTaskMapper) {
        this(rolePackageMapper, roleProactiveTaskMapper, new ObjectMapper());
    }

    @Autowired
    public RoleProactiveTaskQueryService(
            RolePackageMapper rolePackageMapper,
            RoleProactiveTaskMapper roleProactiveTaskMapper,
            ObjectMapper objectMapper) {
        this.rolePackageMapper = rolePackageMapper;
        this.roleProactiveTaskMapper = roleProactiveTaskMapper;
        this.objectMapper = objectMapper;
    }

    public List<PublishedRoleProactiveTask> listPublishedTasks() {
        return rolePackageMapper.listPublished().stream()
                .flatMap(rolePackage -> roleProactiveTaskMapper.listByRolePackageId(rolePackage.getId()).stream()
                        .filter(this::isEnabled)
                        .map(task -> new PublishedRoleProactiveTask(
                                rolePackage.getSpaceId(),
                                "prod",
                                rolePackage.getAgentAppCode(),
                                rolePackage.getRoleCode(),
                                rolePackage.getVersion(),
                                task.getTaskCode(),
                                task.getCronExpr(),
                                task.getArtifactCode(),
                                task.getScenarioCode(),
                                parsePayload(task.getTaskPayloadJson()))))
                .toList();
    }

    private boolean isEnabled(RoleProactiveTask task) {
        return task != null && "enabled".equalsIgnoreCase(task.getStatus());
    }

    private Map<String, Object> parsePayload(String json) {
        if (!StringUtils.hasText(json)) {
            return Map.of();
        }
        try {
            Map<String, Object> payload = objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {
            });
            return payload == null ? Map.of() : payload;
        }
        catch (Exception ignored) {
            return Map.of();
        }
    }

    public record PublishedRoleProactiveTask(
            Long spaceId,
            String environment,
            String agentAppCode,
            String roleCode,
            String roleVersion,
            String taskCode,
            String cronExpr,
            String artifactCode,
            String scenarioCode,
            Map<String, Object> taskPayload) {

        public String taskKey() {
            return String.join("|",
                    String.valueOf(spaceId != null ? spaceId : 0L),
                    normalize(agentAppCode),
                    normalize(roleCode),
                    normalize(roleVersion),
                    normalize(taskCode));
        }

        private static String normalize(String value) {
            return StringUtils.hasText(value) ? value.trim() : "_";
        }
    }
}



