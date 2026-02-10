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
import com.alibaba.assistant.agent.start.saas.app.service.CapabilityAppService;
import com.alibaba.assistant.agent.start.saas.controller.dto.CapabilityDetailResponse;
import com.alibaba.assistant.agent.start.saas.controller.dto.CreateCapabilityRequest;
import com.alibaba.assistant.agent.start.saas.controller.dto.CreateCapabilityVersionRequest;
import com.alibaba.assistant.agent.start.saas.controller.dto.PublishCapabilityRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Capability registry APIs.
 *
 * @author Assistant Agent Team
 * @since 1.0.0
 */
@RestController
@RequestMapping("/api/v1/tenant/{tenantId}/capabilities")
public class CapabilityController {

    private final CapabilityAppService capabilityAppService;

    public CapabilityController(CapabilityAppService capabilityAppService) {
        this.capabilityAppService = capabilityAppService;
    }

    /**
     * Create capability draft.
     *
     * @param tenantId tenant id
     * @param request request
     * @return response
     */
    @PostMapping
    public ApiResponse<CapabilityDetailResponse> createCapability(@PathVariable("tenantId") String tenantId,
            @Valid @RequestBody CreateCapabilityRequest request) {
        return ApiResponse.success(capabilityAppService.createCapability(tenantId, request));
    }

    /**
     * Create a new draft version for capability.
     *
     * @param tenantId tenant id
     * @param capabilityId capability id
     * @param request request
     * @return response
     */
    @PostMapping("/{capabilityId}/versions")
    public ApiResponse<CapabilityDetailResponse> createVersion(@PathVariable("tenantId") String tenantId,
            @PathVariable("capabilityId") String capabilityId,
            @Valid @RequestBody CreateCapabilityVersionRequest request) {
        return ApiResponse.success(capabilityAppService.createVersion(tenantId, capabilityId, request));
    }

    /**
     * Publish a target version.
     *
     * @param tenantId tenant id
     * @param capabilityId capability id
     * @param request request
     * @return response
     */
    @PostMapping("/{capabilityId}/publish")
    public ApiResponse<CapabilityDetailResponse> publish(@PathVariable("tenantId") String tenantId,
            @PathVariable("capabilityId") String capabilityId,
            @Valid @RequestBody PublishCapabilityRequest request) {
        return ApiResponse.success(capabilityAppService.publish(tenantId, capabilityId, request));
    }

    /**
     * Query capability detail.
     *
     * @param tenantId tenant id
     * @param capabilityId capability id
     * @return response
     */
    @GetMapping("/{capabilityId}")
    public ApiResponse<CapabilityDetailResponse> getDetail(@PathVariable("tenantId") String tenantId,
            @PathVariable("capabilityId") String capabilityId) {
        return ApiResponse.success(capabilityAppService.getDetail(tenantId, capabilityId));
    }
}
