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
import com.alibaba.assistant.agent.slot.port.OptionCachePort;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Service for enriching slots with runtime option lists.
 *
 * <p>The canonical path is query-tool backed resolution. Inline API definitions
 * are no longer executed by the runtime and must be migrated to reusable query tools.
 * Enum mappings remain supported as a local fallback.</p>
 *
 * @author Assistant Agent Team
 * @since 1.0.0
 */
@Service
public class SlotEnricherService {

    private static final Logger logger = LoggerFactory.getLogger(SlotEnricherService.class);

    private static final String CACHE_KEY_PREFIX = "assistant:slot:options:";

    private static final Duration CACHE_TTL = Duration.ofMinutes(10);

    private final ObjectMapper objectMapper;

    private final OptionCachePort optionCachePort;

    private final ToolBackedSlotOptionResolver toolOptionResolver;

    public SlotEnricherService(
            ObjectMapper objectMapper,
            OptionCachePort optionCachePort,
            ToolBackedSlotOptionResolver toolOptionResolver) {
        this.objectMapper = objectMapper;
        this.optionCachePort = optionCachePort;
        this.toolOptionResolver = toolOptionResolver;
    }

    /**
     * Enrich slots with dynamic options from reusable query tools.
     *
     * @param slots list of slot definitions to enrich
     * @param systemCode system code for API calls
     * @param userId user ID for authentication
     * @return list of enriched slots with loaded options
     */
    public List<EnrichedSlot> enrichSlots(List<SlotDefinition> slots, String systemCode, String userId) {
        if (slots == null || slots.isEmpty()) {
            return Collections.emptyList();
        }

        logger.debug("SlotEnricherService#enrichSlots - slotsCount={}, systemCode={}, userId={}",
                slots.size(), systemCode, userId);

        List<EnrichedSlot> enrichedSlots = new ArrayList<>();
        for (SlotDefinition slot : slots) {
            EnrichedSlot enriched = new EnrichedSlot(slot);
            SlotOptions optionsConfig = slot.getOptions();
            if (optionsConfig == null) {
                enrichedSlots.add(enriched);
                continue;
            }

            try {
                if (optionsConfig.getSource() == SlotOptions.SourceType.TOOL && optionsConfig.getToolConfig() != null) {
                    enriched.setOptions(loadOptionsFromTool(slot, systemCode, userId));
                }
                else if (optionsConfig.getSource() == SlotOptions.SourceType.API
                        || optionsConfig.getSource() == SlotOptions.SourceType.SEARCH
                        || optionsConfig.getSource() == SlotOptions.SourceType.API_SEARCH) {
                    throw new IllegalStateException("slot_option_source_unsupported: use TOOL instead of "
                            + optionsConfig.getSource());
                }
                else if (optionsConfig.getEnumMapping() != null && !optionsConfig.getEnumMapping().isEmpty()) {
                    enriched.setOptions(convertEnumMappingToOptions(optionsConfig.getEnumMapping()));
                }
            }
            catch (Exception ex) {
                logger.warn("SlotEnricherService#enrichSlots - option resolution failed, slot={}, error={}",
                        slot.getName(), ex.getMessage());
                enriched.setOptionsError(ex.getMessage());
                if (optionsConfig.getEnumMapping() != null && !optionsConfig.getEnumMapping().isEmpty()) {
                    enriched.setOptions(convertEnumMappingToOptions(optionsConfig.getEnumMapping()));
                }
            }

            enrichedSlots.add(enriched);
        }
        return enrichedSlots;
    }

    private List<SlotOption> loadOptionsFromTool(SlotDefinition slot, String systemCode, String userId) throws Exception {
        if (slot == null || slot.getOptions() == null || slot.getOptions().getToolConfig() == null) {
            return List.of();
        }
        String toolCode = slot.getOptions().getToolConfig().getToolCode();
        if (toolCode == null || toolCode.isBlank()) {
            throw new IllegalStateException("tool_option_config_missing_tool_code");
        }

        String cacheKey = buildCacheKey(systemCode, userId, slot.getName(), toolCode);
        Optional<String> cachedJson = optionCachePort.get(cacheKey);
        if (cachedJson.isPresent()) {
            List<SlotOption> cached = objectMapper.readValue(cachedJson.get(), new TypeReference<List<SlotOption>>() {
            });
            logger.debug("SlotEnricherService#loadOptionsFromTool - cache hit, slot={}, toolCode={}, count={}",
                    slot.getName(), toolCode, cached.size());
            return cached;
        }

        List<SlotOption> resolved = toolOptionResolver.resolve(slot, systemCode, userId);
        try {
            optionCachePort.put(cacheKey, objectMapper.writeValueAsString(resolved), CACHE_TTL);
        }
        catch (Exception ex) {
            logger.warn("SlotEnricherService#loadOptionsFromTool - cache write failed, slot={}, error={}",
                    slot.getName(), ex.getMessage());
        }
        return resolved;
    }

    private List<SlotOption> convertEnumMappingToOptions(Map<String, Object> enumMapping) {
        if (enumMapping == null || enumMapping.isEmpty()) {
            return List.of();
        }
        return enumMapping.entrySet().stream()
                .map(entry -> new SlotOption(entry.getKey(), entry.getValue()))
                .toList();
    }

    private String buildCacheKey(String systemCode, String userId, String slotName, String toolCode) {
        String normalizedSystemCode = systemCode != null ? systemCode.trim() : "default";
        String normalizedUserId = userId != null ? userId.trim() : "anonymous";
        String normalizedSlotName = slotName != null ? slotName.trim() : "slot";
        String normalizedToolCode = toolCode.replaceAll("[^a-zA-Z0-9._-]", "_");
        return CACHE_KEY_PREFIX + normalizedSystemCode + ":" + normalizedUserId + ":" + normalizedSlotName + ":" + normalizedToolCode;
    }

}


