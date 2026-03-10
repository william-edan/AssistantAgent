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
package com.alibaba.assistant.agent.runtime.tool.react;

import com.alibaba.assistant.agent.execution.flow.FlowDefinition;
import com.alibaba.assistant.agent.runtime.registry.ArtifactPublicationLookupService;
import com.alibaba.assistant.agent.runtime.registry.PublishedToolDescriptor;
import com.alibaba.assistant.agent.slot.SlotEnricherService;
import com.alibaba.assistant.agent.slot.SlotSchemaParser;
import com.alibaba.assistant.agent.slot.computed.ComputedFieldProcessor;
import com.alibaba.assistant.agent.slot.computed.ConcatFunction;
import com.alibaba.assistant.agent.slot.computed.DateDiffFunction;
import com.alibaba.assistant.agent.slot.form.FormDisplayConfigService;
import com.alibaba.assistant.agent.slot.model.SlotValue;
import com.alibaba.assistant.agent.runtime.compiler.RuntimeArtifact;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ArtifactAwareSlotConfirmToolTest {

    @Test
    void shouldResolveConfirmSnapshotFromPublishedArtifactWhenOnlyToolCodeIsProvided() {
        ObjectMapper objectMapper = new ObjectMapper();
        SlotSchemaParser slotSchemaParser = new SlotSchemaParser(objectMapper);
        SlotEnricherService slotEnricherService = mock(SlotEnricherService.class);
        ComputedFieldProcessor computedFieldProcessor = new ComputedFieldProcessor(
                List.of(new DateDiffFunction(), new ConcatFunction()));
        FormDisplayConfigService formDisplayConfigService = new FormDisplayConfigService();
        ArtifactPublicationLookupService lookupService = mock(ArtifactPublicationLookupService.class);
        when(lookupService.findPublishedArtifact(eq("oa.leave.apply"), any()))
                .thenReturn(Optional.of(descriptor("oa.leave.apply", "gougu_oa")));

        SlotConfirmTool tool = new SlotConfirmTool(
                slotSchemaParser,
                slotEnricherService,
                computedFieldProcessor,
                formDisplayConfigService,
                objectMapper,
                lookupService);

        SlotConfirmTool.Request request = new SlotConfirmTool.Request();
        request.toolCode = "oa.leave.apply";
        request.collectedSlots.put("reason", "事假");

        SlotConfirmTool.Response response = tool.apply(request, null);

        assertEquals("CONFIRMING", response.status);
        assertNotNull(response.confirmForm);
        assertEquals("oa.leave.apply", response.confirmForm.toolCode);
        assertEquals("事假", response.confirmForm.collected.get("reason"));
    }

    private PublishedToolDescriptor descriptor(String artifactCode, String systemCode) {
        RuntimeArtifact.Interaction interaction = new RuntimeArtifact.Interaction(
                1L,
                artifactCode + ".interaction",
                "{\"slots\":[{\"name\":\"reason\",\"type\":\"string\",\"required\":true}]}",
                null,
                null,
                null,
                null,
                null);
        RuntimeArtifact artifact = new RuntimeArtifact(
                1L,
                artifactCode,
                RuntimeArtifact.ArtifactType.WORKFLOW,
                "请假申请",
                1,
                null,
                null,
                null,
                null,
                interaction,
                new FlowDefinition(),
                Map.of(),
                Map.of());
        return PublishedToolDescriptor.forArtifact(
                "artifact-catalog",
                "workflow:" + artifactCode,
                "请假申请",
                null,
                null,
                false,
                systemCode,
                artifact);
    }
}

