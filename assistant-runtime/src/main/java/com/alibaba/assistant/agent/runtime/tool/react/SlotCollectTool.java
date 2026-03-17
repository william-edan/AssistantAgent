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
import com.alibaba.assistant.agent.runtime.agent.ConversationUserInputResolver;
import com.alibaba.assistant.agent.runtime.registry.ArtifactPublicationLookupService;
import com.alibaba.assistant.agent.runtime.registry.PublishedToolDescriptor;
import com.alibaba.assistant.agent.runtime.planner.DependencyResolver;
import com.alibaba.assistant.agent.runtime.planner.FieldMappingProcessor;
import com.alibaba.assistant.agent.runtime.planner.ToolExecutor;
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
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 槽位收集工具。
 *
 * <p>负责在多轮对话中完成参数收集主链路：解析用户输入、合并历史值、加载候选项、自动补全默认值、
 * 计算派生字段并判断当前是否可以进入确认阶段。绝大多数“我要请假”“我要写周报”之类的业务请求，
 * 都会先经过这里生成采集卡或确认卡。</p>
 */
@Component
@Profile("migration")
public class SlotCollectTool implements BiFunction<SlotCollectTool.Request, ToolContext, SlotCollectTool.Response> {

    private static final Logger logger = LoggerFactory.getLogger(SlotCollectTool.class);
    private static final String RAW_TOOL_OPTION_PAYLOAD_KEY = "_toolOptionPayload";

    private static final Pattern RELATIVE_DATE_RANGE_PATTERN =
            Pattern.compile("(大后天|后天|明天|今天|今日)\\s*(?:到|至|\\-|~|～)\\s*(大后天|后天|明天|今天|今日)");

    private static final Pattern ABSOLUTE_DATE_PATTERN =
            Pattern.compile("(20\\d{2})[-/.年](\\d{1,2})[-/.月](\\d{1,2})日?");

    private static final Pattern EXPLICIT_TITLE_PATTERN =
            Pattern.compile("(?:会议主题|主题|标题)(?:是|为|：|:)?\\s*([^，。；,;\n]{1,40})");

    private static final Pattern HEADCOUNT_PATTERN =
            Pattern.compile("([零一二两三四五六七八九十\\d]{1,3})\\s*(?:个?人|位)");

    private static final Pattern RELATIVE_TIME_RANGE_PATTERN =
            Pattern.compile("(?:(今天|今日|明天|后天|大后天|20\\d{2}[-/.年]\\d{1,2}[-/.月]\\d{1,2}日?)\\s*)?"
                    + "(上午|中午|下午|晚上)?\\s*([零一二两三四五六七八九十\\d]{1,2})(?:点|时)(半|一刻|三刻|[0-5]?\\d分?)?"
                    + "\\s*(?:到|至|\\-|~|～)\\s*"
                    + "(上午|中午|下午|晚上)?\\s*([零一二两三四五六七八九十\\d]{1,2})(?:点|时)(半|一刻|三刻|[0-5]?\\d分?)?");
    private static final Pattern RELATIVE_TIME_POINT_PATTERN =
            Pattern.compile("(上午|中午|下午|晚上)?\\s*([零一二两三四五六七八九十\\d]{1,2})(?:点|时)(半|一刻|三刻|[0-5]?\\d分?)?");
    private static final Pattern CLOCK_TIME_PATTERN = Pattern.compile("(?<!\\d)(\\d{1,2}):(\\d{2})(?!\\d)");

    private static final DateTimeFormatter DATETIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private static final Pattern EXPLICIT_REASON_PATTERN =
            Pattern.compile("(?:请假原因(?:是|为)?|原因(?:是|为)?|因为|由于)\\s*[:：]?\\s*([^，。；,;\\n]{1,40})");

    private static final Pattern GENERIC_PRIVATE_REASON_PATTERN =
            Pattern.compile("有[^，。；,;\\n]{0,6}事");

    private final SlotCollectorService slotCollectorService;
    private final SlotEnricherService slotEnricherService;
    private final ComputedFieldProcessor computedFieldProcessor;
    private final SlotSchemaParser slotSchemaParser;
    private final ObjectMapper objectMapper;
    private final DependencyResolver dependencyResolver;
    private final FieldMappingProcessor fieldMappingProcessor;
    private final ToolExecutor toolExecutor;
    private final ArtifactPublicationLookupService artifactPublicationLookupService;
    private final ToolMetaService toolMetaService;

    public SlotCollectTool(SlotCollectorService slotCollectorService,
                           SlotEnricherService slotEnricherService,
                           ComputedFieldProcessor computedFieldProcessor,
                           SlotSchemaParser slotSchemaParser,
                           ObjectMapper objectMapper) {
        this(slotCollectorService, slotEnricherService, computedFieldProcessor, slotSchemaParser, objectMapper,
                null, null, null, null, null);
    }

    public SlotCollectTool(SlotCollectorService slotCollectorService,
                           SlotEnricherService slotEnricherService,
                           ComputedFieldProcessor computedFieldProcessor,
                           SlotSchemaParser slotSchemaParser,
                           ObjectMapper objectMapper,
                           DependencyResolver dependencyResolver,
                           FieldMappingProcessor fieldMappingProcessor,
                           ToolExecutor toolExecutor) {
        this(slotCollectorService, slotEnricherService, computedFieldProcessor, slotSchemaParser, objectMapper,
                dependencyResolver, fieldMappingProcessor, toolExecutor, null, null);
    }

    public SlotCollectTool(SlotCollectorService slotCollectorService,
                           SlotEnricherService slotEnricherService,
                           ComputedFieldProcessor computedFieldProcessor,
                           SlotSchemaParser slotSchemaParser,
                           ObjectMapper objectMapper,
                           DependencyResolver dependencyResolver,
                           FieldMappingProcessor fieldMappingProcessor,
                           ToolExecutor toolExecutor,
                           ArtifactPublicationLookupService artifactPublicationLookupService) {
        this(slotCollectorService, slotEnricherService, computedFieldProcessor, slotSchemaParser, objectMapper,
                dependencyResolver, fieldMappingProcessor, toolExecutor, artifactPublicationLookupService, null);
    }

    @Autowired
    public SlotCollectTool(SlotCollectorService slotCollectorService,
                           SlotEnricherService slotEnricherService,
                           ComputedFieldProcessor computedFieldProcessor,
                           SlotSchemaParser slotSchemaParser,
                           ObjectMapper objectMapper,
                           @Nullable DependencyResolver dependencyResolver,
                           @Nullable FieldMappingProcessor fieldMappingProcessor,
                           @Nullable ToolExecutor toolExecutor,
                           @Nullable ArtifactPublicationLookupService artifactPublicationLookupService,
                           @Nullable ToolMetaService toolMetaService) {
        this.slotCollectorService = slotCollectorService;
        this.slotEnricherService = slotEnricherService;
        this.computedFieldProcessor = computedFieldProcessor;
        this.slotSchemaParser = slotSchemaParser;
        this.objectMapper = objectMapper;
        this.dependencyResolver = dependencyResolver;
        this.fieldMappingProcessor = fieldMappingProcessor != null ? fieldMappingProcessor : new FieldMappingProcessor();
        this.toolExecutor = toolExecutor;
        this.artifactPublicationLookupService = artifactPublicationLookupService;
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
            ToolMetaSnapshot snapshot = resolveToolMetaSnapshot(effectiveRequest, state, toolContext);
            if (snapshot == null) {
                return Response.error("Missing tool meta snapshot or slot schema");
            }

            List<SlotDefinition> slotDefinitions = slotSchemaParser.parse(snapshot);
            if (slotDefinitions.isEmpty()) {
                return Response.error("No slot definitions found for tool: " + snapshot.getToolCode());
            }

            // Priority: state > snapshot > LLM args (LLM often hallucinates identity values)
            String systemCode = firstNonEmpty(
                    readStringState(state, AssistantStateKeys.SYSTEM_CODE),
                    snapshot.getSystemCode(),
                    effectiveRequest.systemCode);
            String assistantUid = firstNonEmpty(
                    readStringState(state, AssistantStateKeys.ASSISTANT_UID),
                    effectiveRequest.assistantUid);
            String tenantId = resolveTenantId(state, effectiveRequest);

            Map<String, SlotValue> collectedSlots = readCollectedSlots(state);
            Map<String, Object> currentTurnSlotInputs = readCurrentTurnSlotInputs(state, slotDefinitions);
            List<EnrichedSlot> previousEnrichedSlots = readEnrichedSlots(state);
            Map<String, Map<String, Object>> dependencyResults = readDependencyResults(state);
            DependencyExecution dependencyExecution = resolveAndExecuteDependencies(
                    snapshot,
                    tenantId,
                    collectedSlots,
                    dependencyResults,
                    systemCode,
                    assistantUid,
                    state,
                    toolContext);
            dependencyResults = dependencyExecution.results();
            if (!dependencyExecution.mappings().isEmpty() && !dependencyResults.isEmpty()) {
                collectedSlots = fieldMappingProcessor.applyMappings(
                        dependencyExecution.mappings(),
                        dependencyResults,
                        collectedSlots);
            }

            String userInput = resolveUserInput(state);
            boolean suppressModelExtraction = shouldSuppressModelExtraction(state, userInput);
            if (suppressModelExtraction) {
                logger.info("SlotCollectTool#apply - suppressing model-only extraction in same user turn");
            }
            if (suppressModelExtraction) {
                SlotEnrichmentResult enrichmentResult = enrichSlotsSafely(
                        slotDefinitions,
                        systemCode,
                        assistantUid,
                        tenantId,
                        collectedSlots,
                        dependencyResults,
                        state,
                        toolContext);
                List<EnrichedSlot> enrichedSlots = enrichmentResult.enrichedSlots();
                dependencyResults = enrichmentResult.dependencyResults();
                applyAutoSelect(slotDefinitions, enrichedSlots, collectedSlots);
                computedFieldProcessor.processComputedFields(
                        slotDefinitions,
                        collectedSlots,
                        buildComputationMetadata(state));

                SlotCollectStatus status = slotCollectorService.checkCollectionStatus(slotDefinitions, collectedSlots);
                int currentRound = Math.max(1, readIntState(state, AssistantStateKeys.COLLECT_ROUND));
                CollectBehavior behavior = parseCollectBehavior(snapshot.getBehaviorConfig());
                List<SlotDefinition> nextSlots = status == SlotCollectStatus.COMPLETE
                        ? Collections.emptyList()
                        : slotCollectorService.getNextSlotsToCollect(slotDefinitions, collectedSlots, behavior,
                        currentRound);

                if (status == SlotCollectStatus.COMPLETE) {
                    return Response.complete(
                            snapshot.getToolCode(),
                            slotCollectorService.buildFinalParams(slotDefinitions, collectedSlots),
                            mapCollected(collectedSlots),
                            currentRound,
                            enrichedSlots);
                }
                Response waiting = Response.collecting(
                        snapshot.getToolCode(),
                        mapCollected(collectedSlots),
                        buildMissingSlots(nextSlots, enrichedSlots),
                        currentRound,
                        enrichedSlots,
                        status.name());
                waiting.message = "No new user input detected; waiting for user input.";
                return waiting;
            }

            Map<String, Object> extracted = mergeAndInferExtractedSlots(
                    effectiveRequest.extractedSlots,
                    currentTurnSlotInputs,
                    slotDefinitions,
                    state,
                    userInput,
                    suppressModelExtraction,
                    collectedSlots,
                    previousEnrichedSlots);
            if (!extracted.isEmpty()) {
                collectedSlots = slotCollectorService.collectFromAgent(extracted, slotDefinitions, collectedSlots);
            }

            SlotEnrichmentResult enrichmentResult = enrichSlotsSafely(
                    slotDefinitions,
                    systemCode,
                    assistantUid,
                    tenantId,
                    collectedSlots,
                    dependencyResults,
                    state,
                    toolContext);
            List<EnrichedSlot> enrichedSlots = enrichmentResult.enrichedSlots();
            dependencyResults = enrichmentResult.dependencyResults();
            applyAutoSelect(slotDefinitions, enrichedSlots, collectedSlots);

            computedFieldProcessor.processComputedFields(
                    slotDefinitions,
                    collectedSlots,
                    buildComputationMetadata(state));

            SlotCollectStatus status = slotCollectorService.checkCollectionStatus(slotDefinitions, collectedSlots);
            int nextRound = readIntState(state, AssistantStateKeys.COLLECT_ROUND) + 1;
            CollectBehavior behavior = parseCollectBehavior(snapshot.getBehaviorConfig());
            List<SlotDefinition> nextSlots = status == SlotCollectStatus.COMPLETE
                    ? Collections.emptyList()
                    : slotCollectorService.getNextSlotsToCollect(slotDefinitions, collectedSlots, behavior, nextRound);

            persistState(state, snapshot, slotDefinitions, collectedSlots, enrichedSlots, status, nextRound,
                    dependencyResults, userInput, currentTurnSlotInputs);

            if (status == SlotCollectStatus.COMPLETE) {
                return Response.complete(
                        snapshot.getToolCode(),
                        slotCollectorService.buildFinalParams(slotDefinitions, collectedSlots),
                        mapCollected(collectedSlots),
                        nextRound,
                        enrichedSlots);
            }
            return Response.collecting(
                    snapshot.getToolCode(),
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

    private Map<String, Object> mergeAndInferExtractedSlots(Map<String, Object> extractedSlots,
                                                            Map<String, Object> currentTurnSlotInputs,
                                                            List<SlotDefinition> slotDefinitions,
                                                            OverAllState state,
                                                            String userInput,
                                                            boolean suppressModelExtraction,
                                                            Map<String, SlotValue> collectedSlots,
                                                            List<EnrichedSlot> previousEnrichedSlots) {
        Map<String, Object> merged = new LinkedHashMap<>();
        Set<String> explicitStructuredSlotNames = currentTurnSlotInputs != null
                ? currentTurnSlotInputs.keySet().stream()
                .filter(StringUtils::hasText)
                .collect(Collectors.toSet())
                : Collections.emptySet();
        if (!suppressModelExtraction && extractedSlots != null && !extractedSlots.isEmpty()) {
            merged.putAll(extractedSlots);
        }
        if (currentTurnSlotInputs != null && !currentTurnSlotInputs.isEmpty()) {
            merged.putAll(filterSupportedSlotInputs(currentTurnSlotInputs, slotDefinitions));
        }

        if (!StringUtils.hasText(userInput)) {
            return merged;
        }

        LocalDate anchorDate = resolveAnchorDate(state);
        applyStructuredSlotPatch(merged, slotDefinitions, userInput, anchorDate, collectedSlots);
        applyDateFallback(merged, slotDefinitions, userInput, anchorDate, collectedSlots);
        applyEnumOptionFallbacks(merged, slotDefinitions, userInput, collectedSlots);
        applyLeaveTypeFallback(merged, slotDefinitions, userInput, collectedSlots);
        applyReasonFallback(merged, slotDefinitions, userInput);
        applyStructuredTextFallbacks(merged, slotDefinitions, userInput, collectedSlots);
        applyNumericFallbacks(merged, slotDefinitions, userInput);
        applySelectableOptionFallbacks(
                merged,
                slotDefinitions,
                previousEnrichedSlots,
                userInput,
                collectedSlots,
                explicitStructuredSlotNames);
        return merged;
    }

    private Map<String, Object> filterSupportedSlotInputs(Map<String, Object> currentTurnSlotInputs,
                                                           List<SlotDefinition> slotDefinitions) {
        if (currentTurnSlotInputs == null || currentTurnSlotInputs.isEmpty()
                || slotDefinitions == null || slotDefinitions.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, Object> filtered = new LinkedHashMap<>();
        for (SlotDefinition slotDefinition : slotDefinitions) {
            if (slotDefinition == null || !StringUtils.hasText(slotDefinition.getName())) {
                continue;
            }
            if (currentTurnSlotInputs.containsKey(slotDefinition.getName())) {
                filtered.put(slotDefinition.getName(), currentTurnSlotInputs.get(slotDefinition.getName()));
            }
        }
        return filtered;
    }

    private void applyStructuredSlotPatch(Map<String, Object> extracted,
                                          List<SlotDefinition> slotDefinitions,
                                          String userInput,
                                          LocalDate anchorDate,
                                          Map<String, SlotValue> collectedSlots) {
        if (extracted == null || slotDefinitions == null || slotDefinitions.isEmpty()
                || !StringUtils.hasText(userInput)) {
            return;
        }
        for (SlotDefinition definition : slotDefinitions) {
            if (definition == null || !StringUtils.hasText(definition.getName())) {
                continue;
            }
            String explicitValue = extractExplicitFieldValue(userInput, buildStructuredPatchAliases(definition));
            if (!StringUtils.hasText(explicitValue)) {
                continue;
            }
            Object normalizedValue = normalizeStructuredPatchValue(definition, explicitValue, anchorDate, collectedSlots);
            if (normalizedValue != null) {
                extracted.put(definition.getName(), normalizedValue);
            }
        }
    }

    private List<String> buildStructuredPatchAliases(SlotDefinition definition) {
        if (definition == null) {
            return List.of();
        }
        java.util.LinkedHashSet<String> aliases = new java.util.LinkedHashSet<>();
        addStructuredPatchAlias(aliases, definition.getTitle());
        addStructuredPatchAlias(aliases, definition.getName());
        if (definition.getAliases() != null) {
            for (String alias : definition.getAliases()) {
                addStructuredPatchAlias(aliases, alias);
            }
        }
        addSemanticStructuredPatchAliases(aliases, definition);
        List<String> ordered = new ArrayList<>(aliases);
        ordered.sort((left, right) -> Integer.compare(right.length(), left.length()));
        return ordered;
    }

    private void addStructuredPatchAlias(java.util.LinkedHashSet<String> aliases, String rawAlias) {
        if (aliases == null || !StringUtils.hasText(rawAlias)) {
            return;
        }
        aliases.add(rawAlias.trim());
    }

    private void addSemanticStructuredPatchAliases(java.util.LinkedHashSet<String> aliases, SlotDefinition definition) {
        if (aliases == null || definition == null) {
            return;
        }
        String normalizedName = normalizeOptionText(definition.getName());
        String normalizedTitle = normalizeOptionText(definition.getTitle());
        String loweredName = normalizedName != null ? normalizedName.toLowerCase(Locale.ROOT) : "";
        String loweredTitle = normalizedTitle != null ? normalizedTitle.toLowerCase(Locale.ROOT) : "";

        if (isTemporalStartSlot(definition, normalizedTitle, loweredName, loweredTitle)) {
            aliases.addAll(List.of("开始时间", "开始日期", "开始", "起始时间", "起始日期", "起始"));
        }
        if (isTemporalEndSlot(definition, normalizedTitle, loweredName, loweredTitle)) {
            aliases.addAll(List.of("结束时间", "结束日期", "结束", "截至时间", "截至日期", "截至", "截止时间", "截止日期", "截止"));
        }
        if (isReasonSlot(definition, loweredName, loweredTitle)) {
            aliases.addAll(List.of("原因", "请假原因", "事由", "备注"));
        }
        if (isTopicSlot(loweredName, loweredTitle)) {
            aliases.addAll(List.of("主题", "标题", "会议主题"));
        }
        if (isCountSlot(loweredName, loweredTitle)) {
            aliases.addAll(List.of("人数", "数量", "人次"));
        }
        if (isTypedOptionSlot(definition, loweredName, loweredTitle)) {
            aliases.addAll(List.of("类型", "请假类型", "类别"));
        }
    }

    private boolean isTemporalStartSlot(SlotDefinition definition,
                                        String normalizedTitle,
                                        String loweredName,
                                        String loweredTitle) {
        return isTemporalSlot(definition)
                && (containsAny(normalizedTitle, "开始", "起始")
                || loweredName.contains("start")
                || loweredName.contains("from")
                || loweredTitle.contains("start")
                || loweredTitle.contains("from"));
    }

    private boolean isTemporalEndSlot(SlotDefinition definition,
                                      String normalizedTitle,
                                      String loweredName,
                                      String loweredTitle) {
        return isTemporalSlot(definition)
                && (containsAny(normalizedTitle, "结束", "截至", "截止")
                || loweredName.contains("end")
                || loweredName.contains("to")
                || loweredName.contains("until")
                || loweredTitle.contains("end")
                || loweredTitle.contains("until"));
    }

    private boolean isTemporalSlot(SlotDefinition definition) {
        return definition != null && (isDateTimeSlot(definition) || isDateSlot(definition));
    }

    private boolean isDateSlot(SlotDefinition definition) {
        if (definition == null) {
            return false;
        }
        return "date".equalsIgnoreCase(definition.getType())
                || "date".equalsIgnoreCase(definition.getUiComponent());
    }

    private boolean isReasonSlot(SlotDefinition definition, String loweredName, String loweredTitle) {
        return containsAny(normalizeOptionText(definition != null ? definition.getTitle() : null), "原因", "事由", "备注")
                || loweredName.contains("reason")
                || loweredName.contains("memo")
                || loweredName.contains("remark")
                || loweredName.contains("note")
                || loweredTitle.contains("reason")
                || loweredTitle.contains("memo")
                || loweredTitle.contains("remark")
                || loweredTitle.contains("note");
    }

    private boolean isTopicSlot(String loweredName, String loweredTitle) {
        return loweredName.contains("title")
                || loweredName.contains("topic")
                || loweredName.contains("subject")
                || loweredTitle.contains("title")
                || loweredTitle.contains("topic")
                || loweredTitle.contains("subject");
    }

    private boolean isCountSlot(String loweredName, String loweredTitle) {
        return loweredName.contains("count")
                || loweredName.contains("headcount")
                || loweredName.contains("num")
                || loweredTitle.contains("count")
                || loweredTitle.contains("headcount")
                || loweredTitle.contains("num");
    }

    private boolean isTypedOptionSlot(SlotDefinition definition, String loweredName, String loweredTitle) {
        return definition != null
                && definition.getOptions() != null
                && (loweredName.contains("type")
                || loweredName.contains("kind")
                || loweredName.contains("category")
                || loweredTitle.contains("type")
                || loweredTitle.contains("kind")
                || loweredTitle.contains("category"));
    }

    private Object normalizeStructuredPatchValue(SlotDefinition definition,
                                                 String explicitValue,
                                                 LocalDate anchorDate,
                                                 Map<String, SlotValue> collectedSlots) {
        if (definition == null || !StringUtils.hasText(explicitValue)) {
            return null;
        }
        String trimmedValue = explicitValue.trim();
        List<SlotOption> staticOptions = resolveStaticOptions(definition);
        if (!staticOptions.isEmpty()) {
            Object normalizedOptionValue = normalizeStructuredOptionValue(definition, trimmedValue, staticOptions);
            if (normalizedOptionValue != null) {
                return normalizedOptionValue;
            }
        }
        if (isDateTimeSlot(definition)) {
            return parseExplicitDateTimeValue(trimmedValue, anchorDate, collectedSlots, definition.getName(), null);
        }
        if (isDateSlot(definition)) {
            LocalDate date = resolveDateToken(trimmedValue, anchorDate);
            if (date == null) {
                date = extractSingleDate(trimmedValue, anchorDate);
            }
            return date != null ? date.toString() : null;
        }
        if (isNumericSlot(definition)) {
            return extractExplicitIntegerValue(trimmedValue);
        }
        if (isReasonSlot(definition, normalizeOptionText(definition.getName()), normalizeOptionText(definition.getTitle()))) {
            String sanitized = sanitizeReason(trimmedValue);
            return StringUtils.hasText(sanitized) ? sanitized : trimmedValue;
        }
        return trimmedValue;
    }

    private boolean isNumericSlot(SlotDefinition definition) {
        if (definition == null) {
            return false;
        }
        return "integer".equalsIgnoreCase(definition.getType())
                || "number".equalsIgnoreCase(definition.getType())
                || "integer".equalsIgnoreCase(definition.getUiComponent())
                || "number".equalsIgnoreCase(definition.getUiComponent());
    }

    private Integer extractExplicitIntegerValue(String rawValue) {
        if (!StringUtils.hasText(rawValue)) {
            return null;
        }
        Matcher matcher = Pattern.compile("-?\\d+").matcher(rawValue);
        if (matcher.find()) {
            return Integer.parseInt(matcher.group());
        }
        String normalized = rawValue.replace("个", "")
                .replace("位", "")
                .replace("人", "")
                .replace("名", "")
                .trim();
        return parseLocalizedInteger(normalized);
    }

    private Object normalizeStructuredOptionValue(SlotDefinition definition,
                                                  String explicitValue,
                                                  List<SlotOption> options) {
        if (definition == null || !StringUtils.hasText(explicitValue) || options == null || options.isEmpty()) {
            return null;
        }
        if (!isMultiValueSlot(definition)) {
            return normalizeSingleOptionValue(explicitValue, options);
        }
        List<Object> normalized = new ArrayList<>();
        for (String segment : explicitValue.split("[,，、/|和及]")) {
            Object optionValue = normalizeSingleOptionValue(segment.trim(), options);
            addDistinctValue(normalized, optionValue);
        }
        return normalized.isEmpty() ? null : normalized;
    }

    private List<SlotOption> resolveStaticOptions(SlotDefinition definition) {
        if (definition == null || definition.getOptions() == null) {
            return List.of();
        }
        List<SlotOption> options = new ArrayList<>();
        if (definition.getOptions().getValues() != null) {
            for (SlotOptions.OptionValue optionValue : definition.getOptions().getValues()) {
                if (optionValue == null || optionValue.getValue() == null) {
                    continue;
                }
                String label = StringUtils.hasText(optionValue.getLabel())
                        ? optionValue.getLabel()
                        : String.valueOf(optionValue.getValue());
                options.add(new SlotOption(label, optionValue.getValue()));
            }
        }
        if (definition.getOptions().getEnumMapping() != null) {
            for (Map.Entry<String, Object> entry : definition.getOptions().getEnumMapping().entrySet()) {
                if (StringUtils.hasText(entry.getKey()) && entry.getValue() != null) {
                    options.add(new SlotOption(entry.getKey(), entry.getValue()));
                }
            }
        }
        return options;
    }

    private boolean shouldSuppressModelExtraction(OverAllState state, String userInput) {
        if (state == null || !StringUtils.hasText(userInput)) {
            return false;
        }

        String phase = readStringState(state, AssistantStateKeys.CONVERSATION_PHASE);
        if (!"COLLECTING".equalsIgnoreCase(phase)
                && !"BLOCKED".equalsIgnoreCase(phase)
                && !"READY_TO_CONFIRM".equalsIgnoreCase(phase)
                && !"CONFIRMING".equalsIgnoreCase(phase)) {
            return false;
        }

        String lastCollectInput = readStringState(state, AssistantStateKeys.LAST_COLLECT_USER_INPUT);
        if (!StringUtils.hasText(lastCollectInput)) {
            return false;
        }

        return normalizeForComparison(lastCollectInput).equals(normalizeForComparison(userInput));
    }

    private String normalizeForComparison(String text) {
        if (!StringUtils.hasText(text)) {
            return "";
        }
        return text.replaceAll("\\s+", "").trim();
    }

    private void applyLeaveTypeFallback(Map<String, Object> extracted,
                                        List<SlotDefinition> slotDefinitions,
                                        String userInput,
                                        Map<String, SlotValue> collectedSlots) {
        String leaveTypeSlotName = resolveSlotName(slotDefinitions, "leave_type", "types", "leaveType", "type");
        if (!StringUtils.hasText(leaveTypeSlotName) || hasTextValue(extracted.get(leaveTypeSlotName))) {
            return;
        }

        boolean hasCollectedLeaveType = hasTextValue(readCollectedValue(collectedSlots, leaveTypeSlotName));
        String inferredLeaveTypeLabel = hasCollectedLeaveType
                ? inferExplicitLeaveTypeLabel(userInput)
                : inferLeaveTypeLabel(userInput);
        if (!StringUtils.hasText(inferredLeaveTypeLabel)) {
            return;
        }

        SlotDefinition leaveTypeSlot = findSlotDefinition(slotDefinitions, leaveTypeSlotName);
        Object mappedValue = mapLeaveTypeLabelToOptionValue(leaveTypeSlot, inferredLeaveTypeLabel);
        extracted.put(leaveTypeSlotName, mappedValue != null ? mappedValue : inferredLeaveTypeLabel);
    }

    private void applyDateFallback(Map<String, Object> extracted,
                                   List<SlotDefinition> slotDefinitions,
                                   String userInput,
                                   LocalDate anchorDate,
                                   Map<String, SlotValue> collectedSlots) {
        String startSlotName = resolveSlotName(slotDefinitions, "start_date", "startDate");
        String endSlotName = resolveSlotName(slotDefinitions, "end_date", "endDate");
        if (!StringUtils.hasText(startSlotName) || !StringUtils.hasText(endSlotName)) {
            return;
        }

        SlotDefinition startSlot = findSlotDefinition(slotDefinitions, startSlotName);
        SlotDefinition endSlot = findSlotDefinition(slotDefinitions, endSlotName);
        boolean hasStart = hasTextValue(extracted.get(startSlotName));
        boolean hasEnd = hasTextValue(extracted.get(endSlotName));
        if (hasStart && hasEnd) {
            return;
        }

        if (isDateTimeSlot(startSlot) || isDateTimeSlot(endSlot)) {
            DateTimeRange dateTimeRange = extractDateTimeRange(userInput, anchorDate, collectedSlots,
                    startSlotName, endSlotName);
            if (dateTimeRange != null) {
                if (!hasStart) {
                    extracted.put(startSlotName, dateTimeRange.start());
                    hasStart = true;
                }
                if (!hasEnd) {
                    extracted.put(endSlotName, dateTimeRange.end());
                    hasEnd = true;
                }
                if (hasStart && hasEnd) {
                    return;
                }
            }
        }

        if (!hasStart) {
            String explicitStart = extractExplicitTemporalValue(
                    userInput,
                    startSlot,
                    anchorDate,
                    collectedSlots,
                    startSlotName,
                    endSlotName,
                    true);
            if (hasTextValue(explicitStart)) {
                extracted.put(startSlotName, explicitStart);
                hasStart = true;
            }
        }
        if (!hasEnd) {
            String explicitEnd = extractExplicitTemporalValue(
                    userInput,
                    endSlot,
                    anchorDate,
                    collectedSlots,
                    endSlotName,
                    startSlotName,
                    false);
            if (hasTextValue(explicitEnd)) {
                extracted.put(endSlotName, explicitEnd);
                hasEnd = true;
            }
        }
        if (hasStart && hasEnd) {
            return;
        }

        Matcher rangeMatcher = RELATIVE_DATE_RANGE_PATTERN.matcher(userInput);
        if (rangeMatcher.find()) {
            LocalDate start = parseRelativeDateToken(rangeMatcher.group(1), anchorDate);
            LocalDate end = parseRelativeDateToken(rangeMatcher.group(2), anchorDate);
            if (!hasStart && start != null) {
                extracted.put(startSlotName, start.toString());
                hasStart = true;
            }
            if (!hasEnd && end != null) {
                extracted.put(endSlotName, end.toString());
                hasEnd = true;
            }
        }

        if (!hasStart) {
            LocalDate singleDate = extractSingleDate(userInput, anchorDate);
            if (singleDate != null) {
                extracted.put(startSlotName, singleDate.toString());
                hasStart = true;
            }
        }

        if (!hasEnd && hasStart && shouldMirrorSingleDateEdit(
                userInput,
                collectedSlots,
                startSlotName,
                endSlotName,
                extracted.get(startSlotName),
                extracted.get(endSlotName))) {
            extracted.put(endSlotName, String.valueOf(extracted.get(startSlotName)));
            hasEnd = true;
        }

        if (!hasStart && hasEnd && shouldMirrorSingleDateEdit(
                userInput,
                collectedSlots,
                startSlotName,
                endSlotName,
                extracted.get(startSlotName),
                extracted.get(endSlotName))) {
            extracted.put(startSlotName, String.valueOf(extracted.get(endSlotName)));
        }
    }

    private boolean isDateTimeSlot(SlotDefinition definition) {
        if (definition == null) {
            return false;
        }
        return "datetime".equalsIgnoreCase(definition.getType())
                || "datetime".equalsIgnoreCase(definition.getUiComponent());
    }

    private DateTimeRange extractDateTimeRange(String userInput,
                                               LocalDate anchorDate,
                                               Map<String, SlotValue> collectedSlots,
                                               String startSlotName,
                                               String endSlotName) {
        if (!StringUtils.hasText(userInput)) {
            return null;
        }

        Matcher matcher = RELATIVE_TIME_RANGE_PATTERN.matcher(userInput);
        if (!matcher.find()) {
            return null;
        }

        LocalDate baseDate = resolveDateToken(matcher.group(1), anchorDate);
        if (baseDate == null) {
            baseDate = (LocalDate) firstNonNull(
                    resolveDateFromCollectedSlot(collectedSlots, startSlotName),
                    resolveDateFromCollectedSlot(collectedSlots, endSlotName),
                    anchorDate);
        }

        String startPeriod = matcher.group(2);
        LocalTime startTime = parseTimeValue(startPeriod, matcher.group(3), matcher.group(4));
        String endPeriod = StringUtils.hasText(matcher.group(5)) ? matcher.group(5) : startPeriod;
        LocalTime endTime = parseTimeValue(endPeriod, matcher.group(6), matcher.group(7));
        if (startTime == null || endTime == null || baseDate == null) {
            return null;
        }

        return new DateTimeRange(
                baseDate.atTime(startTime).format(DATETIME_FORMATTER),
                baseDate.atTime(endTime).format(DATETIME_FORMATTER));
    }

    private String extractExplicitTemporalValue(String userInput,
                                                SlotDefinition definition,
                                                LocalDate anchorDate,
                                                Map<String, SlotValue> collectedSlots,
                                                String slotName,
                                                String peerSlotName,
                                                boolean startSlot) {
        String explicitValue = extractExplicitFieldValue(userInput, buildTemporalFieldAliases(definition, startSlot));
        if (!StringUtils.hasText(explicitValue)) {
            return null;
        }
        if (isDateTimeSlot(definition)) {
            return parseExplicitDateTimeValue(explicitValue, anchorDate, collectedSlots, slotName, peerSlotName);
        }
        LocalDate explicitDate = resolveDateToken(explicitValue, anchorDate);
        if (explicitDate == null) {
            explicitDate = extractSingleDate(explicitValue, anchorDate);
        }
        return explicitDate != null ? explicitDate.toString() : null;
    }

    private String extractExplicitFieldValue(String userInput, List<String> aliases) {
        if (!StringUtils.hasText(userInput) || aliases == null || aliases.isEmpty()) {
            return null;
        }
        for (String alias : aliases) {
            if (!StringUtils.hasText(alias)) {
                continue;
            }
            Pattern pattern = Pattern.compile(
                    "(?:^|[，,；;\\s])" + Pattern.quote(alias.trim())
                            + "\\s*(?:是|为|改成|改为|填写|填成|写成|写)?\\s*[:：]?\\s*([^，。；;\\n]+)");
            Matcher matcher = pattern.matcher(userInput);
            if (matcher.find()) {
                String value = matcher.group(1);
                if (StringUtils.hasText(value)) {
                    return value.trim();
                }
            }
        }
        return null;
    }

    private List<String> buildTemporalFieldAliases(SlotDefinition definition, boolean startSlot) {
        List<String> aliases = new ArrayList<>();
        if (definition != null) {
            if (StringUtils.hasText(definition.getTitle())) {
                aliases.add(definition.getTitle().trim());
            }
            if (StringUtils.hasText(definition.getName())) {
                aliases.add(definition.getName().trim());
            }
        }
        if (startSlot) {
            aliases.addAll(List.of("开始时间", "开始日期", "开始", "起始时间", "起始日期", "起始"));
        }
        else {
            aliases.addAll(List.of("结束时间", "结束日期", "结束", "截至时间", "截至日期", "截至", "截止时间", "截止日期", "截止"));
        }
        aliases.sort((left, right) -> Integer.compare(right.length(), left.length()));
        return aliases;
    }

    private String parseExplicitDateTimeValue(String explicitValue,
                                              LocalDate anchorDate,
                                              Map<String, SlotValue> collectedSlots,
                                              String slotName,
                                              String peerSlotName) {
        if (!StringUtils.hasText(explicitValue)) {
            return null;
        }
        String normalized = explicitValue.trim().replace('T', ' ');
        if (normalized.matches("20\\d{2}-\\d{1,2}-\\d{1,2}\\s+\\d{1,2}:\\d{2}")) {
            try {
                return LocalDateTime.parse(normalized, DATETIME_FORMATTER).format(DATETIME_FORMATTER);
            }
            catch (DateTimeParseException ignored) {
                // fall through to tolerant parsing below
            }
        }

        LocalTime time = extractSingleTime(normalized);
        LocalDate date = resolveDateToken(normalized, anchorDate);
        if (date == null) {
            date = extractSingleDate(normalized, anchorDate);
        }
        if (date == null) {
            date = resolveDateFromCollectedSlot(collectedSlots, slotName);
        }
        if (date == null) {
            date = resolveDateFromCollectedSlot(collectedSlots, peerSlotName);
        }
        if (date == null && time != null) {
            date = anchorDate;
        }
        if (date != null && time != null) {
            return date.atTime(time).format(DATETIME_FORMATTER);
        }
        if (date != null) {
            return date.toString();
        }
        return null;
    }

    private LocalTime extractSingleTime(String input) {
        if (!StringUtils.hasText(input)) {
            return null;
        }
        Matcher clockMatcher = CLOCK_TIME_PATTERN.matcher(input);
        if (clockMatcher.find()) {
            int hour = Integer.parseInt(clockMatcher.group(1));
            int minute = Integer.parseInt(clockMatcher.group(2));
            if (hour >= 0 && hour <= 23 && minute >= 0 && minute <= 59) {
                return LocalTime.of(hour, minute);
            }
        }
        Matcher relativeTimeMatcher = RELATIVE_TIME_POINT_PATTERN.matcher(input);
        if (!relativeTimeMatcher.find()) {
            return null;
        }
        return parseTimeValue(
                relativeTimeMatcher.group(1),
                relativeTimeMatcher.group(2),
                relativeTimeMatcher.group(3));
    }

    private LocalDate resolveDateToken(String rawToken, LocalDate anchorDate) {
        if (!StringUtils.hasText(rawToken)) {
            return null;
        }
        LocalDate relativeDate = parseRelativeDateToken(rawToken, anchorDate);
        return relativeDate != null ? relativeDate : parseDateText(rawToken);
    }

    private LocalDate resolveDateFromCollectedSlot(Map<String, SlotValue> collectedSlots, String slotName) {
        Object rawValue = readCollectedValue(collectedSlots, slotName);
        if (!hasTextValue(rawValue)) {
            return null;
        }
        return parseDateText(String.valueOf(rawValue));
    }

    private LocalTime parseTimeValue(String periodText, String hourText, String minuteText) {
        Integer hour = parseLocalizedInteger(hourText);
        if (hour == null) {
            return null;
        }
        int minute = parseMinuteValue(minuteText);
        if (StringUtils.hasText(periodText)) {
            if (("下午".equals(periodText) || "晚上".equals(periodText)) && hour < 12) {
                hour += 12;
            }
            else if ("中午".equals(periodText) && hour < 11) {
                hour += 12;
            }
            else if ("上午".equals(periodText) && hour == 12) {
                hour = 0;
            }
        }
        if (hour < 0 || hour > 23 || minute < 0 || minute > 59) {
            return null;
        }
        return LocalTime.of(hour, minute);
    }

    private int parseMinuteValue(String rawMinute) {
        if (!StringUtils.hasText(rawMinute)) {
            return 0;
        }
        String normalized = rawMinute.trim();
        if ("半".equals(normalized)) {
            return 30;
        }
        if ("一刻".equals(normalized)) {
            return 15;
        }
        if ("三刻".equals(normalized)) {
            return 45;
        }
        normalized = normalized.replace("分", "");
        Integer minute = parseLocalizedInteger(normalized);
        return minute != null ? minute : 0;
    }

    private Integer parseLocalizedInteger(String rawNumber) {
        if (!StringUtils.hasText(rawNumber)) {
            return null;
        }
        String normalized = rawNumber.trim();
        if (normalized.matches("\\d+")) {
            return Integer.parseInt(normalized);
        }
        Map<Character, Integer> digits = new HashMap<>();
        digits.put('零', 0);
        digits.put('一', 1);
        digits.put('二', 2);
        digits.put('两', 2);
        digits.put('三', 3);
        digits.put('四', 4);
        digits.put('五', 5);
        digits.put('六', 6);
        digits.put('七', 7);
        digits.put('八', 8);
        digits.put('九', 9);
        int result = 0;
        int current = 0;
        for (char ch : normalized.toCharArray()) {
            if (ch == '十') {
                result += (current == 0 ? 1 : current) * 10;
                current = 0;
                continue;
            }
            Integer digit = digits.get(ch);
            if (digit == null) {
                return null;
            }
            current = digit;
        }
        return result + current;
    }

    private record DateTimeRange(String start, String end) {
    }

    private void applyEnumOptionFallbacks(Map<String, Object> extracted,
                                              List<SlotDefinition> slotDefinitions,
                                              String userInput,
                                              Map<String, SlotValue> collectedSlots) {
        if (!StringUtils.hasText(userInput) || slotDefinitions == null || slotDefinitions.isEmpty()) {
            return;
        }
        String normalizedInput = normalizeOptionText(userInput);
        for (SlotDefinition slotDefinition : slotDefinitions) {
            if (slotDefinition == null || !StringUtils.hasText(slotDefinition.getName()) || slotDefinition.getOptions() == null) {
                continue;
            }
            if (slotDefinition.getOptions().getSource() != SlotOptions.SourceType.ENUM
                    || slotDefinition.getOptions().getEnumMapping() == null
                    || slotDefinition.getOptions().getEnumMapping().isEmpty()) {
                continue;
            }
            if (hasTextValue(extracted.get(slotDefinition.getName()))
                    || hasTextValue(readCollectedValue(collectedSlots, slotDefinition.getName()))) {
                continue;
            }
            for (Map.Entry<String, Object> entry : slotDefinition.getOptions().getEnumMapping().entrySet()) {
                String normalizedLabel = normalizeOptionText(entry.getKey());
                if (StringUtils.hasText(normalizedLabel) && normalizedInput.contains(normalizedLabel)) {
                    extracted.put(slotDefinition.getName(), entry.getValue());
                    break;
                }
            }
        }
    }

    private void applyReasonFallback(Map<String, Object> extracted,
                                     List<SlotDefinition> slotDefinitions,
                                     String userInput) {
        String reasonSlotName = resolveSlotName(slotDefinitions, "reason", "leave_reason", "leaveReason");
        if (!StringUtils.hasText(reasonSlotName) || hasTextValue(extracted.get(reasonSlotName))) {
            return;
        }
        String reason = inferReason(userInput);
        if (StringUtils.hasText(reason)) {
            extracted.put(reasonSlotName, reason);
        }
    }

    private void applyStructuredTextFallbacks(Map<String, Object> extracted,
                                              List<SlotDefinition> slotDefinitions,
                                              String userInput,
                                              Map<String, SlotValue> collectedSlots) {
        String titleSlotName = resolveSlotName(slotDefinitions, "title", "subject", "topic");
        if (StringUtils.hasText(titleSlotName) && !hasTextValue(extracted.get(titleSlotName))) {
            String title = inferStructuredTitle(userInput);
            if (StringUtils.hasText(title)) {
                extracted.put(titleSlotName, title);
            }
        }
        applyFreeTextSlotFallback(extracted, slotDefinitions, userInput, collectedSlots);
    }

    private String inferStructuredTitle(String userInput) {
        if (!StringUtils.hasText(userInput)) {
            return null;
        }
        Matcher titleMatcher = EXPLICIT_TITLE_PATTERN.matcher(userInput);
        if (!titleMatcher.find()) {
            return null;
        }
        String title = titleMatcher.group(1);
        if (!StringUtils.hasText(title)) {
            return null;
        }
        return title.replaceAll("[。；;]$", "").trim();
    }

    // 多轮续聊里，如果当前只有一个主文本槽位待补充，则直接把用户自然语言吸收到该槽位。
    // 这样工作汇报、说明类表单不再依赖模型先把自由文本翻译成结构化字段。
    private void applyFreeTextSlotFallback(Map<String, Object> extracted,
                                           List<SlotDefinition> slotDefinitions,
                                           String userInput,
                                           Map<String, SlotValue> collectedSlots) {
        if (!StringUtils.hasText(userInput) || slotDefinitions == null || slotDefinitions.isEmpty()) {
            return;
        }

        String normalizedInput = normalizeOptionText(userInput);
        if (!StringUtils.hasText(normalizedInput)) {
            return;
        }

        Map<String, SlotValue> safeCollectedSlots = collectedSlots != null ? collectedSlots : Collections.emptyMap();
        List<SlotDefinition> pendingTextSlots = resolvePendingFreeTextSlots(slotDefinitions, safeCollectedSlots, extracted);
        if (pendingTextSlots.isEmpty()) {
            return;
        }

        SlotDefinition targetSlot = resolvePreferredFreeTextTarget(pendingTextSlots, normalizedInput);
        if (targetSlot == null || hasTextValue(extracted.get(targetSlot.getName()))) {
            return;
        }

        boolean hasFieldCue = hasFieldCue(targetSlot, normalizedInput);
        if (!isLikelyFreeTextAnswer(userInput, normalizedInput, hasFieldCue)) {
            return;
        }

        String freeTextValue = sanitizeFreeTextAnswer(userInput, targetSlot);
        if (StringUtils.hasText(freeTextValue)) {
            extracted.put(targetSlot.getName(), freeTextValue);
        }
    }

    private List<SlotDefinition> resolvePendingFreeTextSlots(List<SlotDefinition> slotDefinitions,
                                                             Map<String, SlotValue> collectedSlots,
                                                             Map<String, Object> currentExtracted) {
        List<SlotDefinition> orderedCandidates = slotCollectorService.getNextSlotsToCollect(
                slotDefinitions,
                collectedSlots,
                CollectBehavior.defaults(),
                1);
        if (orderedCandidates == null || orderedCandidates.isEmpty()) {
            orderedCandidates = slotDefinitions;
        }
        return orderedCandidates.stream()
                .filter(this::isFreeTextSlotCandidate)
                .filter(definition -> !hasTextValue(readCollectedValue(collectedSlots, definition.getName())))
                .filter(definition -> currentExtracted == null || !hasTextValue(currentExtracted.get(definition.getName())))
                .collect(Collectors.toList());
    }

    private SlotDefinition resolvePreferredFreeTextTarget(List<SlotDefinition> pendingTextSlots,
                                                          String normalizedInput) {
        if (pendingTextSlots == null || pendingTextSlots.isEmpty()) {
            return null;
        }

        List<SlotDefinition> cueTargets = pendingTextSlots.stream()
                .filter(definition -> hasFieldCue(definition, normalizedInput))
                .collect(Collectors.toList());
        if (cueTargets.size() == 1) {
            return cueTargets.get(0);
        }

        List<SlotDefinition> primaryTargets = pendingTextSlots.stream()
                .filter(this::isPrimaryFreeTextSlot)
                .collect(Collectors.toList());
        if (primaryTargets.size() == 1) {
            return primaryTargets.get(0);
        }

        return pendingTextSlots.size() == 1 ? pendingTextSlots.get(0) : null;
    }

    private boolean isFreeTextSlotCandidate(SlotDefinition definition) {
        if (definition == null || !StringUtils.hasText(definition.getName())) {
            return false;
        }
        if (!"string".equalsIgnoreCase(definition.getType())) {
            return false;
        }
        if (definition.hasOptions() || definition.isComputed()) {
            return false;
        }
        String uiComponent = firstNonEmpty(definition.getUiComponent(), "text");
        return !"hidden".equalsIgnoreCase(uiComponent)
                && !"date".equalsIgnoreCase(uiComponent)
                && !"datetime".equalsIgnoreCase(uiComponent)
                && !"select".equalsIgnoreCase(uiComponent)
                && !"radio".equalsIgnoreCase(uiComponent)
                && !"checkbox".equalsIgnoreCase(uiComponent)
                && !"number".equalsIgnoreCase(uiComponent);
    }

    private boolean isPrimaryFreeTextSlot(SlotDefinition definition) {
        if (definition == null) {
            return false;
        }
        return definition.isRequired()
                || definition.getPriority() == com.alibaba.assistant.agent.slot.model.SlotPriority.CORE
                || definition.getAskMode() == SlotAskMode.BATCH;
    }

    private boolean isLikelyFreeTextAnswer(String userInput,
                                           String normalizedInput,
                                           boolean hasFieldCue) {
        if (hasFieldCue) {
            return true;
        }
        if (!StringUtils.hasText(normalizedInput)) {
            return false;
        }
        if (isControlReply(normalizedInput)) {
            return false;
        }
        if (isShortSelectionLikeInput(normalizedInput)) {
            return false;
        }
        if (looksLikeDateOrTimeInput(userInput, normalizedInput)) {
            return false;
        }
        return !looksLikeIntentDirective(normalizedInput);
    }

    private boolean isControlReply(String normalizedInput) {
        return "确认".equals(normalizedInput)
                || "确定".equals(normalizedInput)
                || "可以".equals(normalizedInput)
                || "好的".equals(normalizedInput)
                || "继续".equals(normalizedInput)
                || "提交".equals(normalizedInput)
                || "取消".equals(normalizedInput)
                || "不用".equals(normalizedInput)
                || "ok".equalsIgnoreCase(normalizedInput)
                || "yes".equalsIgnoreCase(normalizedInput)
                || "no".equalsIgnoreCase(normalizedInput);
    }

    private boolean isShortSelectionLikeInput(String normalizedInput) {
        if (!StringUtils.hasText(normalizedInput)) {
            return false;
        }
        return normalizedInput.length() <= 3;
    }

    private boolean looksLikeDateOrTimeInput(String userInput, String normalizedInput) {
        if (!StringUtils.hasText(userInput) && !StringUtils.hasText(normalizedInput)) {
            return false;
        }
        return RELATIVE_DATE_RANGE_PATTERN.matcher(firstNonEmpty(userInput, "")).find()
                || RELATIVE_TIME_RANGE_PATTERN.matcher(firstNonEmpty(userInput, "")).find()
                || ABSOLUTE_DATE_PATTERN.matcher(firstNonEmpty(userInput, "")).find()
                || "今天".equals(normalizedInput)
                || "明天".equals(normalizedInput)
                || "后天".equals(normalizedInput)
                || "大后天".equals(normalizedInput)
                || "本周".equals(normalizedInput)
                || "本月".equals(normalizedInput)
                || "下周".equals(normalizedInput)
                || "下月".equals(normalizedInput);
    }

    private boolean looksLikeIntentDirective(String normalizedInput) {
        if (!StringUtils.hasText(normalizedInput)) {
            return false;
        }
        return normalizedInput.startsWith("我要")
                || normalizedInput.startsWith("我想")
                || normalizedInput.startsWith("请帮我")
                || normalizedInput.startsWith("帮我")
                || normalizedInput.startsWith("请")
                || normalizedInput.startsWith("发起")
                || normalizedInput.startsWith("提交")
                || normalizedInput.startsWith("创建")
                || normalizedInput.startsWith("新增")
                || normalizedInput.startsWith("申请")
                || normalizedInput.startsWith("查询")
                || normalizedInput.startsWith("查看")
                || normalizedInput.startsWith("写汇报")
                || normalizedInput.startsWith("写周报")
                || normalizedInput.startsWith("写日报")
                || normalizedInput.startsWith("写月报");
    }

    private String sanitizeFreeTextAnswer(String userInput, SlotDefinition definition) {
        if (!StringUtils.hasText(userInput)) {
            return null;
        }
        String sanitized = stripLeadingFieldCue(userInput.trim(), definition)
                .replaceFirst("^(?:就写|写成|改成|填成|改为|更新为|补充)\s*", "")
                .replaceAll("^[：:，,；;\s]+", "")
                .replaceAll("[。；;\s]+$", "")
                .trim();
        return StringUtils.hasText(sanitized) ? sanitized : null;
    }

    private String stripLeadingFieldCue(String userInput, SlotDefinition definition) {
        if (!StringUtils.hasText(userInput) || definition == null) {
            return userInput;
        }
        String stripped = userInput;
        List<String> aliases = new ArrayList<>();
        if (StringUtils.hasText(definition.getTitle())) {
            aliases.add(definition.getTitle());
        }
        if (StringUtils.hasText(definition.getName())) {
            aliases.add(definition.getName());
        }
        for (String alias : aliases) {
            stripped = stripped.replaceFirst(
                    "^(?:请)?\s*" + Pattern.quote(alias.trim()) + "\s*(?:是|为|改成|改为|填写|填成|写成|写|：|:)?\s*",
                    "");
        }
        return stripped;
    }

    private void applyNumericFallbacks(Map<String, Object> extracted,
                                       List<SlotDefinition> slotDefinitions,
                                       String userInput) {
        Integer headCount = inferHeadCount(userInput);
        if (headCount == null || slotDefinitions == null || slotDefinitions.isEmpty()) {
            return;
        }
        for (SlotDefinition definition : slotDefinitions) {
            if (definition == null || !StringUtils.hasText(definition.getName())) {
                continue;
            }
            if (hasTextValue(extracted.get(definition.getName())) || !isHeadCountSlot(definition)) {
                continue;
            }
            extracted.put(definition.getName(), headCount);
            return;
        }
    }

    private Integer inferHeadCount(String userInput) {
        if (!StringUtils.hasText(userInput)) {
            return null;
        }
        Matcher matcher = HEADCOUNT_PATTERN.matcher(userInput);
        if (!matcher.find()) {
            return null;
        }
        return parseLocalizedInteger(matcher.group(1));
    }

    private boolean isHeadCountSlot(SlotDefinition definition) {
        if (definition == null) {
            return false;
        }
        String normalizedName = normalizeOptionText(definition.getName());
        String normalizedTitle = normalizeOptionText(definition.getTitle());
        return (StringUtils.hasText(normalizedName)
                && (normalizedName.contains("num") || normalizedName.contains("count") || normalizedName.contains("people")))
                || (StringUtils.hasText(normalizedTitle)
                && (normalizedTitle.contains("人数") || normalizedTitle.contains("参会人")));
    }

    // 选项槽位优先按值命中，再结合字段提示和歧义约束判断，避免用户必须重复说字段名。
    private void applySelectableOptionFallbacks(Map<String, Object> extracted,
                                                List<SlotDefinition> slotDefinitions,
                                                List<EnrichedSlot> previousEnrichedSlots,
                                                String userInput,
                                                Map<String, SlotValue> collectedSlots,
                                                Set<String> explicitStructuredSlotNames) {
        if (slotDefinitions == null || slotDefinitions.isEmpty() || previousEnrichedSlots == null
                || previousEnrichedSlots.isEmpty()) {
            return;
        }

        String normalizedInput = normalizeOptionText(userInput);
        if (!StringUtils.hasText(normalizedInput)) {
            return;
        }

        Map<String, EnrichedSlot> enrichedByName = new LinkedHashMap<>();
        for (EnrichedSlot enrichedSlot : previousEnrichedSlots) {
            if (enrichedSlot == null || enrichedSlot.getDefinition() == null
                    || !StringUtils.hasText(enrichedSlot.getDefinition().getName())) {
                continue;
            }
            enrichedByName.putIfAbsent(enrichedSlot.getDefinition().getName(), enrichedSlot);
        }

        Map<String, OptionMatchResult> matchesBySlot = new LinkedHashMap<>();
        Map<String, Boolean> fieldCueBySlot = new LinkedHashMap<>();
        for (SlotDefinition definition : slotDefinitions) {
            if (definition == null || !StringUtils.hasText(definition.getName())) {
                continue;
            }
            EnrichedSlot enrichedSlot = enrichedByName.get(definition.getName());
            if (enrichedSlot == null || enrichedSlot.getOptions() == null || enrichedSlot.getOptions().isEmpty()) {
                continue;
            }

            OptionMatchResult optionMatch = matchOptionsFromInput(
                    normalizedInput,
                    enrichedSlot.getOptions(),
                    isMultiValueSlot(definition));
            if (optionMatch.hasMatches()) {
                matchesBySlot.put(definition.getName(), optionMatch);
            }
            fieldCueBySlot.put(definition.getName(), hasFieldCue(definition, normalizedInput));
        }

        for (SlotDefinition definition : slotDefinitions) {
            if (definition == null || !StringUtils.hasText(definition.getName())) {
                continue;
            }
            EnrichedSlot enrichedSlot = enrichedByName.get(definition.getName());
            if (enrichedSlot == null || enrichedSlot.getOptions() == null || enrichedSlot.getOptions().isEmpty()) {
                continue;
            }

            OptionMatchResult optionMatch = matchesBySlot.get(definition.getName());
            boolean hasFieldCue = Boolean.TRUE.equals(fieldCueBySlot.get(definition.getName()));
            Object existingValue = readCollectedValue(collectedSlots, definition.getName());
            Object currentValue = extracted.get(definition.getName());
            Object normalizedValue = normalizeOptionValue(currentValue, enrichedSlot.getOptions());
            boolean explicitStructuredInput = explicitStructuredSlotNames != null
                    && explicitStructuredSlotNames.contains(definition.getName());
            if (currentValue != null) {
                if (!shouldAcceptCurrentOptionValue(
                        definition,
                        normalizedValue,
                        existingValue,
                        optionMatch,
                        hasFieldCue,
                        explicitStructuredInput,
                        matchesBySlot,
                        enrichedSlot.getOptions())) {
                    extracted.remove(definition.getName());
                    continue;
                }
                if (!java.util.Objects.equals(currentValue, normalizedValue)) {
                    extracted.put(definition.getName(), normalizedValue);
                }
                continue;
            }

            if (!isTargetedOptionSlot(definition, optionMatch, hasFieldCue, matchesBySlot)) {
                continue;
            }
            Object resolvedValue = resolveMatchedOptionValue(
                    definition,
                    optionMatch,
                    existingValue,
                    userInput,
                    enrichedSlot.getOptions());
            if (resolvedValue != null) {
                extracted.put(definition.getName(), resolvedValue);
            }
        }
    }

    private boolean shouldAcceptCurrentOptionValue(SlotDefinition definition,
                                                   Object normalizedValue,
                                                   Object existingValue,
                                                   OptionMatchResult optionMatch,
                                                   boolean hasFieldCue,
                                                   boolean explicitStructuredInput,
                                                   Map<String, OptionMatchResult> matchesBySlot,
                                                   List<SlotOption> options) {
        if (definition == null || normalizedValue == null) {
            return false;
        }
        // 前端结构化表单已经显式给出字段值时，运行时应直接采信，不再要求文案里再次出现字段提示。
        if (explicitStructuredInput) {
            return true;
        }
        if (optionValuesEquivalent(existingValue, normalizedValue, options, isMultiValueSlot(definition))) {
            return true;
        }
        return isTargetedOptionSlot(definition, optionMatch, hasFieldCue, matchesBySlot);
    }

    private boolean isTargetedOptionSlot(SlotDefinition definition,
                                         OptionMatchResult optionMatch,
                                         boolean hasFieldCue,
                                         Map<String, OptionMatchResult> matchesBySlot) {
        if (definition == null) {
            return false;
        }
        if (hasFieldCue) {
            return true;
        }
        return optionMatch != null
                && optionMatch.hasMatches()
                && isUnambiguousOptionMatch(definition.getName(), optionMatch, matchesBySlot);
    }

    private boolean optionValuesEquivalent(Object left,
                                           Object right,
                                           List<SlotOption> options,
                                           boolean multiValueSlot) {
        if (left == null || right == null || options == null || options.isEmpty()) {
            return false;
        }
        if (!multiValueSlot) {
            Object normalizedLeft = normalizeOptionValue(left, options);
            Object normalizedRight = normalizeOptionValue(right, options);
            return normalizedLeft != null
                    && normalizedRight != null
                    && String.valueOf(normalizedLeft).equals(String.valueOf(normalizedRight));
        }
        java.util.LinkedHashSet<String> normalizedLeft = new java.util.LinkedHashSet<>(
                normalizeExistingOptionValues(left, options).stream()
                        .map(String::valueOf)
                        .collect(Collectors.toList()));
        java.util.LinkedHashSet<String> normalizedRight = new java.util.LinkedHashSet<>(
                normalizeExistingOptionValues(right, options).stream()
                        .map(String::valueOf)
                        .collect(Collectors.toList()));
        return !normalizedLeft.isEmpty() && normalizedLeft.equals(normalizedRight);
    }

    private Object normalizeOptionValue(Object rawValue, List<SlotOption> options) {
        if (rawValue == null || options == null || options.isEmpty()) {
            return null;
        }
        if (rawValue instanceof List<?> values) {
            List<Object> normalizedValues = new ArrayList<>();
            for (Object value : values) {
                collectNormalizedOptionValues(normalizedValues, value, options);
            }
            return normalizedValues.isEmpty() ? null : normalizedValues;
        }
        return normalizeSingleOptionValue(rawValue, options);
    }

    private void collectNormalizedOptionValues(List<Object> target, Object rawValue, List<SlotOption> options) {
        if (target == null || rawValue == null || options == null || options.isEmpty()) {
            return;
        }
        if (rawValue instanceof List<?> values) {
            for (Object value : values) {
                collectNormalizedOptionValues(target, value, options);
            }
            return;
        }
        String rawText = asText(rawValue);
        if (!StringUtils.hasText(rawText)) {
            return;
        }
        String[] segments = rawText.split("[,，、]");
        if (segments.length > 1) {
            for (String segment : segments) {
                collectNormalizedOptionValues(target, segment.trim(), options);
            }
            return;
        }
        addDistinctValue(target, normalizeSingleOptionValue(rawText, options));
    }

    private Object normalizeSingleOptionValue(Object rawValue, List<SlotOption> options) {
        if (rawValue == null || options == null || options.isEmpty()) {
            return null;
        }
        String rawText = asText(rawValue);
        if (!StringUtils.hasText(rawText)) {
            return null;
        }
        String normalizedRawText = normalizeOptionText(rawText);
        for (SlotOption option : options) {
            if (option == null) {
                continue;
            }
            String optionValue = option.getValue() != null ? String.valueOf(option.getValue()) : null;
            if (StringUtils.hasText(optionValue) && optionValue.equals(rawText)) {
                return option.getValue();
            }
            for (String alias : buildOptionAliases(option)) {
                if (alias.equals(normalizedRawText)) {
                    return option.getValue();
                }
            }
        }
        return null;
    }

    private boolean hasFieldCue(SlotDefinition definition, String normalizedInput) {
        if (!StringUtils.hasText(normalizedInput) || definition == null) {
            return false;
        }
        for (String alias : buildFieldAliases(definition)) {
            if (StringUtils.hasText(alias) && normalizedInput.contains(alias)) {
                return true;
            }
        }
        return false;
    }

    private List<String> buildFieldAliases(SlotDefinition definition) {
        if (definition == null) {
            return List.of();
        }
        java.util.LinkedHashSet<String> aliases = new java.util.LinkedHashSet<>();
        if (definition.getAliases() != null) {
            for (String alias : definition.getAliases()) {
                addFieldAlias(aliases, alias);
            }
        }
        addFieldAlias(aliases, definition.getTitle());
        addFieldAlias(aliases, definition.getName());
        String normalizedTitle = normalizeOptionText(definition.getTitle());
        if (StringUtils.hasText(normalizedTitle)) {
            if (normalizedTitle.endsWith("人员")) {
                aliases.add(normalizedTitle.substring(0, normalizedTitle.length() - 1));
            }
            if (normalizedTitle.startsWith("会议") && normalizedTitle.length() > 2) {
                aliases.add(normalizedTitle.substring(2));
            }
        }
        return new ArrayList<>(aliases);
    }

    private void addFieldAlias(java.util.LinkedHashSet<String> aliases, String rawAlias) {
        String normalized = normalizeOptionText(rawAlias);
        if (StringUtils.hasText(normalized)) {
            aliases.add(normalized);
        }
    }

    private OptionMatchResult matchOptionsFromInput(String normalizedInput,
                                                    List<SlotOption> options,
                                                    boolean multiValueSlot) {
        if (!StringUtils.hasText(normalizedInput) || options == null || options.isEmpty()) {
            return OptionMatchResult.empty();
        }
        List<MatchedOption> matches = new ArrayList<>();
        for (SlotOption option : options) {
            if (option == null) {
                continue;
            }
            String bestAlias = null;
            for (String alias : buildOptionAliases(option)) {
                if (!StringUtils.hasText(alias) || alias.length() < 2) {
                    continue;
                }
                if (normalizedInput.contains(alias) && (bestAlias == null || alias.length() > bestAlias.length())) {
                    bestAlias = alias;
                }
            }
            if (bestAlias != null) {
                matches.add(new MatchedOption(option, bestAlias));
            }
        }
        if (matches.isEmpty()) {
            return OptionMatchResult.empty();
        }
        if (!multiValueSlot) {
            MatchedOption bestMatch = matches.stream()
                    .max((left, right) -> Integer.compare(left.alias().length(), right.alias().length()))
                    .orElse(null);
            if (bestMatch == null) {
                return OptionMatchResult.empty();
            }
            return OptionMatchResult.single(bestMatch.option(), bestMatch.alias());
        }
        return OptionMatchResult.multi(matches);
    }

    private boolean isUnambiguousOptionMatch(String slotName,
                                             OptionMatchResult currentMatch,
                                             Map<String, OptionMatchResult> matchesBySlot) {
        if (currentMatch == null || !currentMatch.hasMatches() || matchesBySlot == null || matchesBySlot.isEmpty()) {
            return false;
        }
        for (Map.Entry<String, OptionMatchResult> entry : matchesBySlot.entrySet()) {
            if (!StringUtils.hasText(entry.getKey()) || entry.getKey().equals(slotName)) {
                continue;
            }
            OptionMatchResult otherMatch = entry.getValue();
            if (otherMatch == null || !otherMatch.hasMatches()) {
                continue;
            }
            for (String alias : currentMatch.aliases()) {
                if (otherMatch.aliases().contains(alias)) {
                    return false;
                }
            }
        }
        return true;
    }

    private Object resolveMatchedOptionValue(SlotDefinition definition,
                                             OptionMatchResult optionMatch,
                                             Object existingValue,
                                             String userInput,
                                             List<SlotOption> options) {
        if (definition == null || optionMatch == null || !optionMatch.hasMatches()) {
            return null;
        }
        if (!isMultiValueSlot(definition)) {
            return optionMatch.options().get(0).getValue();
        }

        List<Object> mergedValues = new ArrayList<>();
        if (hasAdditiveIntent(userInput)) {
            mergedValues.addAll(normalizeExistingOptionValues(existingValue, options));
        }
        for (SlotOption option : optionMatch.options()) {
            addDistinctValue(mergedValues, option.getValue());
        }
        return mergedValues;
    }

    private boolean isMultiValueSlot(SlotDefinition definition) {
        if (definition == null) {
            return false;
        }
        return "array".equalsIgnoreCase(definition.getType())
                || (definition.getOptions() != null && definition.getOptions().isMultiSelect());
    }

    private boolean hasAdditiveIntent(String userInput) {
        String normalizedInput = normalizeOptionText(userInput);
        return StringUtils.hasText(normalizedInput)
                && containsAny(normalizedInput, "加上", "加入", "追加", "再加", "还要");
    }

    private List<Object> normalizeExistingOptionValues(Object existingValue, List<SlotOption> options) {
        if (existingValue == null || options == null || options.isEmpty()) {
            return List.of();
        }
        List<Object> normalizedValues = new ArrayList<>();
        collectNormalizedOptionValues(normalizedValues, existingValue, options);
        return normalizedValues;
    }

    private void addDistinctValue(List<Object> target, Object rawValue) {
        if (target == null || rawValue == null) {
            return;
        }
        String normalized = String.valueOf(rawValue);
        boolean exists = target.stream().map(String::valueOf).anyMatch(normalized::equals);
        if (!exists) {
            target.add(rawValue);
        }
    }

    private List<String> buildOptionAliases(SlotOption option) {
        if (option == null) {
            return List.of();
        }
        java.util.LinkedHashSet<String> aliases = new java.util.LinkedHashSet<>();
        addOptionAlias(aliases, option.getLabel());
        addOptionAlias(aliases, stripBracketText(asText(option.getLabel())));
        String label = stripBracketText(asText(option.getLabel()));
        if (StringUtils.hasText(label)) {
            for (String segment : label.split("[-/|,，、]")) {
                addOptionAlias(aliases, segment);
            }
        }
        if (!(option.getValue() instanceof Number)) {
            addOptionAlias(aliases, option.getValue());
        }
        return new ArrayList<>(aliases);
    }

    private void addOptionAlias(java.util.LinkedHashSet<String> aliases, Object rawValue) {
        if (aliases == null) {
            return;
        }
        String normalized = normalizeOptionText(rawValue);
        if (StringUtils.hasText(normalized)) {
            aliases.add(normalized);
        }
    }

    private String stripBracketText(String text) {
        if (!StringUtils.hasText(text)) {
            return text;
        }
        return text.replaceAll("（[^）]*）|\\([^)]*\\)", "").trim();
    }

    private String normalizeOptionText(Object rawValue) {
        if (rawValue == null) {
            return null;
        }
        String text = String.valueOf(rawValue).trim();
        if (!StringUtils.hasText(text)) {
            return null;
        }
        text = stripBracketText(text);
        text = text.replaceAll("[\\s\\-_/|,，。；：、]+", "");
        return StringUtils.hasText(text) ? text : null;
    }

    private record MatchedOption(SlotOption option, String alias) {
    }

    private static final class OptionMatchResult {

        private final List<SlotOption> options;
        private final List<String> aliases;

        private OptionMatchResult(List<SlotOption> options, List<String> aliases) {
            this.options = options;
            this.aliases = aliases;
        }

        private static OptionMatchResult empty() {
            return new OptionMatchResult(List.of(), List.of());
        }

        private static OptionMatchResult single(SlotOption option, String alias) {
            return new OptionMatchResult(List.of(option), List.of(alias));
        }

        private static OptionMatchResult multi(List<MatchedOption> matches) {
            List<SlotOption> matchedOptions = new ArrayList<>();
            List<String> matchedAliases = new ArrayList<>();
            for (MatchedOption match : matches) {
                matchedOptions.add(match.option());
                matchedAliases.add(match.alias());
            }
            return new OptionMatchResult(matchedOptions, matchedAliases);
        }

        private boolean hasMatches() {
            return !options.isEmpty();
        }

        private List<SlotOption> options() {
            return options;
        }

        private List<String> aliases() {
            return aliases;
        }
    }

    private boolean shouldMirrorSingleDateEdit(String userInput,

                                               Map<String, SlotValue> collectedSlots,
                                               String startSlotName,
                                               String endSlotName,
                                               Object extractedStart,
                                               Object extractedEnd) {
        if (!hasTextValue(extractedStart) && !hasTextValue(extractedEnd)) {
            return false;
        }
        if (isOneDayLeave(userInput)) {
            return true;
        }
        String normalizedInput = normalizeOptionText(userInput);
        if (!StringUtils.hasText(normalizedInput)) {
            return false;
        }
        if (RELATIVE_DATE_RANGE_PATTERN.matcher(userInput).find()) {
            return false;
        }
        if (containsAny(normalizedInput, "开始日期", "结束日期", "开始时间", "结束时间", "起始", "截至", "到", "至")) {
            return false;
        }
        Object previousStart = readCollectedValue(collectedSlots, startSlotName);
        Object previousEnd = readCollectedValue(collectedSlots, endSlotName);
        if (!hasTextValue(previousStart) || !hasTextValue(previousEnd)) {
            return false;
        }
        return String.valueOf(previousStart).equals(String.valueOf(previousEnd));
    }

    private Object readCollectedValue(Map<String, SlotValue> collectedSlots, String slotName) {
        if (collectedSlots == null || !StringUtils.hasText(slotName)) {
            return null;
        }
        SlotValue slotValue = collectedSlots.get(slotName);
        return slotValue != null ? slotValue.getResolvedValue() : null;
    }

    private String resolveSlotName(List<SlotDefinition> slotDefinitions, String... candidates) {
        if (slotDefinitions == null || slotDefinitions.isEmpty() || candidates == null || candidates.length == 0) {
            return null;
        }
        for (String candidate : candidates) {
            if (!StringUtils.hasText(candidate)) {
                continue;
            }
            for (SlotDefinition definition : slotDefinitions) {
                if (definition != null
                        && StringUtils.hasText(definition.getName())
                        && candidate.equalsIgnoreCase(definition.getName().trim())) {
                    return definition.getName().trim();
                }
            }
        }
        return null;
    }

    private boolean hasTextValue(Object value) {
        if (value == null) {
            return false;
        }
        return StringUtils.hasText(String.valueOf(value));
    }

    private String resolveUserInput(OverAllState state) {
        return ConversationUserInputResolver.resolve(state);
    }

    private LocalDate resolveAnchorDate(OverAllState state) {
        String dateText = firstNonEmpty(
                readLooseStateText(state, "current_date"),
                readLooseStateText(state, "currentDate"),
                readLooseStateText(state, "current_time"),
                readLooseStateText(state, "currentTime"),
                readLooseStateText(state, "now"));
        LocalDate parsed = parseDateText(dateText);
        return parsed != null ? parsed : LocalDate.now();
    }

    private LocalDate parseDateText(String text) {
        if (!StringUtils.hasText(text)) {
            return null;
        }

        Matcher absoluteMatcher = ABSOLUTE_DATE_PATTERN.matcher(text);
        if (absoluteMatcher.find()) {
            try {
                int year = Integer.parseInt(absoluteMatcher.group(1));
                int month = Integer.parseInt(absoluteMatcher.group(2));
                int day = Integer.parseInt(absoluteMatcher.group(3));
                return LocalDate.of(year, month, day);
            }
            catch (Exception ignored) {
                return null;
            }
        }

        String normalized = text.trim();
        if (normalized.length() >= 10) {
            normalized = normalized.substring(0, 10);
        }
        try {
            return LocalDate.parse(normalized);
        }
        catch (DateTimeParseException ignored) {
            return null;
        }
    }

    private LocalDate parseRelativeDateToken(String token, LocalDate anchorDate) {
        if (!StringUtils.hasText(token)) {
            return null;
        }
        String normalized = token.trim();
        if ("今天".equals(normalized) || "今日".equals(normalized)) {
            return anchorDate;
        }
        if ("明天".equals(normalized)) {
            return anchorDate.plusDays(1);
        }
        if ("后天".equals(normalized)) {
            return anchorDate.plusDays(2);
        }
        if ("大后天".equals(normalized)) {
            return anchorDate.plusDays(3);
        }
        return null;
    }

    private LocalDate extractSingleDate(String input, LocalDate anchorDate) {
        if (!StringUtils.hasText(input)) {
            return null;
        }

        Matcher absoluteMatcher = ABSOLUTE_DATE_PATTERN.matcher(input);
        if (absoluteMatcher.find()) {
            try {
                int year = Integer.parseInt(absoluteMatcher.group(1));
                int month = Integer.parseInt(absoluteMatcher.group(2));
                int day = Integer.parseInt(absoluteMatcher.group(3));
                return LocalDate.of(year, month, day);
            }
            catch (Exception ignored) {
                return null;
            }
        }

        if (input.contains("大后天")) {
            return anchorDate.plusDays(3);
        }
        if (input.contains("后天")) {
            return anchorDate.plusDays(2);
        }
        if (input.contains("明天")) {
            return anchorDate.plusDays(1);
        }
        if (input.contains("今天") || input.contains("今日")) {
            return anchorDate;
        }
        return null;
    }

    private boolean isOneDayLeave(String input) {
        if (!StringUtils.hasText(input)) {
            return false;
        }
        return input.contains("一天")
                || input.contains("1天")
                || input.contains("1 天")
                || input.contains("单天");
    }

    private String inferReason(String input) {
        if (!StringUtils.hasText(input)) {
            return null;
        }

        Matcher explicitReasonMatcher = EXPLICIT_REASON_PATTERN.matcher(input);
        if (explicitReasonMatcher.find()) {
            String explicitReason = sanitizeReason(explicitReasonMatcher.group(1));
            if (StringUtils.hasText(explicitReason)) {
                return explicitReason;
            }
        }

        if (containsAny(input, "有事", "私事", "个人原因", "个人事务", "家里有事", "家中有事")
                || GENERIC_PRIVATE_REASON_PATTERN.matcher(input).find()) {
            return sanitizeReason(input);
        }
        return null;
    }

    private String inferLeaveTypeLabel(String input) {
        return inferLeaveTypeLabel(input, true);
    }

    private String inferExplicitLeaveTypeLabel(String input) {
        return inferLeaveTypeLabel(input, false);
    }

    private String inferLeaveTypeLabel(String input, boolean allowImplicitPrivateReason) {
        if (!StringUtils.hasText(input)) {
            return null;
        }

        String normalizedInput = input.replaceAll("\\s+", "").trim();
        if (normalizedInput.isEmpty()) {
            return null;
        }

        if (containsAny(normalizedInput, "陪产假", "陪产", "陪护生产", "陪老婆生产", "陪老婆生孩子", "陪媳妇生孩子", "陪爱人生孩子")) {
            return "陪产假";
        }
        if (containsAny(normalizedInput, "产假", "待产", "分娩", "生产", "生孩子", "生宝宝", "产后", "保胎")) {
            return "产假";
        }
        if (containsAny(normalizedInput, "婚假", "结婚", "领证", "办婚礼", "婚礼", "办喜酒", "婚宴")) {
            return "婚假";
        }
        if (containsAny(normalizedInput, "丧假", "奔丧", "丧事", "白事", "吊唁", "亲人去世", "家人去世", "老人去世")) {
            return "丧假";
        }
        if (containsAny(normalizedInput, "病假", "发烧", "感冒", "生病", "不舒服", "身体不适", "看病", "就医", "住院", "手术", "复查")) {
            return "病假";
        }
        if (containsAny(normalizedInput, "年假", "年休", "年休假")) {
            return "年假";
        }
        if (containsAny(normalizedInput, "调休假", "调休", "补休")) {
            return "调休假";
        }
        if (normalizedInput.contains("事假")) {
            return "事假";
        }
        if (allowImplicitPrivateReason
                && (containsAny(normalizedInput, "有事", "私事", "个人原因", "家中有事", "家里有事")
                || GENERIC_PRIVATE_REASON_PATTERN.matcher(normalizedInput).find())) {
            return "事假";
        }
        return null;
    }

    private Object mapLeaveTypeLabelToOptionValue(SlotDefinition slotDefinition, String inferredLabel) {
        if (slotDefinition == null
                || slotDefinition.getOptions() == null
                || slotDefinition.getOptions().getEnumMapping() == null
                || slotDefinition.getOptions().getEnumMapping().isEmpty()
                || !StringUtils.hasText(inferredLabel)) {
            return null;
        }
        for (Map.Entry<String, Object> entry : slotDefinition.getOptions().getEnumMapping().entrySet()) {
            if (entry.getKey() != null && inferredLabel.equalsIgnoreCase(entry.getKey().trim())) {
                return entry.getValue();
            }
        }
        return null;
    }

    private SlotDefinition findSlotDefinition(List<SlotDefinition> slotDefinitions, String slotName) {
        if (slotDefinitions == null || slotDefinitions.isEmpty() || !StringUtils.hasText(slotName)) {
            return null;
        }
        for (SlotDefinition definition : slotDefinitions) {
            if (definition != null
                    && StringUtils.hasText(definition.getName())
                    && slotName.equalsIgnoreCase(definition.getName().trim())) {
                return definition;
            }
        }
        return null;
    }

    private boolean containsAny(String text, String... keywords) {
        if (!StringUtils.hasText(text) || keywords == null || keywords.length == 0) {
            return false;
        }
        for (String keyword : keywords) {
            if (StringUtils.hasText(keyword) && text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private String sanitizeReason(String rawReason) {
        if (!StringUtils.hasText(rawReason)) {
            return null;
        }
        String normalized = rawReason.trim();
        normalized = normalized.replaceFirst("^(是|为)", "").trim();
        normalized = normalized.replaceFirst("^(今天|今日|明天|后天|大后天)", "").trim();
        normalized = normalized.replaceFirst("(需要)?请假.*$", "").trim();
        if (!StringUtils.hasText(normalized)) {
            return null;
        }
        return normalized;
    }

    private String asText(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return StringUtils.hasText(text) ? text : null;
    }

    private ToolMetaSnapshot resolveToolMetaSnapshot(Request request, OverAllState state, ToolContext toolContext) {
        ToolMetaSnapshot stateSnapshot = resolveStateToolMetaSnapshot(state);
        if (hasUsableSchema(stateSnapshot)) {
            return stateSnapshot;
        }
        if (!StringUtils.hasText(request.toolCode) || artifactPublicationLookupService == null) {
            return null;
        }
        return artifactPublicationLookupService.findPublishedArtifact(request.toolCode, toolContext)
                .map(this::convertFromPublishedArtifact)
                .filter(this::hasUsableSchema)
                .orElse(null);
    }

    private ToolMetaSnapshot resolveStateToolMetaSnapshot(OverAllState state) {
        if (state == null) {
            return null;
        }
        Object raw = state.value(AssistantStateKeys.MATCHED_TOOL_META, Object.class).orElse(null);
        if (raw == null) {
            return null;
        }
        if (raw instanceof ToolMetaSnapshot snapshot && hasUsableSchema(snapshot)) {
            return snapshot;
        }
        if (raw instanceof ToolMeta toolMeta) {
            ToolMetaSnapshot snapshot = convertFromToolMeta(toolMeta);
            if (hasUsableSchema(snapshot)) {
                return snapshot;
            }
        }
        try {
            ToolMetaSnapshot snapshot = objectMapper.convertValue(raw, ToolMetaSnapshot.class);
            if (hasUsableSchema(snapshot)) {
                return snapshot;
            }
        }
        catch (Exception e) {
            logger.warn("SlotCollectTool#resolveToolMetaSnapshot - cannot convert state meta, error={}", e.getMessage());
        }
        return null;
    }

    private ToolMetaSnapshot resolveToolMetaSnapshotFromRegistry(String toolCode) {
        return findToolMetaByCode(toolCode)
                .map(this::convertFromToolMeta)
                .filter(this::hasUsableSchema)
                .orElse(null);
    }

    private Optional<ToolMeta> findToolMetaByCode(String toolCode) {
        if (!StringUtils.hasText(toolCode) || toolMetaService == null) {
            return Optional.empty();
        }
        return toolMetaService.findLatestEnabledByToolCode(null, toolCode.trim());
    }

    private boolean hasUsableSchema(ToolMetaSnapshot snapshot) {
        return snapshot != null
                && StringUtils.hasText(snapshot.getSlotSchema());
    }

    private ToolMetaSnapshot convertFromPublishedArtifact(PublishedToolDescriptor descriptor) {
        ToolMetaSnapshot snapshot = new ToolMetaSnapshot();
        if (descriptor == null) {
            return snapshot;
        }
        if (descriptor.artifact() != null) {
            snapshot.setToolCode(descriptor.artifact().getArtifactCode());
            if (descriptor.artifact().getInteraction() != null) {
                snapshot.setSlotSchema(descriptor.artifact().getInteraction().slotSchemaJson());
                snapshot.setBehaviorConfig(descriptor.artifact().getInteraction().askStrategyJson());
            }
            snapshot.setSystemCode(descriptor.executionSystemCode());
            return snapshot;
        }
        if (descriptor.directTool() == null) {
            return snapshot;
        }
        snapshot.setToolCode(resolvePublishedDirectToolCode(descriptor));
        org.springframework.ai.tool.definition.ToolDefinition definition = descriptor.directTool().getToolDefinition();
        if (definition != null) {
            applySchema(snapshot, definition.inputSchema());
        }
        snapshot.setSystemCode(descriptor.executionSystemCode());
        return snapshot;
    }

    private ToolMetaSnapshot convertFromToolMeta(ToolMeta toolMeta) {
        ToolMetaSnapshot snapshot = new ToolMetaSnapshot();
        snapshot.setToolCode(toolMeta.getToolCode());
        applySchema(snapshot, toolMeta.getParameterSchema());
        snapshot.setExecutionPlan(toolMeta.getExecutionPlan());
        snapshot.setBehaviorConfig(toolMeta.getInteractionPolicy());
        snapshot.setSystemCode(toolMeta.getSystemCode());
        return snapshot;
    }

    private void applySchema(ToolMetaSnapshot snapshot, String rawSchema) {
        if (snapshot == null || !StringUtils.hasText(rawSchema)) {
            return;
        }
        snapshot.setSlotSchema(rawSchema);
    }

    private DependencyExecution resolveAndExecuteDependencies(ToolMetaSnapshot snapshot,
                                                              String tenantId,
                                                              Map<String, SlotValue> collectedSlots,
                                                              Map<String, Map<String, Object>> cachedDependencyResults,
                                                              String systemCode,
                                                              String assistantUid,
                                                              OverAllState state,
                                                              ToolContext toolContext) {
        List<DependencyResolver.ResolvedStep> steps = resolveDependencySteps(snapshot, tenantId, state, toolContext);
        if (steps.isEmpty()) {
            return new DependencyExecution(
                    cachedDependencyResults != null ? cachedDependencyResults : Collections.emptyMap(),
                    Collections.emptyList());
        }

        List<DependencyResolver.FieldMapping> mappings = collectDependencyMappings(steps);
        if (steps.size() <= 1) {
            return new DependencyExecution(
                    cachedDependencyResults != null ? cachedDependencyResults : Collections.emptyMap(),
                    mappings);
        }

        Map<String, Map<String, Object>> results = new LinkedHashMap<>();
        if (cachedDependencyResults != null && !cachedDependencyResults.isEmpty()) {
            results.putAll(cachedDependencyResults);
        }

        for (int i = 0; i < steps.size() - 1; i++) {
            DependencyResolver.ResolvedStep dependencyStep = steps.get(i);
            if (dependencyStep == null || !StringUtils.hasText(dependencyStep.toolCode())) {
                continue;
            }
            String dependencyKey = normalizeToolCode(dependencyStep.toolCode());
            if (results.containsKey(dependencyKey)) {
                continue;
            }
            if (toolExecutor == null) {
                throw new IllegalStateException("Dependency execution is enabled but ToolExecutor is missing");
            }

            Map<String, Object> dependencyArgs = buildDependencyArgs(
                    collectedSlots, results, systemCode, assistantUid, state);
            ToolExecutor.ExecutionResult executionResult = toolExecutor.execute(
                    tenantId, dependencyStep.toolCode(), dependencyArgs, toolContext);
            if (!executionResult.success()) {
                throw new IllegalStateException("Dependency tool failed: " + dependencyStep.toolCode()
                        + ", reason=" + executionResult.errorMessage());
            }

            results.put(dependencyKey, buildDependencyResultPayload(executionResult));
        }
        return new DependencyExecution(results, mappings);
    }

    private List<DependencyResolver.ResolvedStep> resolveDependencySteps(ToolMetaSnapshot snapshot,
                                                                         String tenantId,
                                                                         OverAllState state,
                                                                         ToolContext toolContext) {
        if (dependencyResolver == null || !StringUtils.hasText(snapshot.getToolCode())) {
            return Collections.emptyList();
        }

        String targetToolCode = snapshot.getToolCode().trim();
        ToolMeta rootMeta = new ToolMeta();
        rootMeta.setToolCode(targetToolCode);
        rootMeta.setDescription(targetToolCode);
        rootMeta.setSystemCode(snapshot.getSystemCode());
        rootMeta.setInteractionPolicy(snapshot.getBehaviorConfig());
        rootMeta.setParameterSchema(snapshot.getSlotSchema());

        ToolMeta finalRootMeta = rootMeta;
        return dependencyResolver.resolve(targetToolCode, toolCode -> {
            if (StringUtils.hasText(toolCode)
                    && targetToolCode.equalsIgnoreCase(toolCode.trim())) {
                return Optional.of(finalRootMeta);
            }
            return resolvePublishedDependencyToolMeta(toolCode, state, toolContext);
        });
    }

    private Optional<ToolMeta> resolvePublishedDependencyToolMeta(String toolCode,
                                                                  OverAllState state,
                                                                  ToolContext toolContext) {
        if (!StringUtils.hasText(toolCode) || artifactPublicationLookupService == null) {
            return Optional.empty();
        }
        ToolContext lookupContext = toolContext;
        if (lookupContext == null && state != null) {
            lookupContext = new ToolContext(Map.of(ToolContextConstants.AGENT_STATE_CONTEXT_KEY, state));
        }
        return artifactPublicationLookupService.findPublishedArtifact(toolCode, lookupContext)
                .map(this::convertPublishedArtifactToToolMeta);
    }

    private ToolMeta convertPublishedArtifactToToolMeta(PublishedToolDescriptor descriptor) {
        ToolMeta toolMeta = new ToolMeta();
        if (descriptor == null) {
            return toolMeta;
        }
        if (descriptor.artifact() != null) {
            toolMeta.setToolCode(descriptor.artifact().getArtifactCode());
            toolMeta.setToolName(firstNonEmpty(
                    descriptor.displayName(),
                    descriptor.artifact().getDisplayName(),
                    descriptor.artifact().getArtifactCode()));
            toolMeta.setDescription(firstNonEmpty(
                    descriptor.displayName(),
                    descriptor.artifact().getDisplayName(),
                    descriptor.artifact().getArtifactCode()));
            toolMeta.setSystemCode(descriptor.executionSystemCode());
            if (descriptor.artifact().getInteraction() != null) {
                toolMeta.setParameterSchema(descriptor.artifact().getInteraction().slotSchemaJson());
                toolMeta.setInteractionPolicy(descriptor.artifact().getInteraction().askStrategyJson());
            }
            return toolMeta;
        }
        if (descriptor.directTool() != null) {
            org.springframework.ai.tool.definition.ToolDefinition definition = descriptor.directTool().getToolDefinition();
            toolMeta.setToolCode(resolvePublishedDirectToolCode(descriptor));
            toolMeta.setToolName(firstNonEmpty(
                    descriptor.displayName(),
                    definition != null ? definition.description() : null,
                    definition != null ? definition.name() : null));
            toolMeta.setDescription(firstNonEmpty(
                    descriptor.displayName(),
                    definition != null ? definition.description() : null,
                    definition != null ? definition.name() : null));
            toolMeta.setSystemCode(descriptor.executionSystemCode());
            if (definition != null) {
                toolMeta.setParameterSchema(definition.inputSchema());
            }
        }
        return toolMeta;
    }

    private String resolvePublishedDirectToolCode(PublishedToolDescriptor descriptor) {
        if (descriptor == null || descriptor.directTool() == null) {
            return null;
        }
        com.alibaba.assistant.agent.common.tools.CodeactToolMetadata metadata = descriptor.directTool().getCodeactMetadata();
        if (metadata != null && metadata.aliases() != null) {
            for (String alias : metadata.aliases()) {
                if (StringUtils.hasText(alias)) {
                    return alias;
                }
            }
        }
        org.springframework.ai.tool.definition.ToolDefinition definition = descriptor.directTool().getToolDefinition();
        return definition != null ? definition.name() : null;
    }
    private List<DependencyResolver.FieldMapping> collectDependencyMappings(List<DependencyResolver.ResolvedStep> steps) {
        if (steps == null || steps.size() <= 1) {
            return Collections.emptyList();
        }
        List<DependencyResolver.FieldMapping> mappings = new ArrayList<>();
        for (int i = 0; i < steps.size() - 1; i++) {
            DependencyResolver.ResolvedStep step = steps.get(i);
            if (step == null || step.mappings() == null || step.mappings().isEmpty()) {
                continue;
            }
            mappings.addAll(step.mappings());
        }
        return mappings;
    }

    private Map<String, Object> buildDependencyArgs(Map<String, SlotValue> collectedSlots,
                                                    Map<String, Map<String, Object>> dependencyResults,
                                                    String systemCode,
                                                    String assistantUid,
                                                    OverAllState state) {
        Map<String, Object> args = mapCollected(collectedSlots);
        if (dependencyResults != null && !dependencyResults.isEmpty()) {
            for (Map<String, Object> result : dependencyResults.values()) {
                if (result == null || result.isEmpty()) {
                    continue;
                }
                for (Map.Entry<String, Object> entry : result.entrySet()) {
                    if (RAW_TOOL_OPTION_PAYLOAD_KEY.equals(entry.getKey())) {
                        continue;
                    }
                    args.putIfAbsent(entry.getKey(), entry.getValue());
                }
            }
        }

        if (StringUtils.hasText(systemCode)) {
            args.putIfAbsent("system_code", systemCode);
            args.putIfAbsent(AssistantStateKeys.SYSTEM_CODE, systemCode);
        }
        if (StringUtils.hasText(assistantUid)) {
            args.putIfAbsent("assistant_uid", assistantUid);
            args.putIfAbsent(AssistantStateKeys.ASSISTANT_UID, assistantUid);
        }
        String threadId = readStringState(state, AssistantStateKeys.THREAD_ID);
        if (StringUtils.hasText(threadId)) {
            args.putIfAbsent("thread_id", threadId);
            args.putIfAbsent(AssistantStateKeys.THREAD_ID, threadId);
        }
        return args;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Map<String, Object>> readDependencyResults(OverAllState state) {
        Map<String, Map<String, Object>> results = new LinkedHashMap<>();
        if (state == null) {
            return results;
        }
        Map<String, Object> raw = state.value(AssistantStateKeys.DEPENDENCY_RESULTS, Map.class).orElse(null);
        if (raw == null || raw.isEmpty()) {
            return results;
        }
        for (Map.Entry<String, Object> entry : raw.entrySet()) {
            if (!StringUtils.hasText(entry.getKey()) || entry.getValue() == null) {
                continue;
            }
            try {
                Map<String, Object> converted = objectMapper.convertValue(entry.getValue(), Map.class);
                if (converted != null) {
                    results.put(normalizeToolCode(entry.getKey()), converted);
                }
            }
            catch (Exception e) {
                logger.warn("SlotCollectTool#readDependencyResults - convert failed, key={}, error={}",
                        entry.getKey(), e.getMessage());
            }
        }
        return results;
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

    @SuppressWarnings("unchecked")
    private Map<String, Object> readCurrentTurnSlotInputs(OverAllState state, List<SlotDefinition> slotDefinitions) {
        if (state == null || slotDefinitions == null || slotDefinitions.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, Object> inputs = new LinkedHashMap<>();
        Map<String, Object> raw = state.value(AssistantStateKeys.CURRENT_TURN_SLOT_INPUTS, Map.class).orElse(null);
        if (raw != null && !raw.isEmpty()) {
            inputs.putAll(filterSupportedSlotInputs(raw, slotDefinitions));
        }
        for (SlotDefinition slotDefinition : slotDefinitions) {
            if (slotDefinition == null || !StringUtils.hasText(slotDefinition.getName())) {
                continue;
            }
            Object flatValue = state.value(slotDefinition.getName(), Object.class).orElse(null);
            if (flatValue != null) {
                inputs.put(slotDefinition.getName(), flatValue);
            }
        }
        return inputs;
    }

    private Map<String, Object> buildComputationMetadata(OverAllState state) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        LocalDate anchorDate = resolveAnchorDate(state);
        metadata.put("current_date", anchorDate.toString());
        return metadata;
    }

    private List<EnrichedSlot> readEnrichedSlots(OverAllState state) {
        List<EnrichedSlot> enrichedSlots = new ArrayList<>();
        if (state == null) {
            return enrichedSlots;
        }

        Object raw = state.value(AssistantStateKeys.ENRICHED_SLOTS, Object.class).orElse(null);
        if (!(raw instanceof List<?> rawList) || rawList.isEmpty()) {
            return enrichedSlots;
        }

        for (Object item : rawList) {
            if (item instanceof EnrichedSlot enrichedSlot) {
                enrichedSlots.add(enrichedSlot);
                continue;
            }
            try {
                enrichedSlots.add(objectMapper.convertValue(item, EnrichedSlot.class));
            }
            catch (Exception e) {
                logger.warn("SlotCollectTool#readEnrichedSlots - failed to convert enriched slot, error={}",
                        e.getMessage());
            }
        }
        return enrichedSlots;
    }

    private SlotEnrichmentResult enrichSlotsSafely(List<SlotDefinition> slotDefinitions,
                                                   String systemCode,
                                                   String assistantUid,
                                                   String tenantId,
                                                   Map<String, SlotValue> collectedSlots,
                                                   Map<String, Map<String, Object>> dependencyResults,
                                                   OverAllState state,
                                                   ToolContext toolContext) {
        List<EnrichedSlot> enrichedSlots = slotDefinitions.stream().map(EnrichedSlot::new).collect(Collectors.toList());
        if (StringUtils.hasText(systemCode) && StringUtils.hasText(assistantUid)) {
            try {
                enrichedSlots = slotEnricherService.enrichSlots(slotDefinitions, systemCode, assistantUid);
            }
            catch (Exception e) {
                logger.warn("SlotCollectTool#enrichSlotsSafely - enrichment failed, error={}", e.getMessage());
            }
        }
        return resolveToolBackedOptions(
                slotDefinitions,
                enrichedSlots,
                tenantId,
                collectedSlots,
                dependencyResults,
                systemCode,
                assistantUid,
                state,
                toolContext);
    }

    private SlotEnrichmentResult resolveToolBackedOptions(List<SlotDefinition> slotDefinitions,
                                                          List<EnrichedSlot> enrichedSlots,
                                                          String tenantId,
                                                          Map<String, SlotValue> collectedSlots,
                                                          Map<String, Map<String, Object>> dependencyResults,
                                                          String systemCode,
                                                          String assistantUid,
                                                          OverAllState state,
                                                          ToolContext toolContext) {
        if (slotDefinitions == null || slotDefinitions.isEmpty()) {
            return new SlotEnrichmentResult(
                    enrichedSlots != null ? enrichedSlots : Collections.emptyList(),
                    dependencyResults != null ? dependencyResults : Collections.emptyMap());
        }

        Map<String, EnrichedSlot> enrichedByName = new LinkedHashMap<>();
        if (enrichedSlots != null) {
            for (EnrichedSlot enrichedSlot : enrichedSlots) {
                if (enrichedSlot != null && enrichedSlot.getDefinition() != null
                        && StringUtils.hasText(enrichedSlot.getDefinition().getName())) {
                    enrichedByName.put(enrichedSlot.getDefinition().getName(), enrichedSlot);
                }
            }
        }

        Map<String, Map<String, Object>> resolvedDependencyResults = dependencyResults != null
                ? new LinkedHashMap<>(dependencyResults)
                : new LinkedHashMap<>();

        for (SlotDefinition definition : slotDefinitions) {
            if (definition == null || !StringUtils.hasText(definition.getName())) {
                continue;
            }
            SlotOptions optionsConfig = definition.getOptions();
            if (optionsConfig == null || optionsConfig.getSource() != SlotOptions.SourceType.TOOL
                    || optionsConfig.getToolConfig() == null) {
                continue;
            }

            ToolOptionResolution resolution = resolveToolOptions(
                    definition,
                    optionsConfig,
                    tenantId,
                    collectedSlots,
                    resolvedDependencyResults,
                    systemCode,
                    assistantUid,
                    state,
                    toolContext);
            if (!resolution.dependencyResults().isEmpty()) {
                resolvedDependencyResults.putAll(resolution.dependencyResults());
            }

            EnrichedSlot target = enrichedByName.computeIfAbsent(definition.getName(), key -> {
                EnrichedSlot enrichedSlot = new EnrichedSlot(definition);
                if (enrichedSlots != null) {
                    enrichedSlots.add(enrichedSlot);
                }
                return enrichedSlot;
            });

            if (!resolution.options().isEmpty()) {
                target.setOptions(resolution.options());
                target.setOptionsError(null);
                continue;
            }
            if (StringUtils.hasText(resolution.errorMessage())) {
                target.setOptionsError(resolution.errorMessage());
            }
        }

        return new SlotEnrichmentResult(
                enrichedSlots != null ? enrichedSlots : Collections.emptyList(),
                resolvedDependencyResults);
    }

    private ToolOptionResolution resolveToolOptions(SlotDefinition definition,
                                                    SlotOptions optionsConfig,
                                                    String tenantId,
                                                    Map<String, SlotValue> collectedSlots,
                                                    Map<String, Map<String, Object>> dependencyResults,
                                                    String systemCode,
                                                    String assistantUid,
                                                    OverAllState state,
                                                    ToolContext toolContext) {
        Map<String, Map<String, Object>> updatedResults = new LinkedHashMap<>();
        Map<String, Object> optionPayload = null;
        String errorMessage = null;

        try {
            String resolverToolCode = optionsConfig.getToolConfig().getToolCode();
            if (StringUtils.hasText(resolverToolCode)) {
                String dependencyKey = normalizeToolCode(resolverToolCode);
                optionPayload = dependencyResults != null ? dependencyResults.get(dependencyKey) : null;
                if ((optionPayload == null || optionPayload.isEmpty()) && toolExecutor != null) {
                    Map<String, Object> dependencyArgs = buildDependencyArgs(
                            collectedSlots,
                            dependencyResults,
                            systemCode,
                            assistantUid,
                            state);
                    ToolExecutor.ExecutionResult executionResult = toolExecutor.execute(
                            tenantId, resolverToolCode, dependencyArgs, toolContext);
                    if (!executionResult.success()) {
                        errorMessage = executionResult.errorMessage();
                    }
                    else {
                        optionPayload = buildDependencyResultPayload(executionResult);
                        updatedResults.put(dependencyKey, optionPayload);
                    }
                }
            }

            if ((optionPayload == null || optionPayload.isEmpty()) && definition.getDependsOn() != null) {
                for (String dependencyToolCode : definition.getDependsOn()) {
                    if (!StringUtils.hasText(dependencyToolCode)) {
                        continue;
                    }
                    Map<String, Object> result = dependencyResults != null
                            ? dependencyResults.get(normalizeToolCode(dependencyToolCode))
                            : null;
                    if (result != null && !result.isEmpty()) {
                        optionPayload = result;
                        break;
                    }
                }
            }
        }
        catch (Exception e) {
            errorMessage = e.getMessage();
        }

        if (optionPayload == null || optionPayload.isEmpty()) {
            return new ToolOptionResolution(Collections.emptyList(), updatedResults, errorMessage);
        }

        List<SlotOption> options = mapToolResultToOptions(optionsConfig, optionPayload);
        return new ToolOptionResolution(options, updatedResults, errorMessage);
    }

    private Map<String, Object> buildDependencyResultPayload(ToolExecutor.ExecutionResult executionResult) {
        if (executionResult == null) {
            return Collections.emptyMap();
        }

        Map<String, Object> payload = executionResult.payload();
        Map<String, Object> outputFields = executionResult.outputFields();
        if ((payload == null || payload.isEmpty()) && (outputFields == null || outputFields.isEmpty())) {
            return Collections.emptyMap();
        }
        if (payload == null || payload.isEmpty()) {
            return new LinkedHashMap<>(outputFields);
        }
        if (outputFields == null || outputFields.isEmpty()) {
            Map<String, Object> rawOnly = new LinkedHashMap<>();
            rawOnly.put(RAW_TOOL_OPTION_PAYLOAD_KEY, new LinkedHashMap<>(payload));
            return rawOnly;
        }

        Map<String, Object> merged = new LinkedHashMap<>(outputFields);
        merged.put(RAW_TOOL_OPTION_PAYLOAD_KEY, new LinkedHashMap<>(payload));
        return merged;
    }

    private List<SlotOption> mapToolResultToOptions(SlotOptions optionsConfig, Map<String, Object> optionPayload) {
        if (optionsConfig == null || optionsConfig.getToolConfig() == null || optionPayload == null
                || optionPayload.isEmpty()) {
            return Collections.emptyList();
        }

        Object extracted = extractOptionSource(optionPayload, optionsConfig.getToolConfig().getResultPath());
        if (extracted == null) {
            extracted = extractOptionSourceFromRawPayload(optionPayload, optionsConfig.getToolConfig().getResultPath());
        }
        if (extracted == null) {
            return Collections.emptyList();
        }

        if (extracted instanceof List<?> list) {
            List<SlotOption> options = new ArrayList<>();
            for (Object item : list) {
                SlotOption option = toSlotOption(item, optionsConfig.getToolConfig());
                if (option != null) {
                    options.add(option);
                }
            }
            return options;
        }

        if (extracted instanceof Map<?, ?> map) {
            List<SlotOption> options = new ArrayList<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry.getKey() == null) {
                    continue;
                }
                options.add(new SlotOption(String.valueOf(entry.getKey()), entry.getValue()));
            }
            return options;
        }

        return List.of(new SlotOption(String.valueOf(extracted), extracted));
    }

    @SuppressWarnings("unchecked")
    private Object extractOptionSourceFromRawPayload(Map<String, Object> optionPayload, String resultPath) {
        Object rawPayload = optionPayload.get(RAW_TOOL_OPTION_PAYLOAD_KEY);
        if (rawPayload instanceof Map<?, ?> rawMap) {
            return extractOptionSource((Map<String, Object>) rawMap, resultPath);
        }
        return null;
    }

    private Object extractOptionSource(Map<String, Object> optionPayload, String resultPath) {
        if (!StringUtils.hasText(resultPath)) {
            for (String candidate : List.of("data", "items", "list", "records", "options")) {
                Object value = optionPayload.get(candidate);
                if (value instanceof List<?> || value instanceof Map<?, ?>) {
                    return value;
                }
            }
            if (optionPayload.size() == 1 && optionPayload.containsKey(RAW_TOOL_OPTION_PAYLOAD_KEY)) {
                return null;
            }
            return optionPayload;
        }

        JsonNode current = objectMapper.valueToTree(optionPayload);
        for (String segment : resultPath.split("\\.")) {
            if (!StringUtils.hasText(segment) || current == null || current.isMissingNode() || current.isNull()) {
                return null;
            }
            current = current.path(segment.trim());
        }
        if (current == null || current.isMissingNode() || current.isNull()) {
            return null;
        }
        return objectMapper.convertValue(current, Object.class);
    }

    @SuppressWarnings("unchecked")
    private SlotOption toSlotOption(Object item, com.alibaba.assistant.agent.slot.model.ToolOptionResolverConfig toolConfig) {
        if (item == null) {
            return null;
        }
        if (item instanceof Map<?, ?> rawMap) {
            Map<String, Object> map = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : rawMap.entrySet()) {
                if (entry.getKey() != null) {
                    map.put(String.valueOf(entry.getKey()), entry.getValue());
                }
            }
            Object rawLabel = readMappedField(map, toolConfig.getLabelField(), toolConfig.getValueField());
            Object rawValue = readMappedField(map, toolConfig.getValueField(), toolConfig.getLabelField());
            if (rawLabel == null && rawValue == null) {
                return null;
            }
            SlotOption option = new SlotOption(String.valueOf(rawLabel != null ? rawLabel : rawValue), rawValue);
            Object description = readMappedField(map, toolConfig.getDescriptionField(), null);
            if (description != null) {
                option.setDescription(String.valueOf(description));
            }
            Object disabled = readMappedField(map, toolConfig.getDisabledField(), null);
            if (disabled instanceof Boolean booleanValue) {
                option.setDisabled(booleanValue);
            }
            else if (disabled != null) {
                option.setDisabled(Boolean.parseBoolean(String.valueOf(disabled)));
            }
            return option;
        }
        if (item instanceof List<?> list && !list.isEmpty()) {
            return toSlotOption(list.get(0), toolConfig);
        }
        return new SlotOption(String.valueOf(item), item);
    }

    private Object readMappedField(Map<String, Object> source, String primaryField, String fallbackField) {
        if (source == null || source.isEmpty()) {
            return null;
        }
        if (StringUtils.hasText(primaryField) && source.containsKey(primaryField)) {
            return source.get(primaryField);
        }
        if (StringUtils.hasText(fallbackField) && source.containsKey(fallbackField)) {
            return source.get(fallbackField);
        }
        return null;
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
            if (!root.has("collect") && !containsCollectBehaviorKeys(collectNode)) {
                return CollectBehavior.defaults();
            }
            CollectBehavior behavior = objectMapper.convertValue(collectNode, CollectBehavior.class);
            return behavior != null ? behavior : CollectBehavior.defaults();
        }
        catch (Exception e) {
            logger.warn("SlotCollectTool#parseCollectBehavior - fallback to defaults, error={}", e.getMessage());
            return CollectBehavior.defaults();
        }
    }

    private boolean containsCollectBehaviorKeys(JsonNode node) {
        if (node == null || !node.isObject()) {
            return false;
        }
        return node.has("ask_mode")
                || node.has("batch_size")
                || node.has("max_rounds")
                || node.has("timeout_seconds")
                || node.has("timeout_action");
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
                              SlotCollectStatus status, int round,
                              Map<String, Map<String, Object>> dependencyResults,
                              String userInput,
                              Map<String, Object> currentTurnSlotInputs) {
        if (state == null) {
            return;
        }

        Map<String, Object> updates = new HashMap<>();
        updates.put(AssistantStateKeys.COLLECTED_SLOTS, collectedSlots);
        updates.put(AssistantStateKeys.COLLECT_ROUND, round);
        updates.put(AssistantStateKeys.ENRICHED_SLOTS, enrichedSlots);
        updates.put(AssistantStateKeys.SLOT_DEFINITIONS, slotDefinitions);
        updates.put(AssistantStateKeys.DEPENDENCY_RESULTS,
                dependencyResults != null ? dependencyResults : Collections.emptyMap());
        updates.put(AssistantStateKeys.CONVERSATION_PHASE,
                status == SlotCollectStatus.COMPLETE ? "READY_TO_CONFIRM" : status.name());
        // jump_to is a one-shot routing hint; clear stale value to avoid tool/model self-loop in same turn.
        updates.put("jump_to", null);
        updates.put(AssistantStateKeys.EXECUTION_CONFIRM_GRANTED, false);
        updates.put(AssistantStateKeys.CURRENT_TURN_SLOT_INPUTS, null);
        clearTransientSlotInputs(updates, currentTurnSlotInputs);
        updates.put(AssistantStateKeys.EXECUTION_CONFIRM_TOOL_NAME, null);
        updates.put(AssistantStateKeys.EXECUTION_CONFIRM_USER_INPUT, null);
        if (StringUtils.hasText(userInput)) {
            updates.put(AssistantStateKeys.LAST_COLLECT_USER_INPUT, userInput);
        }
        if (snapshot != null) {
            updates.put(AssistantStateKeys.MATCHED_TOOL_META, snapshot);
        }

        state.updateState(updates);
    }

    private void clearTransientSlotInputs(Map<String, Object> updates, Map<String, Object> currentTurnSlotInputs) {
        if (updates == null || currentTurnSlotInputs == null || currentTurnSlotInputs.isEmpty()) {
            return;
        }
        for (String slotName : currentTurnSlotInputs.keySet()) {
            if (StringUtils.hasText(slotName)) {
                updates.put(slotName, null);
            }
        }
    }

    private String resolveTenantId(OverAllState state, Request request) {
        String tenantId = firstNonEmpty(
                request != null ? request.tenantId : null,
                readLooseStateText(state, "tenant_id"),
                readLooseStateText(state, "tenantId"));
        return StringUtils.hasText(tenantId) ? tenantId : "default";
    }

    private String firstNonEmpty(String... values) {
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value;
            }
        }
        return null;
    }

    private Object firstNonNull(Object... values) {
        if (values == null) {
            return null;
        }
        for (Object value : values) {
            if (value != null) {
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

    private String readLooseStateText(OverAllState state, String key) {
        if (state == null || !StringUtils.hasText(key)) {
            return null;
        }
        Object value = state.value(key, Object.class).orElse(null);
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return StringUtils.hasText(text) ? text : null;
    }

    private String normalizeToolCode(String toolCode) {
        if (!StringUtils.hasText(toolCode)) {
            return "";
        }
        return toolCode.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * 依赖解析结果与执行输出。
     */
    private record DependencyExecution(Map<String, Map<String, Object>> results,
                           List<DependencyResolver.FieldMapping> mappings) {

    }

    private record SlotEnrichmentResult(List<EnrichedSlot> enrichedSlots,
                                        Map<String, Map<String, Object>> dependencyResults) {

    }

    private record ToolOptionResolution(List<SlotOption> options,
                                        Map<String, Map<String, Object>> dependencyResults,
                                        String errorMessage) {

    }

    /**
     * {@code slot_collect} 的输入参数。
     */
    public static class Request {
        @JsonPropertyDescription("Target tool code")
        public String toolCode;

        @JsonPropertyDescription("Extracted slot values from current user turn")
        public Map<String, Object> extractedSlots = new HashMap<>();

        @JsonPropertyDescription("Optional system code override")
        public String systemCode;

        @JsonPropertyDescription("Optional assistant uid override")
        public String assistantUid;

        @JsonPropertyDescription("Optional tenant id override")
        public String tenantId;
    }

    /**
     * {@code slot_collect} 的执行结果。
     */
    public static class Response {
        public String status;
        public String phase;
        public String message;
        public String toolCode;
        public Integer round;
        public Map<String, Object> collected;
        public Map<String, Object> allCollected;
        public List<MissingSlot> missing;
        public List<EnrichedSlot> enrichedSlots;

        public static Response collecting(String toolCode,
                                          Map<String, Object> collected,
                                          List<MissingSlot> missing,
                                          Integer round,
                                          List<EnrichedSlot> enrichedSlots,
                                          String status) {
            Response response = new Response();
            response.status = status;
            response.phase = "COLLECTING";
            response.toolCode = toolCode;
            response.collected = collected;
            response.missing = missing;
            response.round = round;
            response.enrichedSlots = enrichedSlots;
            response.message = "Missing required slots, continue collecting.";
            return response;
        }

        public static Response complete(String toolCode,
                                        Map<String, Object> allCollected,
                                        Map<String, Object> collected,
                                        Integer round,
                                        List<EnrichedSlot> enrichedSlots) {
            Response response = new Response();
            response.status = SlotCollectStatus.COMPLETE.name();
            response.phase = "READY_TO_CONFIRM";
            response.toolCode = toolCode;
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
     * 返回给模型的缺失槽位描述。
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







