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
package com.alibaba.assistant.agent.start.saas.controller;

import com.alibaba.assistant.agent.start.saas.api.ApiResponse;
import com.alibaba.assistant.agent.start.saas.app.service.ConnectorAppService;
import com.alibaba.assistant.agent.start.saas.controller.dto.ConnectorResponse;
import com.alibaba.assistant.agent.start.saas.controller.dto.ConnectorTestResponse;
import com.alibaba.assistant.agent.start.saas.controller.dto.CreateConnectorRequest;
import com.alibaba.assistant.agent.start.saas.controller.dto.UpdateConnectorAuthRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Connector management APIs.
 *
 * @author Assistant Agent Team
 * @since 1.0.0
 */
@RestController
@RequestMapping("/api/v1/tenant/{tenantId}/connectors")
public class ConnectorController {

    private final ConnectorAppService connectorAppService;

    public ConnectorController(ConnectorAppService connectorAppService) {
        this.connectorAppService = connectorAppService;
    }

    /**
     * Create connector.
     *
     * @param tenantId tenant id
     * @param request request
     * @return response
     */
    @PostMapping
    public ApiResponse<ConnectorResponse> create(@PathVariable("tenantId") String tenantId,
            @Valid @RequestBody CreateConnectorRequest request) {
        return ApiResponse.success(connectorAppService.createConnector(tenantId, request));
    }

    /**
     * Update connector auth.
     *
     * @param tenantId tenant id
     * @param connectorId connector id
     * @param request request
     * @return response
     */
    @PutMapping("/{connectorId}/auth")
    public ApiResponse<Void> updateAuth(@PathVariable("tenantId") String tenantId,
            @PathVariable("connectorId") Long connectorId,
            @Valid @RequestBody UpdateConnectorAuthRequest request) {
        connectorAppService.updateAuth(tenantId, connectorId, request);
        return ApiResponse.success(null);
    }

    /**
     * Test connector.
     *
     * @param tenantId tenant id
     * @param connectorId connector id
     * @return response
     */
    @PostMapping("/{connectorId}/test")
    public ApiResponse<ConnectorTestResponse> test(@PathVariable("tenantId") String tenantId,
            @PathVariable("connectorId") Long connectorId) {
        return ApiResponse.success(connectorAppService.testConnector(tenantId, connectorId));
    }

    /**
     * List connectors.
     *
     * @param tenantId tenant id
     * @return response
     */
    @GetMapping
    public ApiResponse<List<ConnectorResponse>> list(@PathVariable("tenantId") String tenantId) {
        return ApiResponse.success(connectorAppService.listConnectors(tenantId));
    }
}
