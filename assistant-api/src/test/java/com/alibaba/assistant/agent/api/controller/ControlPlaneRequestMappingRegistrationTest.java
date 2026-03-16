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

import com.alibaba.assistant.agent.api.security.MigrationControlPlaneAuthorizationService;
import com.alibaba.assistant.agent.controlplane.connector.ConnectorCatalogService;
import com.alibaba.assistant.agent.controlplane.connector.ConnectorManagementService;
import com.alibaba.assistant.agent.controlplane.toolregistry.ToolMetaCatalogService;
import com.alibaba.assistant.agent.controlplane.toolregistry.ToolMetaManagementService;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.mock;

class ControlPlaneRequestMappingRegistrationTest {

    @Test
    void shouldRegisterConnectorControllersWithoutAmbiguousMappings() {
        assertDoesNotThrow(() -> MockMvcBuilders.standaloneSetup(
                new ConnectorCatalogController(
                        mock(ConnectorCatalogService.class),
                        mock(MigrationControlPlaneAuthorizationService.class)),
                new ConnectorManagementController(
                        mock(ConnectorManagementService.class),
                        mock(MigrationControlPlaneAuthorizationService.class)))
                .setControllerAdvice(new ControlPlaneErrorResponseAdvice())
                .build());
    }

    @Test
    void shouldRegisterToolControllersWithoutAmbiguousMappings() {
        assertDoesNotThrow(() -> MockMvcBuilders.standaloneSetup(
                new ToolCatalogController(
                        mock(ToolMetaCatalogService.class),
                        mock(MigrationControlPlaneAuthorizationService.class)),
                new ToolMetaManagementController(
                        mock(ToolMetaManagementService.class),
                        mock(MigrationControlPlaneAuthorizationService.class)))
                .setControllerAdvice(new ControlPlaneErrorResponseAdvice())
                .build());
    }
}
