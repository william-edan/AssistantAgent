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
import com.alibaba.assistant.agent.start.saas.app.service.ConnectorApiAppService;
import com.alibaba.assistant.agent.start.saas.controller.dto.ConnectorApiResponse;
import com.alibaba.assistant.agent.start.saas.controller.dto.CreateConnectorApiRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Connector API registry controller.
 *
 * @author Assistant Agent Team
 * @since 1.0.0
 */
@RestController
@RequestMapping("/api/v1/tenant/{tenantId}/connectors/{connectorId}/apis")
public class ConnectorApiController {

    private final ConnectorApiAppService connectorApiAppService;

    public ConnectorApiController(ConnectorApiAppService connectorApiAppService) {
        this.connectorApiAppService = connectorApiAppService;
    }

    /**
     * Register an API for connector.
     *
     * @param tenantId tenant id
     * @param connectorId connector id
     * @param request request
     * @return response
     */
    @PostMapping
    public ApiResponse<ConnectorApiResponse> createConnectorApi(@PathVariable("tenantId") String tenantId,
            @PathVariable("connectorId") Long connectorId,
            @Valid @RequestBody CreateConnectorApiRequest request) {
        return ApiResponse.success(connectorApiAppService.createConnectorApi(tenantId, connectorId, request));
    }

    /**
     * List registered APIs under connector.
     *
     * @param tenantId tenant id
     * @param connectorId connector id
     * @return response
     */
    @GetMapping
    public ApiResponse<List<ConnectorApiResponse>> listConnectorApis(@PathVariable("tenantId") String tenantId,
            @PathVariable("connectorId") Long connectorId) {
        return ApiResponse.success(connectorApiAppService.listConnectorApis(tenantId, connectorId));
    }
}
