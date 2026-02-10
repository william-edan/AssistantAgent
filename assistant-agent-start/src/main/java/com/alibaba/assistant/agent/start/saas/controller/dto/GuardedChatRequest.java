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
package com.alibaba.assistant.agent.start.saas.controller.dto;

import jakarta.validation.constraints.NotBlank;

import java.util.Map;

/**
 * Guarded conversation execute request.
 *
 * @author Assistant Agent Team
 * @since 1.0.0
 */
public class GuardedChatRequest {

    @NotBlank
    private String requestId;

    @NotBlank
    private String executorUserId;

    @NotBlank
    private String capabilityId;

    private Integer capabilityVersion;

    private String userInput;

    private Map<String, Object> input;

    public String getRequestId() {
        return requestId;
    }

    public void setRequestId(String requestId) {
        this.requestId = requestId;
    }

    public String getExecutorUserId() {
        return executorUserId;
    }

    public void setExecutorUserId(String executorUserId) {
        this.executorUserId = executorUserId;
    }

    public String getCapabilityId() {
        return capabilityId;
    }

    public void setCapabilityId(String capabilityId) {
        this.capabilityId = capabilityId;
    }

    public Integer getCapabilityVersion() {
        return capabilityVersion;
    }

    public void setCapabilityVersion(Integer capabilityVersion) {
        this.capabilityVersion = capabilityVersion;
    }

    public String getUserInput() {
        return userInput;
    }

    public void setUserInput(String userInput) {
        this.userInput = userInput;
    }

    public Map<String, Object> getInput() {
        return input;
    }

    public void setInput(Map<String, Object> input) {
        this.input = input;
    }
}
