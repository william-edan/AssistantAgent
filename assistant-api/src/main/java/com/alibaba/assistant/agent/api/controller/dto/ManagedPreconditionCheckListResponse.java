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
 * API envelope for managed precondition-check list responses.
 */
public record ManagedPreconditionCheckListResponse(int code, String msg, ManagedPreconditionCheckListData data) {

    public static ManagedPreconditionCheckListResponse ok(ManagedPreconditionCheckListData data) {
        return new ManagedPreconditionCheckListResponse(0, "", data);
    }
}
