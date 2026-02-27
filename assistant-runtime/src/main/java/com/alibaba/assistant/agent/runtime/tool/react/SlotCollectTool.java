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
import com.alibaba.assistant.agent.controlplane.toolregistry.ToolMetaService;
import com.alibaba.assistant.agent.runtime.agent.AssistantStateKeys;
import com.alibaba.assistant.agent.slot.SlotCollectorService;
import com.alibaba.assistant.agent.slot.SlotEnricherService;
import com.alibaba.assistant.agent.slot.SlotSchemaParser;
import com.alibaba.assistant.agent.slot.computed.ComputedFieldProcessor;
import com.alibaba.assistant.agent.slot.model.CollectBehavior;
import com.alibaba.assistant.agent.slot.model.EnrichedSlot;
import com.alibaba.assistant.agent.slot.model.SlotAskMode;
import com.alibaba.assistant.agent.slot.model.SlotAutoSelect;
import com.alibaba.assistant.agent.slot.model.SlotCollectStatus;
import com.alibaba.assistant.agent.slot.model.SlotDefinition;
import com.alibaba.assistant.agent.slot.model.SlotOption;
import com.alibaba.assistant.agent.slot.model.SlotOptions;
import com.alibaba.assistant.agent.slot.model.SlotValue;
import com.alibaba.assistant.agent.slot.model.ToolMetaSnapshot;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.agent.tools.ToolContextConstants;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.stream.Collectors;

/**
 * React phase slot collection tool.
 * Implements the 6-step workflow: parse, merge, enrich, auto-fill, compute, and status check.
 *
 * @author Assistant Agent Team
 * @since 1.0.0
 */
@Component
@Profile("migration")
public class SlotCollectTool implements BiFunction<SlotCollectTool.Request, ToolContext, SlotCollectTool.Response> {

    private static final Logger logger = LoggerFactory.getLogger(SlotCollectTool.class);

    private final SlotCollectorService slotCollectorService;
    private final SlotEnricherService slotEnricherService;
    private final ComputedFieldProcessor computedFieldProcessor;
    private final SlotSchemaParser slotSchemaParser;
    private final ObjectMapper objectMapper;
    private final ToolMetaService toolMetaService;

    public SlotCollectTool(SlotCollectorService slotCollectorService,
                           SlotEnricherService slotEnricherService,
                           ComputedFieldProcessor computedFieldProcessor,
                           SlotSchemaParser slotSchemaParser,
                           ObjectMapper objectMapper,
                           ToolMetaService toolMetaService) {
        this.slotCollectorService = slotCollectorService;
        this.slotEnricherService = slotEnricherService;
        this.computedFieldProcessor = computedFieldProcessor;
        this.slotSchemaParser = slotSchemaParser;
        this.objectMapper = objectMapper;
        this.toolMetaService = toolMetaService;
    }

    public static ToolCallback createToolCallback(SlotCollectTool tool) {
        return FunctionToolCallback.builder("slot_collect", tool)
                .description("Collect and enrich workflow slots, then return COLLECTING/COMPLETE result.")
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

            List<SlotDefinition> slotDefinitions = slotSchemaParser.parse(snapshot);
            if (slotDefinitions.isEmpty()) {
                return Response.error("No slot definitions found for tool: " + snapshot.getToolCode());
            }

            Map<String, SlotValue> collectedSlots = readCollectedSlots(state);
            Map<String, Object> extracted = effectiveRequest.extractedSlots != null
                    ? effectiveRequest.extractedSlots : Collections.emptyMap();
            if (!extracted.isEmpty()) {
                collectedSlots = slotCollectorService.collectFromAgent(extracted, slotDefinitions, collectedSlots);
            }

            // Priority: state > snapshot > LLM args (LLM often hallucinates identity values)
            String systemCode = firstNonEmpty(
                    readStringState(state, AssistantStateKeys.SYSTEM_CODE),
                    snapshot.getSystemCode(),
                    effectiveRequest.systemCode);
            String assistantUid = firstNonEmpty(
                    readStringState(state, AssistantStateKeys.ASSISTANT_UID),
                    effectiveRequest.assistantUid);

            List<EnrichedSlot> enrichedSlots = enrichSlotsSafely(slotDefinitions, systemCode, assistantUid);
            applyAutoSelect(slotDefinitions, enrichedSlots, collectedSlots);

            computedFieldProcessor.processComputedFields(slotDefinitions, collectedSlots);

            SlotCollectStatus status = slotCollectorService.checkCollectionStatus(slotDefinitions, collectedSlots);
            int nextRound = readIntState(state, AssistantStateKeys.COLLECT_ROUND) + 1;
            CollectBehavior behavior = parseCollectBehavior(snapshot.getBehaviorConfig());
            List<SlotDefinition> nextSlots = status == SlotCollectStatus.COMPLETE
                    ? Collections.emptyList()
                    : slotCollectorService.getNextSlotsToCollect(slotDefinitions, collectedSlots, behavior, nextRound);

            persistState(state, snapshot, slotDefinitions, collectedSlots, enrichedSlots, status, nextRound);

            if (status == SlotCollectStatus.COMPLETE) {
                return Response.complete(
                        slotCollectorService.buildFinalParams(slotDefinitions, collectedSlots),
                        mapCollected(collectedSlots),
                        nextRound,
                        enrichedSlots);
            }
            return Response.collecting(
                    mapCollected(collectedSlots),
                    buildMissingSlots(nextSlots, enrichedSlots),
                    nextRound,
                    enrichedSlots,
                    status.name());
        }
        catch (Exception e) {
            logger.error("SlotCollectTool#apply - execution failed", e);
            return Response.error("slot_collect failed: " + e.getMessage());
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
        // Priority 1: State contains a matched tool meta (set by upstream intent matching)
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
                    logger.warn("SlotCollectTool#resolveToolMetaSnapshot - cannot convert state meta, error={}",
                            e.getMessage());
                }
            }
        }

        // Priority 2: Lookup ToolMeta from database by toolCode
        if (StringUtils.hasText(request.toolCode) && toolMetaService != null) {
            ToolMeta toolMeta = lookupToolMetaByCode(request.toolCode);
            if (toolMeta != null) {
                logger.info("SlotCollectTool#resolveToolMetaSnapshot - reason=从数据库找到ToolMeta, toolCode={}",
                        request.toolCode);
                return convertFromToolMeta(toolMeta);
            }
        }

        // Priority 3: Inline schema from request (fallback)
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

    private ToolMeta lookupToolMetaByCode(String toolCode) {
        try {
            LambdaQueryWrapper<ToolMeta> query = new LambdaQueryWrapper<>();
            query.eq(ToolMeta::getToolCode, toolCode);
            query.and(w -> w.isNull(ToolMeta::getStatus).or().eq(ToolMeta::getStatus, "enabled"));
            query.last("LIMIT 1");
            return toolMetaService.getOne(query, false);
        }
        catch (Exception e) {
            logger.warn("SlotCollectTool#lookupToolMetaByCode - reason=数据库查找失败, toolCode={}, error={}",
                    toolCode, e.getMessage());
            return null;
        }
    }

    private ToolMetaSnapshot convertFromToolMeta(ToolMeta toolMeta) {
        ToolMetaSnapshot snapshot = new ToolMetaSnapshot();
        snapshot.setToolCode(toolMeta.getToolCode());
        snapshot.setSlotSchema(toolMeta.getParameterSchema());
        snapshot.setExecutionPlan(toolMeta.getExecutionPlan());
        snapshot.setBehaviorConfig(toolMeta.getInteractionPolicy());
        snapshot.setSystemCode(toolMeta.getSystemCode());
        return snapshot;
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
                    logger.warn("SlotCollectTool#readCollectedSlots - failed to convert slot={}, error={}",
                            entry.getKey(), e.getMessage());
                }
            }
        }
        return result;
    }

    private List<EnrichedSlot> enrichSlotsSafely(List<SlotDefinition> slotDefinitions, String systemCode,
                                                 String assistantUid) {
        if (!StringUtils.hasText(systemCode) || !StringUtils.hasText(assistantUid)) {
            return slotDefinitions.stream().map(EnrichedSlot::new).collect(Collectors.toList());
        }

        try {
            return slotEnricherService.enrichSlots(slotDefinitions, systemCode, assistantUid);
        }
        catch (Exception e) {
            logger.warn("SlotCollectTool#enrichSlotsSafely - enrichment failed, error={}", e.getMessage());
            return slotDefinitions.stream().map(EnrichedSlot::new).collect(Collectors.toList());
        }
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

        if ("first".equalsIgnoreCase(autoSelectConfig.getStrategy())
                || !StringUtils.hasText(autoSelectConfig.getStrategy())) {
            return options.get(0);
        }

        return options.get(0);
    }

    private CollectBehavior parseCollectBehavior(String behaviorConfigJson) {
        if (!StringUtils.hasText(behaviorConfigJson)) {
            return CollectBehavior.defaults();
        }

        try {
            JsonNode root = objectMapper.readTree(behaviorConfigJson);
            JsonNode collectNode = root.has("collect") ? root.get("collect") : root;
            CollectBehavior behavior = objectMapper.convertValue(collectNode, CollectBehavior.class);
            return behavior != null ? behavior : CollectBehavior.defaults();
        }
        catch (Exception e) {
            logger.warn("SlotCollectTool#parseCollectBehavior - fallback to defaults, error={}", e.getMessage());
            return CollectBehavior.defaults();
        }
    }

    private List<MissingSlot> buildMissingSlots(List<SlotDefinition> nextSlots, List<EnrichedSlot> enrichedSlots) {
        Map<String, EnrichedSlot> enrichedByName = enrichedSlots.stream()
                .filter(slot -> slot.getDefinition() != null && StringUtils.hasText(slot.getDefinition().getName()))
                .collect(Collectors.toMap(slot -> slot.getDefinition().getName(), slot -> slot, (a, b) -> a));

        List<MissingSlot> missing = new ArrayList<>();
        for (SlotDefinition slot : nextSlots) {
            MissingSlot item = new MissingSlot();
            item.name = slot.getName();
            item.title = slot.getTitle();
            item.priority = slot.getPriority() != null ? slot.getPriority().name() : null;
            item.askMode = slot.getAskMode() != null ? slot.getAskMode().name() : null;
            item.aiHint = slot.getAiHint();
            EnrichedSlot enriched = enrichedByName.get(slot.getName());
            item.options = enriched != null ? enriched.getOptionsForPrompt() : null;
            missing.add(item);
        }
        return missing;
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
                              Map<String, SlotValue> collectedSlots, List<EnrichedSlot> enrichedSlots,
                              SlotCollectStatus status, int round) {
        if (state == null) {
            return;
        }

        Map<String, Object> updates = new HashMap<>();
        updates.put(AssistantStateKeys.COLLECTED_SLOTS, collectedSlots);
        updates.put(AssistantStateKeys.COLLECT_ROUND, round);
        updates.put(AssistantStateKeys.ENRICHED_SLOTS, enrichedSlots);
        updates.put(AssistantStateKeys.SLOT_DEFINITIONS, slotDefinitions);
        updates.put(AssistantStateKeys.CONVERSATION_PHASE,
                status == SlotCollectStatus.COMPLETE ? "READY_TO_CONFIRM" : status.name());
        if (snapshot != null) {
            updates.put(AssistantStateKeys.MATCHED_TOOL_META, snapshot);
        }

        state.updateState(updates);
    }

    private String firstNonEmpty(String... values) {
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value;
            }
        }
        return null;
    }

    private String readStringState(OverAllState state, String key) {
        if (state == null) {
            return null;
        }
        return state.value(key, String.class).orElse(null);
    }

    private int readIntState(OverAllState state, String key) {
        if (state == null) {
            return 0;
        }
        Integer value = state.value(key, Integer.class).orElse(0);
        return value != null ? value : 0;
    }

    /**
     * slot_collect request.
     */
    public static class Request {
        @JsonPropertyDescription("Target tool code")
        public String toolCode;

        @JsonPropertyDescription("Extracted slot values from current user turn")
        public Map<String, Object> extractedSlots = new HashMap<>();

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
     * slot_collect response.
     */
    public static class Response {
        public String status;
        public String phase;
        public String message;
        public Integer round;
        public Map<String, Object> collected;
        public Map<String, Object> allCollected;
        public List<MissingSlot> missing;
        public List<EnrichedSlot> enrichedSlots;

        public static Response collecting(Map<String, Object> collected,
                                          List<MissingSlot> missing,
                                          Integer round,
                                          List<EnrichedSlot> enrichedSlots,
                                          String status) {
            Response response = new Response();
            response.status = status;
            response.phase = "COLLECTING";
            response.collected = collected;
            response.missing = missing;
            response.round = round;
            response.enrichedSlots = enrichedSlots;
            response.message = "Missing required slots, continue collecting.";
            return response;
        }

        public static Response complete(Map<String, Object> allCollected,
                                        Map<String, Object> collected,
                                        Integer round,
                                        List<EnrichedSlot> enrichedSlots) {
            Response response = new Response();
            response.status = SlotCollectStatus.COMPLETE.name();
            response.phase = "READY_TO_CONFIRM";
            response.allCollected = allCollected;
            response.collected = collected;
            response.round = round;
            response.enrichedSlots = enrichedSlots;
            response.message = "All required slots collected.";
            return response;
        }

        public static Response error(String message) {
            Response response = new Response();
            response.status = "ERROR";
            response.phase = "COLLECTING";
            response.message = message;
            response.missing = Collections.emptyList();
            response.collected = Collections.emptyMap();
            return response;
        }
    }

    /**
     * Missing slot descriptor returned to LLM.
     */
    public static class MissingSlot {
        public String name;
        public String title;
        public String priority;
        public String askMode;
        public String aiHint;
        public String options;
    }
}
