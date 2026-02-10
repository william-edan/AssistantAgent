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

import java.util.List;
import java.util.Map;

/**
 * Guarded conversation response.
 *
 * @author Assistant Agent Team
 * @since 1.0.0
 */
public class GuardedChatResponse {

    private Long executionId;

    private String resolvedCapabilityId;

    private Integer resolvedVersion;

    private String status;

    private String errorCode;

    private String message;

    private List<String> missingSlots;

    private Map<String, Object> collectedInput;

    private Map<String, Object> output;

    public Long getExecutionId() {
        return executionId;
    }

    public void setExecutionId(Long executionId) {
        this.executionId = executionId;
    }

    public String getResolvedCapabilityId() {
        return resolvedCapabilityId;
    }

    public void setResolvedCapabilityId(String resolvedCapabilityId) {
        this.resolvedCapabilityId = resolvedCapabilityId;
    }

    public Integer getResolvedVersion() {
        return resolvedVersion;
    }

    public void setResolvedVersion(Integer resolvedVersion) {
        this.resolvedVersion = resolvedVersion;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public void setErrorCode(String errorCode) {
        this.errorCode = errorCode;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public List<String> getMissingSlots() {
        return missingSlots;
    }

    public void setMissingSlots(List<String> missingSlots) {
        this.missingSlots = missingSlots;
    }

    public Map<String, Object> getCollectedInput() {
        return collectedInput;
    }

    public void setCollectedInput(Map<String, Object> collectedInput) {
        this.collectedInput = collectedInput;
    }

    public Map<String, Object> getOutput() {
        return output;
    }

    public void setOutput(Map<String, Object> output) {
        this.output = output;
    }
}
