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
package com.alibaba.assistant.agent.api.controller;

import com.alibaba.assistant.agent.api.controller.dto.ControlPlaneErrorResponse;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

/**
 * 把控制面异常统一包装为标准响应结构。
 */
@RestControllerAdvice(assignableTypes = {
        AgentAppManagementController.class,
        AgentAppPublicationPolicyController.class,
        AuthProfileManagementController.class,
        ConnectorCatalogController.class,
        ConnectorManagementController.class,
        ControlPlaneCatalogController.class,
        ExecutionApprovalController.class,
        ExecutionEventTimelineController.class,
        ExecutionHistoryController.class,
        ExecutionRunListController.class,
        LocalUserControlPlaneAccessPolicyController.class,
        LocalUserManagementController.class,
        ToolCatalogController.class,
        ToolMetaManagementController.class
})
@Profile("migration")
public class ControlPlaneErrorResponseAdvice {

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ControlPlaneErrorResponse> handleResponseStatusException(ResponseStatusException ex) {
        String message = StringUtils.hasText(ex.getReason()) ? ex.getReason() : ex.getStatusCode().toString();
        return ResponseEntity.status(ex.getStatusCode())
                .body(ControlPlaneErrorResponse.of(ex.getStatusCode().value(), message));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ControlPlaneErrorResponse> handleIllegalArgumentException(IllegalArgumentException ex) {
        String message = StringUtils.hasText(ex.getMessage()) ? ex.getMessage() : "bad_request";
        return ResponseEntity.badRequest()
                .body(ControlPlaneErrorResponse.of(400, message));
    }
}
