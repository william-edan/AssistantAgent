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
package com.alibaba.assistant.agent.slot;

import com.alibaba.assistant.agent.controlplane.toolregistry.ToolMeta;
import com.alibaba.assistant.agent.controlplane.toolregistry.ToolMetaService;
import com.alibaba.assistant.agent.execution.flow.FlowContext;
import com.alibaba.assistant.agent.execution.model.StepConfig;
import com.alibaba.assistant.agent.execution.model.StepResult;
import com.alibaba.assistant.agent.execution.step.HttpStepExecutor;
import com.alibaba.assistant.agent.slot.model.SlotDefinition;
import com.alibaba.assistant.agent.slot.model.SlotOption;
import com.alibaba.assistant.agent.slot.model.SlotOptions;
import com.alibaba.assistant.agent.slot.model.ToolOptionResolverConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ToolBackedSlotOptionResolverTest {

    @Mock
    private ToolMetaService toolMetaService;

    @Mock
    private HttpStepExecutor httpStepExecutor;

    private ToolBackedSlotOptionResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new ToolBackedSlotOptionResolver(toolMetaService, httpStepExecutor, new ObjectMapper());
    }

    @Test
    void shouldResolveOptionsFromQueryToolExecutionPlan() {
        SlotDefinition slot = new SlotDefinition();
        slot.setName("check_uids");
        SlotOptions options = new SlotOptions();
        options.setSource(SlotOptions.SourceType.TOOL);
        ToolOptionResolverConfig toolConfig = new ToolOptionResolverConfig();
        toolConfig.setToolCode("gougu_oa.approver_candidates");
        toolConfig.setResultPath("data");
        toolConfig.setLabelField("name");
        toolConfig.setValueField("id");
        toolConfig.setDescriptionField("role");
        options.setToolConfig(toolConfig);
        slot.setOptions(options);

        ToolMeta toolMeta = new ToolMeta();
        toolMeta.setToolCode("gougu_oa.approver_candidates");
        toolMeta.setSystemCode("gougu_oa");
        toolMeta.setInteractionPolicy("""
                {
                  "toolType": "QUERY"
                }
                """);
        toolMeta.setExecutionPlan("""
                {
                  "entry": ["fetch"],
                  "steps": {
                    "fetch": {
                      "type": "HTTP",
                      "config": {
                        "method": "GET",
                        "endpoint": "/api/oa_integration/get_department_users_tree",
                        "contentType": "application/json",
                        "outputMapping": {
                          "data": "$.data"
                        },
                        "successCondition": "$.code == 0"
                      }
                    }
                  }
                }
                """);

        when(toolMetaService.findLatestEnabledByToolCode(null, "gougu_oa.approver_candidates"))
                .thenReturn(Optional.of(toolMeta));
        when(httpStepExecutor.execute(any(StepConfig.class), any(FlowContext.class))).thenReturn(
                StepResult.success(Map.of("data", List.of(
                        new LinkedHashMap<>(Map.of("id", 4, "name", "人事领导", "role", "直属上级")),
                        new LinkedHashMap<>(Map.of("id", 7, "name", "财务负责人", "role", "备选审批人"))))));

        List<SlotOption> resolved = resolver.resolve(slot, "oa", "user1");

        assertEquals(2, resolved.size());
        assertEquals("人事领导", resolved.get(0).getLabel());
        assertEquals(4, resolved.get(0).getValue());
        assertEquals("直属上级", resolved.get(0).getDescription());

        ArgumentCaptor<StepConfig> stepCaptor = ArgumentCaptor.forClass(StepConfig.class);
        ArgumentCaptor<FlowContext> contextCaptor = ArgumentCaptor.forClass(FlowContext.class);
        verify(httpStepExecutor).execute(stepCaptor.capture(), contextCaptor.capture());
        assertEquals("GET", stepCaptor.getValue().getMethod());
        assertEquals("/api/oa_integration/get_department_users_tree", stepCaptor.getValue().getEndpoint());
        assertEquals("gougu_oa", contextCaptor.getValue().getSystemCode());
        assertEquals("user1", contextCaptor.getValue().getAssistantUid());
    }

    @Test
    void shouldRejectNonQueryTools() {
        SlotDefinition slot = new SlotDefinition();
        SlotOptions options = new SlotOptions();
        options.setSource(SlotOptions.SourceType.TOOL);
        ToolOptionResolverConfig toolConfig = new ToolOptionResolverConfig();
        toolConfig.setToolCode("gougu_oa.leave_application");
        options.setToolConfig(toolConfig);
        slot.setOptions(options);

        ToolMeta toolMeta = new ToolMeta();
        toolMeta.setToolCode("gougu_oa.leave_application");

        when(toolMetaService.findLatestEnabledByToolCode(null, "gougu_oa.leave_application"))
                .thenReturn(Optional.of(toolMeta));

        assertThrows(IllegalStateException.class, () -> resolver.resolve(slot, "oa", "user1"));
    }
}
