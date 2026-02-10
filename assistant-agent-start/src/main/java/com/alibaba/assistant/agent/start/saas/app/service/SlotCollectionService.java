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
package com.alibaba.assistant.agent.start.saas.app.service;

import com.alibaba.assistant.agent.start.saas.app.model.SlotCollectionResult;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Slot collector for multi-turn conversation.
 *
 * @author Assistant Agent Team
 * @since 1.0.0
 */
@Service
public class SlotCollectionService {

    private final ObjectMapper objectMapper;

    public SlotCollectionService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Merge session snapshot and current input, then find missing required slots.
     *
     * @param slotSchemaJson slot schema json
     * @param inputSchemaJson input schema json
     * @param sessionSnapshot snapshot from previous rounds
     * @param currentInput current input
     * @return collection result
     */
    public SlotCollectionResult collect(String slotSchemaJson, String inputSchemaJson, Map<String, Object> sessionSnapshot,
            Map<String, Object> currentInput) {
        Map<String, Object> merged = new HashMap<>();
        if (sessionSnapshot != null) {
            merged.putAll(sessionSnapshot);
        }
        if (currentInput != null) {
            merged.putAll(currentInput);
        }

        List<String> requiredFields = resolveRequiredFields(slotSchemaJson, inputSchemaJson);
        List<String> missing = new ArrayList<>();
        for (String field : requiredFields) {
            Object value = merged.get(field);
            if (value == null) {
                missing.add(field);
                continue;
            }
            if (value instanceof String && ((String) value).isBlank()) {
                missing.add(field);
            }
        }

        SlotCollectionResult result = new SlotCollectionResult();
        result.setMergedInput(merged);
        result.setMissingSlots(missing);
        return result;
    }

    private List<String> resolveRequiredFields(String slotSchemaJson, String inputSchemaJson) {
        List<String> requiredFromSlot = parseRequired(slotSchemaJson);
        if (!requiredFromSlot.isEmpty()) {
            return requiredFromSlot;
        }
        return parseRequired(inputSchemaJson);
    }

    private List<String> parseRequired(String schemaJson) {
        List<String> required = new ArrayList<>();
        if (schemaJson == null || schemaJson.isBlank()) {
            return required;
        }
        try {
            JsonNode node = objectMapper.readTree(schemaJson);
            JsonNode requiredNode = node.get("required");
            if (requiredNode == null || !requiredNode.isArray()) {
                return required;
            }
            for (JsonNode item : requiredNode) {
                String field = item.asText();
                if (!field.isBlank()) {
                    required.add(field);
                }
            }
            return required;
        }
        catch (JsonProcessingException ex) {
            return required;
        }
    }
}
