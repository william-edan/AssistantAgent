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

import com.alibaba.assistant.agent.controlplane.catalog.ResolvedPlatformSpaceView;

import java.util.List;

/**
 * Space list payload for control-plane navigation.
 */
public record ControlPlaneSpaceListData(List<SpaceSummary> spaces) {

    public static ControlPlaneSpaceListData from(List<ResolvedPlatformSpaceView> spaces) {
        List<SpaceSummary> items = spaces == null ? List.of() : spaces.stream().map(SpaceSummary::from).toList();
        return new ControlPlaneSpaceListData(items);
    }

    public record SpaceSummary(
            Long spaceId,
            String spaceCode,
            String spaceName,
            String environment,
            String status) {

        static SpaceSummary from(ResolvedPlatformSpaceView view) {
            return new SpaceSummary(
                    view.spaceId(),
                    view.spaceCode(),
                    view.spaceName(),
                    normalize(view.environment()),
                    normalize(view.status()));
        }
    }

    private static String normalize(String value) {
        return value == null ? null : value.toUpperCase();
    }
}
