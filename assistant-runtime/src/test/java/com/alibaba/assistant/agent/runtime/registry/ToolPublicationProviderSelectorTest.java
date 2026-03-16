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
package com.alibaba.assistant.agent.runtime.registry;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ToolPublicationProviderSelectorTest {

    @Test
    void shouldSelectOnlyRequestedSourcesInExclusiveMode() {
        ToolPublicationProviderSelector selector = new ToolPublicationProviderSelector();
        ToolPublicationProvider artifact = provider("tool-meta-catalog");
        ToolPublicationProvider direct = provider("synthetic-direct");
        ToolPublicationProvider mcp = provider("mcp-gateway");

        ToolPublicationProvider.PublicationScope scope = new ToolPublicationProvider.PublicationScope(
                "default",
                1L,
                "prod",
                "hr-assistant",
                ToolPublicationProvider.SourceSelectionMode.EXCLUSIVE,
                List.of("mcp-gateway", "tool-meta-catalog"),
                List.of());

        List<String> selected = selector.selectProviders(scope, List.of(artifact, direct, mcp)).stream()
                .map(ToolPublicationProvider::providerId)
                .toList();

        assertEquals(List.of("mcp-gateway", "tool-meta-catalog"), selected);
    }

    @Test
    void shouldSkipBlockedSourcesAndAppendRemainingEligibleSourcesInMergeMode() {
        ToolPublicationProviderSelector selector = new ToolPublicationProviderSelector();
        ToolPublicationProvider artifact = provider("tool-meta-catalog");
        ToolPublicationProvider direct = provider("synthetic-direct");
        ToolPublicationProvider mcp = provider("mcp-gateway");

        ToolPublicationProvider.PublicationScope scope = new ToolPublicationProvider.PublicationScope(
                "default",
                1L,
                "prod",
                "hr-assistant",
                ToolPublicationProvider.SourceSelectionMode.MERGE,
                List.of("mcp-gateway"),
                List.of("tool-meta-catalog"));

        List<String> selected = selector.selectProviders(scope, List.of(artifact, direct, mcp)).stream()
                .map(ToolPublicationProvider::providerId)
                .toList();

        assertEquals(List.of("mcp-gateway", "synthetic-direct"), selected);
    }

    private ToolPublicationProvider provider(String providerId) {
        return new ToolPublicationProvider() {
            @Override
            public String providerId() {
                return providerId;
            }

            @Override
            public List<PublishedToolDescriptor> listPublishedTools(PublicationScope scope) {
                return List.of();
            }
        };
    }
}
