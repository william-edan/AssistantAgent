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

import com.alibaba.assistant.agent.controlplane.toolregistry.ToolMeta;
import com.alibaba.assistant.agent.runtime.agent.AssistantStateKeys;
import com.alibaba.assistant.agent.slot.SlotEnricherService;
import com.alibaba.assistant.agent.slot.SlotSchemaParser;
import com.alibaba.assistant.agent.slot.computed.ComputedFieldProcessor;
import com.alibaba.assistant.agent.slot.form.FormDisplayConfigService;
import com.alibaba.assistant.agent.slot.form.FormSummary;
import com.alibaba.assistant.agent.slot.model.EnrichedSlot;
import com.alibaba.assistant.agent.slot.model.SlotAskMode;
import com.alibaba.assistant.agent.slot.model.SlotAutoSelect;
import com.alibaba.assistant.agent.slot.model.SlotDefinition;
import com.alibaba.assistant.agent.slot.model.SlotOption;
import com.alibaba.assistant.agent.slot.model.SlotOptions;
import com.alibaba.assistant.agent.slot.model.SlotValue;
import com.alibaba.assistant.agent.slot.model.ToolMetaSnapshot;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.agent.tools.ToolContextConstants;
import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.stream.Collectors;

/**
 * React phase confirmation form tool.
 * Builds confirm payload after auto-fill, computed-field refresh and display value rendering.
 *
 * @author Assistant Agent Team
 * @since 1.0.0
 */
@Component
@Profile("migration")
public class SlotConfirmTool implements BiFunction<SlotConfirmTool.Request, ToolContext, SlotConfirmTool.Response> {

    private static final Logger logger = LoggerFactory.getLogger(SlotConfirmTool.class);

    private final SlotSchemaParser slotSchemaParser;
    private final SlotEnricherService slotEnricherService;
    private final ComputedFieldProcessor computedFieldProcessor;
    private final FormDisplayConfigService formDisplayConfigService;
    private final ObjectMapper objectMapper;

    public SlotConfirmTool(SlotSchemaParser slotSchemaParser,
                           SlotEnricherService slotEnricherService,
                           ComputedFieldProcessor computedFieldProcessor,
                           FormDisplayConfigService formDisplayConfigService,
                           ObjectMapper objectMapper) {
        this.slotSchemaParser = slotSchemaParser;
        this.slotEnricherService = slotEnricherService;
        this.computedFieldProcessor = computedFieldProcessor;
        this.formDisplayConfigService = formDisplayConfigService;
        this.objectMapper = objectMapper;
    }

    public static ToolCallback createToolCallback(SlotConfirmTool tool) {
        return FunctionToolCallback.builder("slot_confirm", tool)
                .description("Build confirmation form payload from collected slots.")
                .inputType(Request.class)
                .build();
    }

    @Override
    public Response apply(Request request, ToolContext toolContext) {
        Request effectiveRequest = request != null ? request : new Request();

        try {
            OverAllState state = getState(toolContext);
            ToolMetaSnapshot snapshot = resolveToolMetaSnapshot(effectiveRequest, state);
            if (snapshot == null) {
                return Response.error("Missing tool meta snapshot or slot schema");
            }

            List<SlotDefinition> slotDefinitions = resolveSlotDefinitions(state, snapshot);
            if (slotDefinitions.isEmpty()) {
                return Response.error("No slot definitions found for confirmation");
            }

            Map<String, SlotValue> collectedSlots = readCollectedSlots(state);
            mergeRequestCollected(effectiveRequest.collectedSlots, collectedSlots);

            // Priority: state > snapshot > LLM args (LLM often hallucinates identity values)
            String systemCode = firstNonEmpty(
                    readStringState(state, AssistantStateKeys.SYSTEM_CODE),
                    snapshot.getSystemCode(),
                    effectiveRequest.systemCode);
            String assistantUid = firstNonEmpty(
                    readStringState(state, AssistantStateKeys.ASSISTANT_UID),
                    effectiveRequest.assistantUid);

            List<EnrichedSlot> enrichedSlots = resolveEnrichedSlots(state, slotDefinitions, systemCode, assistantUid);
            applyAutoSelect(slotDefinitions, enrichedSlots, collectedSlots);
            computedFieldProcessor.processComputedFields(slotDefinitions, collectedSlots);
            applyDisplayValues(slotDefinitions, enrichedSlots, collectedSlots);

            FormSummary summary = formDisplayConfigService.buildSummary(slotDefinitions, collectedSlots);
            ConfirmFormPayload payload = new ConfirmFormPayload();
            payload.toolCode = snapshot.getToolCode();
            payload.collected = mapCollected(collectedSlots);
            payload.formSummary = summary;
            payload.enrichedSlots = enrichedSlots;

            persistState(state, snapshot, slotDefinitions, collectedSlots, enrichedSlots);
            return Response.confirming(payload);
        }
        catch (Exception e) {
            logger.error("SlotConfirmTool#apply - execution failed", e);
            return Response.error("slot_confirm failed: " + e.getMessage());
        }
    }

    private OverAllState getState(ToolContext toolContext) {
        if (toolContext == null || toolContext.getContext() == null) {
            return null;
        }
        Object stateObject = toolContext.getContext().get(ToolContextConstants.AGENT_STATE_CONTEXT_KEY);
        return stateObject instanceof OverAllState ? (OverAllState) stateObject : null;
    }

    private ToolMetaSnapshot resolveToolMetaSnapshot(Request request, OverAllState state) {
        if (state != null) {
            Object raw = state.value(AssistantStateKeys.MATCHED_TOOL_META, Object.class).orElse(null);
            if (raw != null) {
                if (raw instanceof ToolMetaSnapshot snapshot) {
                    return snapshot;
                }
                if (raw instanceof ToolMeta toolMeta) {
                    return convertFromToolMeta(toolMeta);
                }
                try {
                    return objectMapper.convertValue(raw, ToolMetaSnapshot.class);
                }
                catch (Exception e) {
                    logger.warn("SlotConfirmTool#resolveToolMetaSnapshot - cannot convert state meta, error={}",
                            e.getMessage());
                }
            }
        }

        if (StringUtils.hasText(request.slotSchema) || StringUtils.hasText(request.requestSchema)) {
            ToolMetaSnapshot snapshot = new ToolMetaSnapshot();
            snapshot.setToolCode(request.toolCode);
            snapshot.setSlotSchema(request.slotSchema);
            snapshot.setRequestSchema(request.requestSchema);
            snapshot.setBehaviorConfig(request.behaviorConfig);
            snapshot.setSystemCode(request.systemCode);
            return snapshot;
        }
        return null;
    }

    private ToolMetaSnapshot convertFromToolMeta(ToolMeta toolMeta) {
        ToolMetaSnapshot snapshot = new ToolMetaSnapshot();
        snapshot.setToolCode(toolMeta.getToolCode());
        snapshot.setSlotSchema(toolMeta.getParameterSchema());
        snapshot.setRequestSchema(null);
        snapshot.setExecutionPlan(toolMeta.getExecutionPlan());
        snapshot.setBehaviorConfig(toolMeta.getInteractionPolicy());
        snapshot.setSystemCode(toolMeta.getSystemCode());
        return snapshot;
    }

    @SuppressWarnings("unchecked")
    private List<SlotDefinition> resolveSlotDefinitions(OverAllState state, ToolMetaSnapshot snapshot) {
        if (state != null) {
            Object raw = state.value(AssistantStateKeys.SLOT_DEFINITIONS, Object.class).orElse(null);
            if (raw instanceof List<?> rawList && !rawList.isEmpty()) {
                List<SlotDefinition> definitions = new ArrayList<>();
                for (Object item : rawList) {
                    if (item instanceof SlotDefinition slotDefinition) {
                        definitions.add(slotDefinition);
                    }
                    else {
                        try {
                            definitions.add(objectMapper.convertValue(item, SlotDefinition.class));
                        }
                        catch (Exception e) {
                            logger.warn("SlotConfirmTool#resolveSlotDefinitions - convert failed, error={}",
                                    e.getMessage());
                        }
                    }
                }
                if (!definitions.isEmpty()) {
                    return definitions;
                }
            }
        }
        return slotSchemaParser.parse(snapshot);
    }

    @SuppressWarnings("unchecked")
    private Map<String, SlotValue> readCollectedSlots(OverAllState state) {
        Map<String, SlotValue> result = new LinkedHashMap<>();
        if (state == null) {
            return result;
        }

        Map<String, Object> rawMap = state.value(AssistantStateKeys.COLLECTED_SLOTS, Map.class).orElse(null);
        if (rawMap == null || rawMap.isEmpty()) {
            return result;
        }

        for (Map.Entry<String, Object> entry : rawMap.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof SlotValue slotValue) {
                result.put(entry.getKey(), slotValue);
            }
            else {
                try {
                    result.put(entry.getKey(), objectMapper.convertValue(value, SlotValue.class));
                }
                catch (Exception e) {
                    logger.warn("SlotConfirmTool#readCollectedSlots - convert failed, slot={}, error={}",
                            entry.getKey(), e.getMessage());
                }
            }
        }
        return result;
    }

    private void mergeRequestCollected(Map<String, Object> requestedSlots, Map<String, SlotValue> collectedSlots) {
        if (requestedSlots == null || requestedSlots.isEmpty()) {
            return;
        }
        for (Map.Entry<String, Object> entry : requestedSlots.entrySet()) {
            String slotName = entry.getKey();
            Object value = entry.getValue();
            if (!StringUtils.hasText(slotName) || value == null) {
                continue;
            }

            if (value instanceof SlotValue slotValue) {
                collectedSlots.put(slotName, slotValue);
                continue;
            }

            if (value instanceof Map<?, ?>) {
                try {
                    SlotValue converted = objectMapper.convertValue(value, SlotValue.class);
                    if (!StringUtils.hasText(converted.getSlotName())) {
                        converted.setSlotName(slotName);
                    }
                    collectedSlots.put(slotName, converted);
                    continue;
                }
                catch (Exception e) {
                    logger.warn("SlotConfirmTool#mergeRequestCollected - structured convert failed, slot={}, error={}",
                            slotName, e.getMessage());
                }
            }

            SlotValue slotValue = SlotValue.fromUser(slotName, value);
            slotValue.setDisplayValue(String.valueOf(value));
            collectedSlots.put(slotName, slotValue);
        }
    }

    private List<EnrichedSlot> resolveEnrichedSlots(OverAllState state, List<SlotDefinition> slotDefinitions,
                                                    String systemCode, String assistantUid) {
        List<EnrichedSlot> fromState = readEnrichedSlots(state);
        if (!fromState.isEmpty()) {
            return fromState;
        }
        if (StringUtils.hasText(systemCode) && StringUtils.hasText(assistantUid)) {
            try {
                return slotEnricherService.enrichSlots(slotDefinitions, systemCode, assistantUid);
            }
            catch (Exception e) {
                logger.warn("SlotConfirmTool#resolveEnrichedSlots - enrichment failed, error={}", e.getMessage());
            }
        }
        return slotDefinitions.stream().map(EnrichedSlot::new).collect(Collectors.toList());
    }

    @SuppressWarnings("unchecked")
    private List<EnrichedSlot> readEnrichedSlots(OverAllState state) {
        if (state == null) {
            return Collections.emptyList();
        }
        List<Object> rawList = state.value(AssistantStateKeys.ENRICHED_SLOTS, List.class).orElse(null);
        if (rawList == null || rawList.isEmpty()) {
            return Collections.emptyList();
        }

        List<EnrichedSlot> result = new ArrayList<>();
        for (Object item : rawList) {
            if (item instanceof EnrichedSlot enrichedSlot) {
                result.add(enrichedSlot);
            }
            else {
                try {
                    result.add(objectMapper.convertValue(item, EnrichedSlot.class));
                }
                catch (Exception e) {
                    logger.warn("SlotConfirmTool#readEnrichedSlots - convert failed, error={}", e.getMessage());
                }
            }
        }
        return result;
    }

    private void applyAutoSelect(List<SlotDefinition> slotDefinitions, List<EnrichedSlot> enrichedSlots,
                                 Map<String, SlotValue> collectedSlots) {
        Map<String, EnrichedSlot> enrichedByName = enrichedSlots.stream()
                .filter(slot -> slot.getDefinition() != null && StringUtils.hasText(slot.getDefinition().getName()))
                .collect(Collectors.toMap(slot -> slot.getDefinition().getName(), slot -> slot, (a, b) -> a));

        for (SlotDefinition definition : slotDefinitions) {
            if (definition == null || !StringUtils.hasText(definition.getName())) {
                continue;
            }
            if (collectedSlots.containsKey(definition.getName())) {
                continue;
            }
            if (definition.getAskMode() != SlotAskMode.AUTO) {
                continue;
            }

            EnrichedSlot enrichedSlot = enrichedByName.get(definition.getName());
            if (enrichedSlot == null || enrichedSlot.getOptions() == null || enrichedSlot.getOptions().isEmpty()) {
                continue;
            }

            SlotOption option = chooseAutoSelectedOption(definition, enrichedSlot.getOptions());
            if (option == null) {
                continue;
            }

            SlotValue slotValue = SlotValue.resolved(
                    definition.getName(),
                    option.getValue(),
                    option.getValue(),
                    option.getLabel());
            slotValue.setSource(SlotValue.Source.AUTO);
            collectedSlots.put(definition.getName(), slotValue);
        }
    }

    private SlotOption chooseAutoSelectedOption(SlotDefinition definition, List<SlotOption> options) {
        if (options == null || options.isEmpty()) {
            return null;
        }

        SlotAutoSelect slotAutoSelect = definition.getAutoSelect();
        if (slotAutoSelect != null) {
            if (slotAutoSelect.getStrategy() == SlotAutoSelect.Strategy.NONE) {
                return null;
            }
            if (slotAutoSelect.getStrategy() == SlotAutoSelect.Strategy.VALUE
                    && StringUtils.hasText(slotAutoSelect.getValue())) {
                for (SlotOption option : options) {
                    if (String.valueOf(option.getValue()).equals(slotAutoSelect.getValue())) {
                        return option;
                    }
                }
            }
            return options.get(0);
        }

        SlotOptions optionsConfig = definition.getOptions();
        if (optionsConfig == null || optionsConfig.getAutoSelect() == null) {
            return options.get(0);
        }

        SlotOptions.AutoSelectConfig autoSelectConfig = optionsConfig.getAutoSelect();
        if (!autoSelectConfig.isEnabled()) {
            return null;
        }
        return options.get(0);
    }

    private void applyDisplayValues(List<SlotDefinition> slotDefinitions, List<EnrichedSlot> enrichedSlots,
                                    Map<String, SlotValue> collectedSlots) {
        Map<String, List<SlotOption>> optionsByName = buildOptionsIndex(slotDefinitions, enrichedSlots);
        for (Map.Entry<String, SlotValue> entry : collectedSlots.entrySet()) {
            SlotValue slotValue = entry.getValue();
            if (slotValue == null || slotValue.getResolvedValue() == null) {
                continue;
            }

            String currentDisplay = slotValue.getDisplayValue();
            String resolvedAsText = String.valueOf(slotValue.getResolvedValue());
            if (StringUtils.hasText(currentDisplay) && !resolvedAsText.equals(currentDisplay)) {
                continue;
            }

            List<SlotOption> options = optionsByName.get(entry.getKey());
            if (options == null || options.isEmpty()) {
                continue;
            }

            String resolvedDisplay = resolveDisplayValue(slotValue.getResolvedValue(), options);
            if (StringUtils.hasText(resolvedDisplay)) {
                slotValue.setDisplayValue(resolvedDisplay);
            }
        }
    }

    private Map<String, List<SlotOption>> buildOptionsIndex(List<SlotDefinition> slotDefinitions,
                                                            List<EnrichedSlot> enrichedSlots) {
        Map<String, List<SlotOption>> optionsByName = new HashMap<>();
        for (SlotDefinition definition : slotDefinitions) {
            if (definition == null || !StringUtils.hasText(definition.getName())) {
                continue;
            }
            List<SlotOption> staticOptions = extractStaticOptions(definition);
            if (!staticOptions.isEmpty()) {
                optionsByName.put(definition.getName(), staticOptions);
            }
        }
        for (EnrichedSlot enrichedSlot : enrichedSlots) {
            if (enrichedSlot == null || enrichedSlot.getDefinition() == null) {
                continue;
            }
            String slotName = enrichedSlot.getDefinition().getName();
            if (!StringUtils.hasText(slotName)
                    || enrichedSlot.getOptions() == null
                    || enrichedSlot.getOptions().isEmpty()) {
                continue;
            }
            optionsByName.put(slotName, enrichedSlot.getOptions());
        }
        return optionsByName;
    }

    private List<SlotOption> extractStaticOptions(SlotDefinition definition) {
        if (definition == null || definition.getOptions() == null) {
            return Collections.emptyList();
        }
        SlotOptions options = definition.getOptions();
        List<SlotOption> result = new ArrayList<>();
        if (options.getValues() != null) {
            for (SlotOptions.OptionValue optionValue : options.getValues()) {
                result.add(new SlotOption(optionValue.getLabel(), optionValue.getValue()));
            }
        }
        if (result.isEmpty() && options.getEnumMapping() != null && !options.getEnumMapping().isEmpty()) {
            for (Map.Entry<String, Object> entry : options.getEnumMapping().entrySet()) {
                result.add(new SlotOption(entry.getKey(), entry.getValue()));
            }
        }
        return result;
    }

    private String resolveDisplayValue(Object resolvedValue, List<SlotOption> options) {
        SlotOption directMatch = findMatch(options, resolvedValue);
        if (directMatch != null) {
            return directMatch.getLabel();
        }

        if (resolvedValue instanceof Collection<?> values) {
            List<String> labels = values.stream()
                    .map(value -> Optional.ofNullable(findMatch(options, value))
                            .map(SlotOption::getLabel)
                            .orElse(String.valueOf(value)))
                    .collect(Collectors.toList());
            return String.join(", ", labels);
        }

        if (resolvedValue != null && resolvedValue.getClass().isArray()) {
            int length = Array.getLength(resolvedValue);
            List<String> labels = new ArrayList<>(length);
            for (int i = 0; i < length; i++) {
                Object value = Array.get(resolvedValue, i);
                SlotOption matched = findMatch(options, value);
                labels.add(matched != null ? matched.getLabel() : String.valueOf(value));
            }
            return String.join(", ", labels);
        }

        if (resolvedValue instanceof String stringValue && stringValue.contains(",")) {
            String[] parts = stringValue.split(",");
            List<String> labels = new ArrayList<>(parts.length);
            for (String part : parts) {
                String trimmed = part.trim();
                SlotOption matched = findMatch(options, trimmed);
                labels.add(matched != null ? matched.getLabel() : trimmed);
            }
            return String.join(", ", labels);
        }

        return null;
    }

    private SlotOption findMatch(List<SlotOption> options, Object value) {
        if (value == null) {
            return null;
        }
        String target = String.valueOf(value);
        for (SlotOption option : options) {
            if (option == null || option.getValue() == null) {
                continue;
            }
            if (target.equals(String.valueOf(option.getValue()))) {
                return option;
            }
        }
        return null;
    }

    private Map<String, Object> mapCollected(Map<String, SlotValue> collectedSlots) {
        Map<String, Object> mapped = new LinkedHashMap<>();
        for (Map.Entry<String, SlotValue> entry : collectedSlots.entrySet()) {
            SlotValue slotValue = entry.getValue();
            mapped.put(entry.getKey(), slotValue != null ? slotValue.getResolvedValue() : null);
        }
        return mapped;
    }

    private void persistState(OverAllState state, ToolMetaSnapshot snapshot, List<SlotDefinition> slotDefinitions,
                              Map<String, SlotValue> collectedSlots, List<EnrichedSlot> enrichedSlots) {
        if (state == null) {
            return;
        }

        Map<String, Object> updates = new HashMap<>();
        updates.put(AssistantStateKeys.COLLECTED_SLOTS, collectedSlots);
        updates.put(AssistantStateKeys.ENRICHED_SLOTS, enrichedSlots);
        updates.put(AssistantStateKeys.SLOT_DEFINITIONS, slotDefinitions);
        updates.put(AssistantStateKeys.CONVERSATION_PHASE, "CONFIRMING");
        updates.put(AssistantStateKeys.EXECUTION_CONFIRM_GRANTED, false);
        updates.put(AssistantStateKeys.EXECUTION_CONFIRM_TOOL_NAME, null);
        updates.put(AssistantStateKeys.EXECUTION_CONFIRM_USER_INPUT, null);
        if (snapshot != null) {
            updates.put(AssistantStateKeys.MATCHED_TOOL_META, snapshot);
        }
        state.updateState(updates);
    }

    private String readStringState(OverAllState state, String key) {
        if (state == null) {
            return null;
        }
        return state.value(key, String.class).orElse(null);
    }

    private String firstNonEmpty(String... values) {
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value;
            }
        }
        return null;
    }

    /**
     * slot_confirm request.
     */
    public static class Request {

        @JsonPropertyDescription("Target tool code")
        public String toolCode;

        @JsonPropertyDescription("Optional collected slots override")
        @JsonAlias({"collected", "collected_slots"})
        public Map<String, Object> collectedSlots = new HashMap<>();

        @JsonPropertyDescription("Optional slot_schema JSON, used when state has no matched tool meta")
        public String slotSchema;

        @JsonPropertyDescription("Optional request_schema JSON fallback")
        public String requestSchema;

        @JsonPropertyDescription("Optional interaction/behavior configuration JSON")
        public String behaviorConfig;

        @JsonPropertyDescription("Optional system code override")
        public String systemCode;

        @JsonPropertyDescription("Optional assistant uid override")
        public String assistantUid;
    }

    /**
     * slot_confirm response.
     */
    public static class Response {
        public String status;
        public String phase;
        public String message;
        public ConfirmFormPayload confirmForm;

        public static Response confirming(ConfirmFormPayload payload) {
            Response response = new Response();
            response.status = "CONFIRMING";
            response.phase = "CONFIRMING";
            response.message = "Confirmation form generated.";
            response.confirmForm = payload;
            return response;
        }

        public static Response error(String message) {
            Response response = new Response();
            response.status = "ERROR";
            response.phase = "CONFIRMING";
            response.message = message;
            return response;
        }
    }

    /**
     * Confirm form payload.
     */
    public static class ConfirmFormPayload {
        public String toolCode;
        public Map<String, Object> collected;
        public FormSummary formSummary;
        public List<EnrichedSlot> enrichedSlots;
    }
}
