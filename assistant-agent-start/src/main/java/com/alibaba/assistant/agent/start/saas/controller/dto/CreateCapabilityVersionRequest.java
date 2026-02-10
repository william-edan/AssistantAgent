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
import jakarta.validation.constraints.NotNull;

/**
 * Create capability version request.
 *
 * @author Assistant Agent Team
 * @since 1.0.0
 */
public class CreateCapabilityVersionRequest {

    @NotNull
    private Long connectorId;

    @NotBlank
    private String inputSchemaJson;

    @NotBlank
    private String outputSchemaJson;

    private String slotSchemaJson;

    private String toolBindingJson;

    @NotBlank
    private String routeConfigJson;

    @NotBlank
    private String executionMode;

    private String operator;

    public Long getConnectorId() {
        return connectorId;
    }

    public void setConnectorId(Long connectorId) {
        this.connectorId = connectorId;
    }

    public String getInputSchemaJson() {
        return inputSchemaJson;
    }

    public void setInputSchemaJson(String inputSchemaJson) {
        this.inputSchemaJson = inputSchemaJson;
    }

    public String getOutputSchemaJson() {
        return outputSchemaJson;
    }

    public void setOutputSchemaJson(String outputSchemaJson) {
        this.outputSchemaJson = outputSchemaJson;
    }

    public String getSlotSchemaJson() {
        return slotSchemaJson;
    }

    public void setSlotSchemaJson(String slotSchemaJson) {
        this.slotSchemaJson = slotSchemaJson;
    }

    public String getToolBindingJson() {
        return toolBindingJson;
    }

    public void setToolBindingJson(String toolBindingJson) {
        this.toolBindingJson = toolBindingJson;
    }

    public String getRouteConfigJson() {
        return routeConfigJson;
    }

    public void setRouteConfigJson(String routeConfigJson) {
        this.routeConfigJson = routeConfigJson;
    }

    public String getExecutionMode() {
        return executionMode;
    }

    public void setExecutionMode(String executionMode) {
        this.executionMode = executionMode;
    }

    public String getOperator() {
        return operator;
    }

    public void setOperator(String operator) {
        this.operator = operator;
    }
}
