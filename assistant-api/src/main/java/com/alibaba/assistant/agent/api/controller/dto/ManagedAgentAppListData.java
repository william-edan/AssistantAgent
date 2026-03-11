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
package com.alibaba.assistant.agent.api.controller.dto;

import com.alibaba.assistant.agent.controlplane.agentapp.ResolvedAgentAppManagementView;

import java.util.List;

/**
 * Agent-app list payload for management APIs.
 */
public record ManagedAgentAppListData(String spaceCode, String environment, List<ManagedAgentAppData> agentApps) {

    public static ManagedAgentAppListData from(
            String spaceCode,
            String environment,
            List<ResolvedAgentAppManagementView> resolvedViews) {
        return new ManagedAgentAppListData(
                spaceCode,
                environment,
                resolvedViews == null ? List.of() : resolvedViews.stream().map(ManagedAgentAppData::from).toList());
    }
}
