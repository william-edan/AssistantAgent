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

/**
 * API envelope for managed agent-app list responses.
 */
public record ManagedAgentAppListResponse(int code, String msg, ManagedAgentAppListData data) {

    public static ManagedAgentAppListResponse ok(ManagedAgentAppListData data) {
        return new ManagedAgentAppListResponse(0, "", data);
    }
}
