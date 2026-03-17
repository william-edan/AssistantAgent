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

import com.alibaba.assistant.agent.controlplane.rolepackage.ResolvedRolePackageManagementView;

import java.util.List;

/**
 * API envelope for managed role-package list responses.
 */
public record RolePackageListResponse(int code, String msg, RolePackageListResponse.Data data) {

    public static RolePackageListResponse ok(
            String spaceCode,
            String environment,
            String agentAppCode,
            List<ResolvedRolePackageManagementView> rolePackages) {
        return new RolePackageListResponse(0, "", new Data(
                spaceCode,
                environment,
                agentAppCode,
                rolePackages == null ? List.of() : rolePackages.stream().map(RolePackageResponse.Data::from).toList()));
    }

    public record Data(
            String spaceCode,
            String environment,
            String agentAppCode,
            List<RolePackageResponse.Data> rolePackages) {
    }
}
