/*
 * Copyright 2024-2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 */
package com.alibaba.assistant.agent.runtime.proactive;

import com.alibaba.assistant.agent.controlplane.rolepackage.RoleProactiveTaskQueryService;
import com.alibaba.assistant.agent.controlplane.rolepackage.SubjectResolverCapability;
import com.alibaba.assistant.agent.execution.persistence.ProactiveRunLease;
import com.alibaba.assistant.agent.runtime.agent.AssistantStateKeys;
import com.alibaba.assistant.agent.runtime.execution.ArtifactRunDispatcher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class RoleProactiveTaskDispatcher {

    private final ArtifactRunDispatcher artifactRunDispatcher;

    private final List<SubjectResolverCapability> subjectResolverCapabilities;

    public RoleProactiveTaskDispatcher(ArtifactRunDispatcher artifactRunDispatcher) {
        this(artifactRunDispatcher, List.of());
    }

    @Autowired
    public RoleProactiveTaskDispatcher(
            ArtifactRunDispatcher artifactRunDispatcher,
            @Nullable List<SubjectResolverCapability> subjectResolverCapabilities) {
        this.artifactRunDispatcher = artifactRunDispatcher;
        this.subjectResolverCapabilities = subjectResolverCapabilities != null
                ? List.copyOf(subjectResolverCapabilities)
                : List.of();
    }

    public void dispatch(RoleProactiveTaskQueryService.PublishedRoleProactiveTask task, ProactiveRunLease lease) {
        Map<String, Object> contextAttributes = new LinkedHashMap<>();
        contextAttributes.put(AssistantStateKeys.SPACE_ID, task.spaceId());
        contextAttributes.put(AssistantStateKeys.SPACE_ENVIRONMENT, task.environment());
        contextAttributes.put(AssistantStateKeys.AGENT_APP_CODE, task.agentAppCode());
        contextAttributes.put(AssistantStateKeys.ROLE_PACKAGE_CODE, task.roleCode());
        contextAttributes.put(AssistantStateKeys.ROLE_PACKAGE_VERSION, task.roleVersion());
        contextAttributes.put(AssistantStateKeys.ROLE_SCENARIO_CODE, task.scenarioCode());
        contextAttributes.put("environment", task.environment());

        Map<String, Object> arguments = new LinkedHashMap<>(task.taskPayload());
        arguments.remove("subject");
        arguments.putIfAbsent(AssistantStateKeys.PROACTIVE_TASK_CODE, task.taskCode());
        for (SubjectResolverCapability capability : subjectResolverCapabilities) {
            if (capability == null || !capability.supports(task)) {
                continue;
            }
            Map<String, Object> resolvedArguments = capability.resolveSubjectArguments(task);
            if (resolvedArguments != null && !resolvedArguments.isEmpty()) {
                arguments.putAll(resolvedArguments);
            }
        }

        // 快速定位：主动任务不会经过 ChatController，而是从这里直接桥接到 ArtifactRunDispatcher / ArtifactRuntimeExecutor。
        artifactRunDispatcher.dispatch(
                task.artifactCode(),
                arguments,
                contextAttributes,
                lease);
    }
}



