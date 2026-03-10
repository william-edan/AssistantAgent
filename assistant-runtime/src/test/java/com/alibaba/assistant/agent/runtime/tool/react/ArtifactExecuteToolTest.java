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

import com.alibaba.assistant.agent.runtime.execution.ArtifactRuntimeExecutor;
import com.alibaba.assistant.agent.runtime.registry.ArtifactPublicationLookupService;
import com.alibaba.assistant.agent.runtime.registry.PublishedToolDescriptor;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ArtifactExecuteToolTest {

    @Test
    void shouldResolvePublishedArtifactAndExecuteByToolCode() {
        ArtifactPublicationLookupService lookupService = mock(ArtifactPublicationLookupService.class);
        ArtifactRuntimeExecutor runtimeExecutor = mock(ArtifactRuntimeExecutor.class);
        ObjectMapper objectMapper = new ObjectMapper();
        ArtifactExecuteTool tool = new ArtifactExecuteTool(lookupService, runtimeExecutor, objectMapper);

        PublishedToolDescriptor descriptor = mock(PublishedToolDescriptor.class);
        ToolContext toolContext = new ToolContext(Map.of("space_code", "default", "agent_app_code", "hr-assistant"));
        when(lookupService.findPublishedArtifact("oa.leave.apply", toolContext)).thenReturn(Optional.of(descriptor));
        when(runtimeExecutor.execute(same(descriptor), anyMap(), same(toolContext)))
                .thenReturn(Map.of("success", true, "artifactCode", "oa.leave.apply"));

        ArtifactExecuteTool.Request request = new ArtifactExecuteTool.Request();
        request.toolCode = "oa.leave.apply";
        request.params = Map.of("reason", "事假");
        request.confirmed = true;

        ArtifactExecuteTool.Response response = tool.apply(request, toolContext);

        assertTrue(response.success);
        assertEquals("oa.leave.apply", response.artifactCode);
        assertEquals(true, response.result.get("success"));
    }

    @Test
    void shouldReturnErrorWhenArtifactPublicationDoesNotExist() {
        ArtifactPublicationLookupService lookupService = mock(ArtifactPublicationLookupService.class);
        ArtifactRuntimeExecutor runtimeExecutor = mock(ArtifactRuntimeExecutor.class);
        ArtifactExecuteTool tool = new ArtifactExecuteTool(lookupService, runtimeExecutor, new ObjectMapper());

        when(lookupService.findPublishedArtifact(eq("oa.leave.apply"), org.mockito.ArgumentMatchers.any()))
                .thenReturn(Optional.empty());

        ArtifactExecuteTool.Request request = new ArtifactExecuteTool.Request();
        request.toolCode = "oa.leave.apply";

        ArtifactExecuteTool.Response response = tool.apply(request, new ToolContext(Map.of()));

        assertTrue(!response.success);
        assertTrue(response.error.contains("oa.leave.apply"));
    }
}
