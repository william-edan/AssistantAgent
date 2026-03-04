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
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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

    private static final Pattern RELATIVE_DATE_RANGE_PATTERN =
            Pattern.compile("(大后天|后天|明天|今天|今日)\\s*(?:到|至|\\-|~|～)\\s*(大后天|后天|明天|今天|今日)");

    private static final Pattern ABSOLUTE_DATE_PATTERN =
            Pattern.compile("(20\\d{2})[-/.年](\\d{1,2})[-/.月](\\d{1,2})日?");

    private static final Pattern EXPLICIT_REASON_PATTERN =
            Pattern.compile("(?:请假原因(?:是|为)?|原因(?:是|为)?|因为|由于)\\s*([^，。；,;\\n]{1,40})");

    private static final Pattern GENERIC_PRIVATE_REASON_PATTERN =
            Pattern.compile("有[^，。；,;\\n]{0,6}事");

    private final SlotCollectorService slotCollectorService;
    private final SlotEnricherService slotEnricherService;
    private final ComputedFieldProcessor computedFieldProcessor;
    private final SlotSchemaParser slotSchemaParser;
    private final ObjectMapper objectMapper;
    private final ToolMetaService toolMetaService;
    private final DependencyResolver dependencyResolver;
    private final FieldMappingProcessor fieldMappingProcessor;
    private final ToolExecutor toolExecutor;

    public SlotCollectTool(SlotCollectorService slotCollectorService,
                           SlotEnricherService slotEnricherService,
                           ComputedFieldProcessor computedFieldProcessor,
                           SlotSchemaParser slotSchemaParser,
                           ObjectMapper objectMapper) {
        this(slotCollectorService, slotEnricherService, computedFieldProcessor, slotSchemaParser, objectMapper,
                null, null, null, null);
    }

    public SlotCollectTool(SlotCollectorService slotCollectorService,
                           SlotEnricherService slotEnricherService,
                           ComputedFieldProcessor computedFieldProcessor,
                           SlotSchemaParser slotSchemaParser,
                           ObjectMapper objectMapper,
                           ToolMetaService toolMetaService) {
        this(slotCollectorService, slotEnricherService, computedFieldProcessor, slotSchemaParser, objectMapper,
                toolMetaService, null, null, null);
    }

    @Autowired
    public SlotCollectTool(SlotCollectorService slotCollectorService,
                           SlotEnricherService slotEnricherService,
                           ComputedFieldProcessor computedFieldProcessor,
                           SlotSchemaParser slotSchemaParser,
                           ObjectMapper objectMapper,
                           @Nullable ToolMetaService toolMetaService,
                           @Nullable DependencyResolver dependencyResolver,
                           @Nullable FieldMappingProcessor fieldMappingProcessor,
                           @Nullable ToolExecutor toolExecutor) {
        this.slotCollectorService = slotCollectorService;
        this.slotEnricherService = slotEnricherService;
        this.computedFieldProcessor = computedFieldProcessor;
        this.slotSchemaParser = slotSchemaParser;
        this.objectMapper = objectMapper;
        this.toolMetaService = toolMetaService;
        this.dependencyResolver = dependencyResolver;
        this.fieldMappingProcessor = fieldMappingProcessor != null ? fieldMappingProcessor : new FieldMappingProcessor();
        this.toolExecutor = toolExecutor;
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
                List<EnrichedSlot> enrichedSlots = enrichSlotsSafely(slotDefinitions, systemCode, assistantUid);
                applyAutoSelect(slotDefinitions, enrichedSlots, collectedSlots);
                computedFieldProcessor.processComputedFields(slotDefinitions, collectedSlots);

                SlotCollectStatus status = slotCollectorService.checkCollectionStatus(slotDefinitions, collectedSlots);
                int currentRound = Math.max(1, readIntState(state, AssistantStateKeys.COLLECT_ROUND));
                CollectBehavior behavior = parseCollectBehavior(snapshot.getBehaviorConfig());
                List<SlotDefinition> nextSlots = status == SlotCollectStatus.COMPLETE
                        ? Collections.emptyList()
                        : slotCollectorService.getNextSlotsToCollect(slotDefinitions, collectedSlots, behavior,
                        currentRound);

                if (status == SlotCollectStatus.COMPLETE) {
                    return Response.complete(
                            slotCollectorService.buildFinalParams(slotDefinitions, collectedSlots),
                            mapCollected(collectedSlots),
                            currentRound,
                            enrichedSlots);
                }
                Response waiting = Response.collecting(
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
                    slotDefinitions,
                    state,
                    userInput,
                    suppressModelExtraction);
            applyWorkReportDateFallback(extracted, slotDefinitions, collectedSlots, snapshot, resolveAnchorDate(state));
            if (!extracted.isEmpty()) {
                collectedSlots = slotCollectorService.collectFromAgent(extracted, slotDefinitions, collectedSlots);
            }

            List<EnrichedSlot> enrichedSlots = enrichSlotsSafely(slotDefinitions, systemCode, assistantUid);
            applyAutoSelect(slotDefinitions, enrichedSlots, collectedSlots);

            computedFieldProcessor.processComputedFields(slotDefinitions, collectedSlots);

            SlotCollectStatus status = slotCollectorService.checkCollectionStatus(slotDefinitions, collectedSlots);
            int nextRound = readIntState(state, AssistantStateKeys.COLLECT_ROUND) + 1;
            CollectBehavior behavior = parseCollectBehavior(snapshot.getBehaviorConfig());
            List<SlotDefinition> nextSlots = status == SlotCollectStatus.COMPLETE
                    ? Collections.emptyList()
                    : slotCollectorService.getNextSlotsToCollect(slotDefinitions, collectedSlots, behavior, nextRound);

            persistState(state, snapshot, slotDefinitions, collectedSlots, enrichedSlots, status, nextRound,
                    dependencyResults, userInput);

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

    private Map<String, Object> mergeAndInferExtractedSlots(Map<String, Object> extractedSlots,
                                                            List<SlotDefinition> slotDefinitions,
                                                            OverAllState state,
                                                            String userInput,
                                                            boolean suppressModelExtraction) {
        Map<String, Object> merged = new LinkedHashMap<>();
        if (!suppressModelExtraction && extractedSlots != null && !extractedSlots.isEmpty()) {
            merged.putAll(extractedSlots);
        }

        if (!StringUtils.hasText(userInput)) {
            return merged;
        }

        LocalDate anchorDate = resolveAnchorDate(state);
        applyDateFallback(merged, slotDefinitions, userInput, anchorDate);
        applyLeaveTypeFallback(merged, slotDefinitions, userInput);
        applyReasonFallback(merged, slotDefinitions, userInput);
        return merged;
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
                                        String userInput) {
        String leaveTypeSlotName = resolveSlotName(slotDefinitions, "leave_type", "types", "leaveType", "type");
        if (!StringUtils.hasText(leaveTypeSlotName) || hasTextValue(extracted.get(leaveTypeSlotName))) {
            return;
        }

        String inferredLeaveTypeLabel = inferLeaveTypeLabel(userInput);
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
                                   LocalDate anchorDate) {
        String startSlotName = resolveSlotName(slotDefinitions, "start_date", "startDate");
        String endSlotName = resolveSlotName(slotDefinitions, "end_date", "endDate");
        if (!StringUtils.hasText(startSlotName) || !StringUtils.hasText(endSlotName)) {
            return;
        }

        boolean hasStart = hasTextValue(extracted.get(startSlotName));
        boolean hasEnd = hasTextValue(extracted.get(endSlotName));
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

        if (!hasEnd && hasStart && isOneDayLeave(userInput)) {
            extracted.put(endSlotName, String.valueOf(extracted.get(startSlotName)));
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

    private void applyWorkReportDateFallback(Map<String, Object> extracted,
                                             List<SlotDefinition> slotDefinitions,
                                             Map<String, SlotValue> collectedSlots,
                                             ToolMetaSnapshot snapshot,
                                             LocalDate anchorDate) {
        if (!isWorkReportTool(snapshot) || slotDefinitions == null || slotDefinitions.isEmpty()) {
            return;
        }

        String startSlotName = resolveSlotName(slotDefinitions, "start_date", "startDate");
        String endSlotName = resolveSlotName(slotDefinitions, "end_date", "endDate");
        if (!StringUtils.hasText(startSlotName) || !StringUtils.hasText(endSlotName)) {
            return;
        }

        Object startCandidate = firstNonNull(extracted.get(startSlotName), readCollectedValue(collectedSlots, startSlotName));
        Object endCandidate = firstNonNull(extracted.get(endSlotName), readCollectedValue(collectedSlots, endSlotName));
        if (hasTextValue(startCandidate) && hasTextValue(endCandidate)) {
            return;
        }

        Integer reportType = resolveWorkReportType(extracted, collectedSlots, slotDefinitions);
        if (reportType == null) {
            return;
        }

        LocalDate safeAnchorDate = anchorDate != null ? anchorDate : LocalDate.now();
        LocalDate inferredStart;
        LocalDate inferredEnd;
        switch (reportType) {
            case 1:
                inferredStart = safeAnchorDate;
                inferredEnd = safeAnchorDate;
                break;
            case 2:
                inferredStart = safeAnchorDate.with(TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY));
                inferredEnd = inferredStart.plusDays(6);
                break;
            case 3:
                inferredStart = safeAnchorDate.withDayOfMonth(1);
                inferredEnd = safeAnchorDate.with(TemporalAdjusters.lastDayOfMonth());
                break;
            default:
                return;
        }

        if (!hasTextValue(startCandidate)) {
            extracted.put(startSlotName, inferredStart.toString());
        }
        if (!hasTextValue(endCandidate)) {
            extracted.put(endSlotName, inferredEnd.toString());
        }
    }

    private Integer resolveWorkReportType(Map<String, Object> extracted,
                                          Map<String, SlotValue> collectedSlots,
                                          List<SlotDefinition> slotDefinitions) {
        String typeSlotName = resolveSlotName(slotDefinitions, "types", "type");
        if (!StringUtils.hasText(typeSlotName)) {
            return null;
        }

        Object typeValue = firstNonNull(extracted.get(typeSlotName), readCollectedValue(collectedSlots, typeSlotName));
        if (typeValue == null) {
            SlotDefinition typeDefinition = findSlotDefinition(slotDefinitions, typeSlotName);
            if (typeDefinition != null) {
                typeValue = typeDefinition.getDefaultValue();
            }
        }
        if (typeValue == null) {
            return null;
        }

        if (typeValue instanceof Number number) {
            return number.intValue();
        }

        String raw = String.valueOf(typeValue).trim();
        if (!StringUtils.hasText(raw)) {
            return null;
        }

        if ("日报".equals(raw)) {
            return 1;
        }
        if ("周报".equals(raw)) {
            return 2;
        }
        if ("月报".equals(raw)) {
            return 3;
        }

        try {
            return Integer.parseInt(raw);
        }
        catch (NumberFormatException e) {
            return null;
        }
    }

    private Object readCollectedValue(Map<String, SlotValue> collectedSlots, String slotName) {
        if (collectedSlots == null || !StringUtils.hasText(slotName)) {
            return null;
        }
        SlotValue slotValue = collectedSlots.get(slotName);
        return slotValue != null ? slotValue.getResolvedValue() : null;
    }

    private boolean isWorkReportTool(ToolMetaSnapshot snapshot) {
        if (snapshot == null || !StringUtils.hasText(snapshot.getToolCode())) {
            return false;
        }
        String toolCode = snapshot.getToolCode().trim().toLowerCase(Locale.ROOT);
        return toolCode.endsWith("work_report") || toolCode.endsWith(".work_report");
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
        return firstNonEmpty(
                readStringState(state, "input"),
                readLooseStateText(state, "input"),
                readLooseStateText(state, "query"),
                resolveLatestUserMessage(state));
    }

    @SuppressWarnings("unchecked")
    private String resolveLatestUserMessage(OverAllState state) {
        if (state == null) {
            return null;
        }
        Object rawMessages = state.value("messages", Object.class).orElse(null);
        if (!(rawMessages instanceof List<?> messages) || messages.isEmpty()) {
            return null;
        }

        for (int i = messages.size() - 1; i >= 0; i--) {
            Object item = messages.get(i);
            if (item instanceof UserMessage userMessage && StringUtils.hasText(userMessage.getText())) {
                return userMessage.getText();
            }
            if (item instanceof Message message
                    && message instanceof UserMessage
                    && StringUtils.hasText(message.getText())) {
                return message.getText();
            }
            if (item instanceof Map<?, ?> rawMap) {
                Map<String, Object> map = objectMapper.convertValue(rawMap, Map.class);
                String role = firstNonEmpty(
                        asText(map.get("messageType")),
                        asText(map.get("type")),
                        asText(map.get("role")),
                        asText(map.get("messageRole")),
                        asText(map.get("message_role")));
                String text = firstNonEmpty(
                        asText(map.get("text")),
                        asText(map.get("content")));
                if (StringUtils.hasText(text) && isUserRole(role)) {
                    return text;
                }
            }
        }
        return null;
    }

    private boolean isUserRole(String role) {
        if (!StringUtils.hasText(role)) {
            return false;
        }
        String normalized = role.trim().toUpperCase(Locale.ROOT);
        return "USER".equals(normalized) || "HUMAN".equals(normalized);
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
            return "个人事务";
        }
        return null;
    }

    private String inferLeaveTypeLabel(String input) {
        if (!StringUtils.hasText(input)) {
            return null;
        }
        if (input.contains("病假")) {
            return "病假";
        }
        if (input.contains("年假")) {
            return "年假";
        }
        if (input.contains("调休")) {
            return "调休假";
        }
        if (input.contains("事假")) {
            return "事假";
        }
        if (containsAny(input, "有事", "私事", "个人原因", "家中有事", "家里有事")
                || GENERIC_PRIVATE_REASON_PATTERN.matcher(input).find()) {
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
        normalized = normalized.replaceFirst("(需要)?请假.*$", "").trim();
        if (!StringUtils.hasText(normalized)) {
            return null;
        }
        if (containsAny(normalized, "有事", "私事", "个人原因", "个人事务")
                || GENERIC_PRIVATE_REASON_PATTERN.matcher(normalized).find()) {
            return "个人事务";
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
            ToolMeta toolMeta = lookupToolMetaByCode(resolveTenantId(state, request), request.toolCode);
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

    private ToolMeta lookupToolMetaByCode(String tenantId, String toolCode) {
        if (!StringUtils.hasText(toolCode) || toolMetaService == null) {
            return null;
        }
        Optional<ToolMeta> latest = toolMetaService.findLatestEnabledByToolCode(tenantId, toolCode);
        if (latest.isPresent()) {
            return latest.get();
        }
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

    private DependencyExecution resolveAndExecuteDependencies(ToolMetaSnapshot snapshot,
                                                              String tenantId,
                                                              Map<String, SlotValue> collectedSlots,
                                                              Map<String, Map<String, Object>> cachedDependencyResults,
                                                              String systemCode,
                                                              String assistantUid,
                                                              OverAllState state,
                                                              ToolContext toolContext) {
        List<DependencyResolver.ResolvedStep> steps = resolveDependencySteps(snapshot, tenantId);
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

            results.put(dependencyKey, executionResult.outputFields());
        }
        return new DependencyExecution(results, mappings);
    }

    private List<DependencyResolver.ResolvedStep> resolveDependencySteps(ToolMetaSnapshot snapshot, String tenantId) {
        if (dependencyResolver == null || !StringUtils.hasText(snapshot.getToolCode())) {
            return Collections.emptyList();
        }

        String targetToolCode = snapshot.getToolCode().trim();
        ToolMeta rootMeta = null;
        if (toolMetaService != null) {
            rootMeta = toolMetaService.findLatestEnabledByToolCode(tenantId, targetToolCode).orElse(null);
        }
        if (rootMeta == null) {
            rootMeta = new ToolMeta();
            rootMeta.setToolCode(targetToolCode);
            rootMeta.setDescription(targetToolCode);
            rootMeta.setSystemCode(snapshot.getSystemCode());
            rootMeta.setInteractionPolicy(snapshot.getBehaviorConfig());
        }
        else if (!StringUtils.hasText(rootMeta.getInteractionPolicy())
                && StringUtils.hasText(snapshot.getBehaviorConfig())) {
            rootMeta.setInteractionPolicy(snapshot.getBehaviorConfig());
        }

        ToolMeta finalRootMeta = rootMeta;
        return dependencyResolver.resolve(targetToolCode, toolCode -> {
            if (StringUtils.hasText(toolCode)
                    && targetToolCode.equalsIgnoreCase(toolCode.trim())) {
                return Optional.of(finalRootMeta);
            }
            if (toolMetaService == null) {
                return Optional.empty();
            }
            return toolMetaService.findLatestEnabledByToolCode(tenantId, toolCode);
        });
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
                              String userInput) {
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
     * Dependency resolution and execution output.
     */
    private record DependencyExecution(Map<String, Map<String, Object>> results,
                                       List<DependencyResolver.FieldMapping> mappings) {

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

        @JsonPropertyDescription("Optional tenant id override")
        public String tenantId;
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
