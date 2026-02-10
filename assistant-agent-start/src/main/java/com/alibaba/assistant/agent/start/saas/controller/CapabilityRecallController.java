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
import com.alibaba.assistant.agent.start.saas.app.service.CapabilitySemanticRecallService;
import com.alibaba.assistant.agent.start.saas.controller.dto.CapabilityRecallResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Capability semantic recall API.
 *
 * @author Assistant Agent Team
 * @since 1.0.0
 */
@RestController
@RequestMapping("/api/v1/tenant/{tenantId}/capabilities")
public class CapabilityRecallController {

    private final CapabilitySemanticRecallService capabilitySemanticRecallService;

    public CapabilityRecallController(CapabilitySemanticRecallService capabilitySemanticRecallService) {
        this.capabilitySemanticRecallService = capabilitySemanticRecallService;
    }

    /**
     * Recall capability candidates by semantic query.
     *
     * @param tenantId tenant id
     * @param query semantic query
     * @param topK top k
     * @return candidates
     */
    @GetMapping("/recall")
    public ApiResponse<List<CapabilityRecallResponse>> recall(@PathVariable("tenantId") String tenantId,
            @RequestParam("query") String query,
            @RequestParam(value = "topK", required = false) Integer topK) {
        return ApiResponse.success(capabilitySemanticRecallService.recall(tenantId, query, topK));
    }
}
