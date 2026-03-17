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
package com.alibaba.assistant.agent.runtime.role;

import com.alibaba.assistant.agent.common.tools.CodeactTool;
import com.alibaba.assistant.agent.controlplane.rolepackage.ResolvedRolePackageManagementView;
import com.alibaba.assistant.agent.runtime.compiler.RuntimeArtifact;
import com.alibaba.assistant.agent.runtime.registry.PublishedToolDescriptor;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.definition.DefaultToolDefinition;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RoleToolScopeFilterTest {

    @Test
    void shouldBlockToolsOutsideRoleScope() {
        RoleContextResolver resolver = mock(RoleContextResolver.class);
        ScenarioRouter scenarioRouter = mock(ScenarioRouter.class);
        RoleToolScopeFilter filter = new RoleToolScopeFilter(resolver, scenarioRouter);
        Map<String, Object> attrs = Map.of("role_package_code", "digital-admin");
        when(resolver.resolve(attrs)).thenReturn(Optional.of(new RoleContextResolver.RoleContext(
                10L,
                "finance-agent",
                "digital-admin",
                "v1",
                "数字行政助理",
                "负责审批、排期和通知。",
                null,
                List.of(),
                List.of(new ResolvedRolePackageManagementView.RoleToolScopeView(
                        null,
                        "gougu_oa.leave_application",
                        "REQUIRED")))));

        List<PublishedToolDescriptor> filtered = filter.filter(attrs, List.of(
                artifact("gougu_oa.leave_application"),
                artifact("gougu_oa.expense_submit")));

        assertEquals(1, filtered.size());
        assertEquals("gougu_oa.leave_application", filtered.get(0).artifact().getArtifactCode());
    }

    @Test
    void shouldKeepDependencyOnlyToolsInternal() {
        RoleContextResolver resolver = mock(RoleContextResolver.class);
        ScenarioRouter scenarioRouter = mock(ScenarioRouter.class);
        RoleToolScopeFilter filter = new RoleToolScopeFilter(resolver, scenarioRouter);
        Map<String, Object> attrs = Map.of("role_package_code", "digital-admin");
        when(resolver.resolve(attrs)).thenReturn(Optional.of(new RoleContextResolver.RoleContext(
                10L,
                "finance-agent",
                "digital-admin",
                "v1",
                "数字行政助理",
                "负责审批、排期和通知。",
                null,
                List.of(),
                List.of(new ResolvedRolePackageManagementView.RoleToolScopeView(
                        null,
                        "gougu_oa.leave_application",
                        "REQUIRED")))));

        List<PublishedToolDescriptor> filtered = filter.filter(attrs, List.of(
                artifact("gougu_oa.expense_submit"),
                internalDirect("employee_resolver")));

        assertEquals(1, filtered.size());
        assertTrue(filtered.get(0).isDirectToolPublication());
        assertEquals("employee_resolver", filtered.get(0).directTool().getToolDefinition().name());
    }

    private PublishedToolDescriptor artifact(String artifactCode) {
        RuntimeArtifact artifact = new RuntimeArtifact(
                10L,
                artifactCode,
                RuntimeArtifact.ArtifactType.ACTION,
                artifactCode,
                1,
                null,
                null,
                null,
                null,
                null,
                null,
                Map.of(),
                Map.of());
        return PublishedToolDescriptor.forArtifact(
                "tool-meta-catalog",
                "tool:" + artifactCode,
                artifactCode,
                null,
                null,
                false,
                null,
                artifact);
    }

    private PublishedToolDescriptor internalDirect(String toolName) {
        CodeactTool tool = mock(CodeactTool.class);
        when(tool.getToolDefinition()).thenReturn(DefaultToolDefinition.builder()
                .name(toolName)
                .description("internal")
                .inputSchema("{}")
                .build());
        return PublishedToolDescriptor.forDirectTool(
                "internal",
                "internal:" + toolName,
                toolName,
                "QUERY",
                "INTERNAL",
                "DEPENDENCY_ONLY",
                tool);
    }
}

