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

import com.alibaba.assistant.agent.slot.model.EnrichedSlot;
import com.alibaba.assistant.agent.slot.model.SlotDefinition;
import com.alibaba.assistant.agent.slot.model.SlotOption;
import com.alibaba.assistant.agent.slot.model.SlotOptions;
import com.alibaba.assistant.agent.slot.model.ToolOptionResolverConfig;
import com.alibaba.assistant.agent.slot.port.OptionCachePort;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SlotEnricherServiceTest {

    @Mock
    private OptionCachePort optionCachePort;

    @Mock
    private ToolBackedSlotOptionResolver toolOptionResolver;

    private SlotEnricherService enricherService;

    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        enricherService = new SlotEnricherService(objectMapper, optionCachePort, toolOptionResolver);
    }

    @Test
    void shouldFallbackToEnumMappingWhenLegacyApiSourceIsEncountered() {
        SlotDefinition slot = new SlotDefinition();
        slot.setName("leave_type");
        slot.setType("enum");

        SlotOptions options = new SlotOptions();
        options.setSource(SlotOptions.SourceType.API);
        Map<String, Object> enumMapping = new LinkedHashMap<>();
        enumMapping.put("事假", 1);
        enumMapping.put("年假", 2);
        enumMapping.put("调休假", 3);
        options.setEnumMapping(enumMapping);
        slot.setOptions(options);

        List<EnrichedSlot> result = enricherService.enrichSlots(List.of(slot), "oa", "user1");

        assertEquals(1, result.size());
        EnrichedSlot enriched = result.get(0);
        assertNotNull(enriched.getOptions());
        assertEquals(3, enriched.getOptions().size());
        assertNotNull(enriched.getOptionsError());
        verify(toolOptionResolver, never()).resolve(org.mockito.ArgumentMatchers.any(), anyString(), anyString());
    }

    @Test
    void shouldUseEnumMappingDirectlyForEnumSource() {
        SlotDefinition slot = new SlotDefinition();
        slot.setName("leave_type");
        slot.setType("enum");

        SlotOptions options = new SlotOptions();
        options.setSource(SlotOptions.SourceType.ENUM);
        Map<String, Object> enumMapping = new LinkedHashMap<>();
        enumMapping.put("事假", 1);
        enumMapping.put("年假", 2);
        options.setEnumMapping(enumMapping);
        slot.setOptions(options);

        List<EnrichedSlot> result = enricherService.enrichSlots(List.of(slot), "oa", "user1");

        assertEquals(1, result.size());
        assertEquals(2, result.get(0).getOptions().size());
        assertNull(result.get(0).getOptionsError());
    }

    @Test
    void shouldResolveToolBackedOptionsAndPopulateCache() {
        SlotDefinition slot = toolBackedSlot("check_uids", "gougu_oa.approver_candidates");
        List<SlotOption> resolved = List.of(new SlotOption("人事领导", 4, "直属上级"));

        when(optionCachePort.get(anyString())).thenReturn(Optional.empty());
        when(toolOptionResolver.resolve(eq(slot), eq("oa"), eq("user1"))).thenReturn(resolved);

        List<EnrichedSlot> result = enricherService.enrichSlots(List.of(slot), "oa", "user1");

        assertEquals(1, result.size());
        assertEquals(1, result.get(0).getOptions().size());
        assertEquals("人事领导", result.get(0).getOptions().get(0).getLabel());
        assertNull(result.get(0).getOptionsError());
        verify(toolOptionResolver).resolve(eq(slot), eq("oa"), eq("user1"));
        verify(optionCachePort).put(anyString(), anyString(), eq(java.time.Duration.ofMinutes(10)));
    }

    @Test
    void shouldReuseCachedToolBackedOptions() throws Exception {
        SlotDefinition slot = toolBackedSlot("check_uids", "gougu_oa.approver_candidates");
        String cachedJson = objectMapper.writeValueAsString(List.of(new SlotOption("人事领导", 4, "直属上级")));

        when(optionCachePort.get(anyString())).thenReturn(Optional.of(cachedJson));

        List<EnrichedSlot> result = enricherService.enrichSlots(List.of(slot), "oa", "user1");

        assertEquals(1, result.size());
        assertEquals(1, result.get(0).getOptions().size());
        assertEquals("人事领导", result.get(0).getOptions().get(0).getLabel());
        verify(toolOptionResolver, never()).resolve(org.mockito.ArgumentMatchers.any(), anyString(), anyString());
    }

    @Test
    void shouldReturnEmptyForNullSlots() {
        assertTrue(enricherService.enrichSlots(null, "oa", "user1").isEmpty());
        assertTrue(enricherService.enrichSlots(Collections.emptyList(), "oa", "user1").isEmpty());
    }

    @Test
    void shouldEnrichSlotWithoutOptions() {
        SlotDefinition slot = new SlotDefinition();
        slot.setName("reason");
        slot.setType("string");

        List<EnrichedSlot> result = enricherService.enrichSlots(List.of(slot), "oa", "user1");

        assertEquals(1, result.size());
        assertNull(result.get(0).getOptions());
    }

    private SlotDefinition toolBackedSlot(String slotName, String toolCode) {
        SlotDefinition slot = new SlotDefinition();
        slot.setName(slotName);
        slot.setType("string");

        SlotOptions options = new SlotOptions();
        options.setSource(SlotOptions.SourceType.TOOL);
        ToolOptionResolverConfig toolConfig = new ToolOptionResolverConfig();
        toolConfig.setToolCode(toolCode);
        toolConfig.setResultPath("data");
        toolConfig.setLabelField("name");
        toolConfig.setValueField("id");
        options.setToolConfig(toolConfig);
        slot.setOptions(options);
        return slot;
    }
}
