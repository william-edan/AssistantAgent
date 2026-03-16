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
package com.alibaba.assistant.agent.controlplane.identity;

import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/**
 * 迁移模式下本地用户在空间内的控制面权限策略。
 */
public record LocalUserControlPlaneAccessPolicy(
        boolean spaceAdmin,
        List<String> agentAppAdminCodes) {

    public LocalUserControlPlaneAccessPolicy {
        agentAppAdminCodes = normalizeCodes(agentAppAdminCodes);
    }

    private static List<String> normalizeCodes(Collection<String> rawCodes) {
        if (rawCodes == null || rawCodes.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String rawCode : rawCodes) {
            if (!StringUtils.hasText(rawCode)) {
                continue;
            }
            normalized.add(rawCode.trim().toLowerCase(Locale.ROOT));
        }
        return normalized.isEmpty() ? List.of() : List.copyOf(new ArrayList<>(normalized));
    }
}
