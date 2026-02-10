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
import com.alibaba.assistant.agent.start.saas.app.service.UserBindingAppService;
import com.alibaba.assistant.agent.start.saas.controller.dto.CreateUserBindingRequest;
import com.alibaba.assistant.agent.start.saas.controller.dto.UserBindingResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * User binding APIs.
 *
 * @author Assistant Agent Team
 * @since 1.0.0
 */
@RestController
@RequestMapping("/api/v1/tenant/{tenantId}/user-bindings")
public class UserBindingController {

    private final UserBindingAppService userBindingAppService;

    public UserBindingController(UserBindingAppService userBindingAppService) {
        this.userBindingAppService = userBindingAppService;
    }

    /**
     * Create user binding.
     *
     * @param tenantId tenant id
     * @param request request
     * @return response
     */
    @PostMapping
    public ApiResponse<UserBindingResponse> create(@PathVariable("tenantId") String tenantId,
            @Valid @RequestBody CreateUserBindingRequest request) {
        return ApiResponse.success(userBindingAppService.createBinding(tenantId, request));
    }

    /**
     * Query bindings by platform user.
     *
     * @param tenantId tenant id
     * @param platformUserId platform user id
     * @return response
     */
    @GetMapping("/{platformUserId}")
    public ApiResponse<List<UserBindingResponse>> listByPlatformUser(@PathVariable("tenantId") String tenantId,
            @PathVariable("platformUserId") String platformUserId) {
        return ApiResponse.success(userBindingAppService.listBindings(tenantId, platformUserId));
    }

    /**
     * Delete binding by id.
     *
     * @param tenantId tenant id
     * @param id binding id
     * @return response
     */
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable("tenantId") String tenantId, @PathVariable("id") Long id) {
        userBindingAppService.deleteBinding(tenantId, id);
        return ApiResponse.success(null);
    }
}
