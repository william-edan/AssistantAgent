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
package com.alibaba.assistant.agent.runtime.execution;

import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 用于恢复暂停执行的可序列化运行时快照。
 */
public record ExecutionContextSnapshot(
        String systemCode,
        Map<String, Object> initialInputs,
        Map<String, Map<String, Object>> stepOutputs,
        Map<String, String> stepStatuses) {

    public ExecutionContextSnapshot {
        systemCode = StringUtils.hasText(systemCode) ? systemCode.trim() : null;
        initialInputs = initialInputs != null ? Map.copyOf(new LinkedHashMap<>(initialInputs)) : Map.of();
        if (stepOutputs != null) {
            Map<String, Map<String, Object>> normalized = new LinkedHashMap<>();
            for (Map.Entry<String, Map<String, Object>> entry : stepOutputs.entrySet()) {
                normalized.put(entry.getKey(), entry.getValue() != null ? Map.copyOf(new LinkedHashMap<>(entry.getValue())) : Map.of());
            }
            stepOutputs = Map.copyOf(normalized);
        }
        else {
            stepOutputs = Map.of();
        }
        stepStatuses = stepStatuses != null ? Map.copyOf(new LinkedHashMap<>(stepStatuses)) : Map.of();
    }
}
