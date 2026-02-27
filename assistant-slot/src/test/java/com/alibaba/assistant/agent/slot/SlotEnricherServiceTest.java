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

import com.alibaba.assistant.agent.controlplane.identity.TokenBroker;
import com.alibaba.assistant.agent.controlplane.identity.TokenLease;
import com.alibaba.assistant.agent.slot.model.*;
import com.alibaba.assistant.agent.slot.port.OptionCachePort;
import com.alibaba.assistant.agent.slot.port.SystemAccessProfilePort;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SlotEnricherServiceTest {

	@Mock
	private SystemAccessProfilePort systemAccessProfilePort;

	@Mock
	private TokenBroker tokenBroker;

	@Mock
	private OptionCachePort optionCachePort;

	private SlotEnricherService enricherService;

	@BeforeEach
	void setUp() {
		enricherService = new SlotEnricherService(systemAccessProfilePort, tokenBroker, new ObjectMapper(),
				optionCachePort);
	}

	@Test
	void shouldFallbackToEnumMappingWhenApiFails() {
		// Given: slot with API source and enumMapping fallback
		SlotDefinition slot = new SlotDefinition();
		slot.setName("leave_type");
		slot.setType("enum");

		SlotOptions options = new SlotOptions();
		options.setSource(SlotOptions.SourceType.API);

		SlotOptions.ApiConfig apiConfig = new SlotOptions.ApiConfig();
		apiConfig.setEndpoint("/api/leave-types");
		apiConfig.setLabelField("name");
		apiConfig.setValueField("id");
		options.setApiConfig(apiConfig);

		Map<String, Object> enumMapping = new LinkedHashMap<>();
		enumMapping.put("事假", 1);
		enumMapping.put("年假", 2);
		enumMapping.put("调休假", 3);
		enumMapping.put("病假", 4);
		options.setEnumMapping(enumMapping);

		slot.setOptions(options);

		// Given: system is configured but API call will fail (no base URL)
		when(systemAccessProfilePort.getBaseUrl("hr")).thenReturn(null);
		when(optionCachePort.get(anyString())).thenReturn(Optional.empty());

		// When
		List<EnrichedSlot> result = enricherService.enrichSlots(List.of(slot), "hr", "user1");

		// Then: fallback to enumMapping
		assertEquals(1, result.size());
		EnrichedSlot enriched = result.get(0);
		assertNotNull(enriched.getOptions());
		assertEquals(4, enriched.getOptions().size());

		// Verify enumMapping values are used
		List<SlotOption> optionList = enriched.getOptions();
		assertEquals("事假", optionList.get(0).getLabel());
		assertEquals(1, optionList.get(0).getValue());
		assertEquals("病假", optionList.get(3).getLabel());
		assertEquals(4, optionList.get(3).getValue());

		// Error should be recorded
		assertNotNull(enriched.getOptionsError());
	}

	@Test
	void shouldUseEnumMappingDirectlyForNonApiSource() {
		// Given: slot with ENUM source
		SlotDefinition slot = new SlotDefinition();
		slot.setName("leave_type");
		slot.setType("enum");

		SlotOptions options = new SlotOptions();
		Map<String, Object> enumMapping = new LinkedHashMap<>();
		enumMapping.put("事假", 1);
		enumMapping.put("年假", 2);
		options.setEnumMapping(enumMapping);

		slot.setOptions(options);

		// When
		List<EnrichedSlot> result = enricherService.enrichSlots(List.of(slot), "hr", "user1");

		// Then: enum options loaded directly
		assertEquals(1, result.size());
		EnrichedSlot enriched = result.get(0);
		assertNotNull(enriched.getOptions());
		assertEquals(2, enriched.getOptions().size());
		assertNull(enriched.getOptionsError());
	}

	@Test
	void shouldReturnEmptyForNullSlots() {
		assertTrue(enricherService.enrichSlots(null, "hr", "user1").isEmpty());
		assertTrue(enricherService.enrichSlots(Collections.emptyList(), "hr", "user1").isEmpty());
	}

	@Test
	void shouldEnrichSlotWithoutOptions() {
		SlotDefinition slot = new SlotDefinition();
		slot.setName("reason");
		slot.setType("string");

		List<EnrichedSlot> result = enricherService.enrichSlots(List.of(slot), "hr", "user1");

		assertEquals(1, result.size());
		assertNull(result.get(0).getOptions());
	}

}
