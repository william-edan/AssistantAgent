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

import com.alibaba.assistant.agent.api.security.AuthenticatedUserContext;
import com.alibaba.assistant.agent.api.security.MigrationControlPlaneAuthorizationService;
import com.alibaba.assistant.agent.controlplane.workflow.ResolvedWorkflowManagementView;
import com.alibaba.assistant.agent.controlplane.workflow.ResolvedWorkflowStepManagementView;
import com.alibaba.assistant.agent.controlplane.workflow.WorkflowManagementService;
import com.alibaba.assistant.agent.controlplane.workflow.WorkflowSpecUpsertCommand;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.security.Principal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class WorkflowManagementControllerTest {

    private MockMvc mockMvc;

    @Mock
    private WorkflowManagementService workflowManagementService;

    @Mock
    private MigrationControlPlaneAuthorizationService authorizationService;

    @BeforeEach
    void setUp() {
        WorkflowManagementController controller =
                new WorkflowManagementController(workflowManagementService, authorizationService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void shouldListManagedWorkflows() throws Exception {
        when(authorizationService.canManageSpaceCatalog(any(AuthenticatedUserContext.class), eq("enterprise-default"), eq("prod")))
                .thenReturn(true);
        when(workflowManagementService.listWorkflows("enterprise-default", "prod"))
                .thenReturn(List.of(new ResolvedWorkflowManagementView(
                        31L,
                        "enterprise-default",
                        "prod",
                        "oa.leave.apply",
                        "请假申请",
                        "leave.apply.form",
                        "max",
                        "strictest",
                        Map.of("mode", "fail_fast"),
                        Map.of("level", "full"),
                        "enabled",
                        List.of(new ResolvedWorkflowStepManagementView(
                                "create_leave",
                                "创建请假",
                                "HTTP",
                                "oa-core",
                                "/leave/create",
                                List.of("oa-user"),
                                List.of("user_mapped"),
                                Map.of("reason", "${reason}"),
                                Map.of("leaveId", "$.data.id"),
                                List.of(),
                                Map.of("expr", "${reason}"),
                                Map.of("type", "ALL"),
                                Map.of("maxRetries", 1),
                                Map.of("seconds", 30),
                                Map.of("required", false),
                                null,
                                Map.of("mode", "continue"),
                                1,
                                "enabled")))));

        mockMvc.perform(get("/api/controlplane/spaces/enterprise-default/workflows/manage")
                        .principal(authenticatedPrincipal()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.workflows.length()").value(1))
                .andExpect(jsonPath("$.data.workflows[0].workflowCode").value("oa.leave.apply"))
                .andExpect(jsonPath("$.data.workflows[0].interactionCode").value("leave.apply.form"))
                .andExpect(jsonPath("$.data.workflows[0].steps[0].connectorCode").value("oa-core"));
    }

    @Test
    void shouldUpsertManagedWorkflow() throws Exception {
        when(authorizationService.canManageSpaceCatalog(any(AuthenticatedUserContext.class), eq("enterprise-default"), eq("test")))
                .thenReturn(true);
        when(workflowManagementService.upsertWorkflow(
                eq("enterprise-default"),
                eq("test"),
                eq("oa.leave.apply"),
                any(WorkflowSpecUpsertCommand.class)))
                .thenReturn(Optional.of(new ResolvedWorkflowManagementView(
                        41L,
                        "enterprise-default",
                        "test",
                        "oa.leave.apply",
                        "请假申请",
                        "leave.apply.form",
                        "sum",
                        "strictest",
                        Map.of("mode", "continue"),
                        Map.of("level", "basic"),
                        "enabled",
                        List.of(new ResolvedWorkflowStepManagementView(
                                "submit_leave",
                                "提交请假",
                                "HTTP",
                                "oa-core",
                                "/leave/submit",
                                List.of("oa-user"),
                                List.of("user_mapped"),
                                Map.of("reason", "${reason}"),
                                Map.of("ok", true),
                                List.of("prepare"),
                                Map.of("expr", true),
                                Map.of("type", "ANY"),
                                Map.of("maxRetries", 2),
                                Map.of("seconds", 45),
                                Map.of("required", true),
                                null,
                                Map.of("mode", "continue"),
                                2,
                                "enabled")))));

        mockMvc.perform(put("/api/controlplane/spaces/enterprise-default/workflows/oa.leave.apply")
                        .param("environment", "test")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "displayName": "请假申请",
                                  "interactionCode": "leave.apply.form",
                                  "riskAggregationPolicy": "sum",
                                  "approvalAggregationPolicy": "strictest",
                                  "failurePolicy": {"mode": "continue"},
                                  "auditPolicy": {"level": "basic"},
                                  "steps": [
                                    {
                                      "stepId": "submit_leave",
                                      "stepName": "提交请假",
                                      "stepType": "HTTP",
                                      "connectorCode": "oa-core",
                                      "targetRef": "/leave/submit",
                                      "allowedAuthProfiles": ["oa-user"],
                                      "bindingStrategies": ["user_mapped"],
                                      "inputMapping": {"reason": "${reason}"},
                                      "outputMapping": {"ok": true},
                                      "dependsOn": ["prepare"],
                                      "condition": {"expr": true},
                                      "joinPolicy": {"type": "ANY"},
                                      "retryPolicy": {"maxRetries": 2},
                                      "timeoutPolicy": {"seconds": 45},
                                      "approvalGate": {"required": true},
                                      "resumePolicy": {"mode": "continue"},
                                      "stepOrder": 2,
                                      "status": "enabled"
                                    }
                                  ],
                                  "status": "enabled"
                                }
                                """)
                        .principal(authenticatedPrincipal()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.workflowCode").value("oa.leave.apply"))
                .andExpect(jsonPath("$.data.riskAggregationPolicy").value("SUM"))
                .andExpect(jsonPath("$.data.steps[0].stepId").value("submit_leave"));

        ArgumentCaptor<WorkflowSpecUpsertCommand> commandCaptor = ArgumentCaptor.forClass(WorkflowSpecUpsertCommand.class);
        verify(workflowManagementService).upsertWorkflow(
                eq("enterprise-default"),
                eq("test"),
                eq("oa.leave.apply"),
                commandCaptor.capture());
        assertEquals("leave.apply.form", commandCaptor.getValue().interactionCode());
        assertEquals("submit_leave", commandCaptor.getValue().steps().get(0).stepId());
    }

    @Test
    void shouldReturnForbiddenWhenManageScopeDenied() throws Exception {
        when(authorizationService.canManageSpaceCatalog(any(AuthenticatedUserContext.class), eq("enterprise-default"), eq("prod")))
                .thenReturn(false);

        mockMvc.perform(get("/api/controlplane/spaces/enterprise-default/workflows/manage")
                        .principal(authenticatedPrincipal()))
                .andExpect(status().isForbidden());

        verify(workflowManagementService, never()).listWorkflows("enterprise-default", "prod");
    }

    private Principal authenticatedPrincipal() {
        return new UsernamePasswordAuthenticationToken(authenticatedUser(), "token-controlplane", List.of());
    }

    private AuthenticatedUserContext authenticatedUser() {
        return new AuthenticatedUserContext(
                "1001",
                1L,
                "gougu_oa",
                "assistant-ui",
                "token-controlplane",
                "admin",
                "管理员",
                List.of("assistant_user", "assistant_controlplane_admin"),
                List.of("assistant:chat", "assistant:controlplane"));
    }
}
