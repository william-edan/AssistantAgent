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
package com.alibaba.assistant.agent.runtime.prompt;

import com.alibaba.assistant.agent.controlplane.toolregistry.ToolMeta;
import com.alibaba.assistant.agent.prompt.PromptContribution;
import com.alibaba.assistant.agent.prompt.PromptContributor;
import com.alibaba.assistant.agent.prompt.PromptContributorContext;
import com.alibaba.assistant.agent.runtime.agent.AssistantStateKeys;
import com.alibaba.assistant.agent.runtime.config.RuntimeConfigCompatibilityAdapter;
import com.alibaba.assistant.agent.slot.SlotSchemaParser;
import com.alibaba.assistant.agent.slot.model.SlotAskMode;
import com.alibaba.assistant.agent.slot.model.SlotDefinition;
import com.alibaba.assistant.agent.slot.model.SlotPriority;
import com.alibaba.assistant.agent.slot.model.SlotValue;
import com.alibaba.assistant.agent.slot.model.ToolMetaSnapshot;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Injects concise action-definition metadata to guide LLM slot collection follow-up questions.
 *
 * @author Assistant Agent Team
 * @since 1.0.0
 */
@Component
@Profile("migration")
public class ActionBriefContributor implements PromptContributor {

	private static final int MAX_SLOT_DEFINITION_LINES = 8;

	private static final int MAX_MISSING_SLOT_LINES = 6;

	private static final int MAX_COLLECTED_SLOT_LINES = 8;

	private final RuntimeConfigCompatibilityAdapter compatibilityAdapter;

	private final SlotSchemaParser slotSchemaParser;

	private final ObjectMapper objectMapper;

	public ActionBriefContributor(
			RuntimeConfigCompatibilityAdapter compatibilityAdapter,
			SlotSchemaParser slotSchemaParser,
			ObjectMapper objectMapper) {
		this.compatibilityAdapter = compatibilityAdapter;
		this.slotSchemaParser = slotSchemaParser;
		this.objectMapper = objectMapper;
	}

	@Override
	public String getName() {
		return "action-brief";
	}

	@Override
	public int getPriority() {
		return 150;
	}

	@Override
	public boolean shouldContribute(PromptContributorContext context) {
		return compatibilityAdapter.promptDynamicEnabled() && resolveActionMeta(context.getAttributes()).isPresent();
	}

	@Override
	public PromptContribution contribute(PromptContributorContext context) {
		Optional<ActionMeta> actionMetaOpt = resolveActionMeta(context.getAttributes());
		if (actionMetaOpt.isEmpty()) {
			return PromptContribution.empty();
		}

		ActionMeta actionMeta = actionMetaOpt.get();
		List<SlotDefinition> slotDefinitions = resolveSlotDefinitions(actionMeta);
		Map<String, Object> collectedSlots = resolveCollectedSlots(
				context.getAttributes().get(AssistantStateKeys.COLLECTED_SLOTS));
		List<SlotDefinition> missingSlots = resolveMissingSlots(slotDefinitions, collectedSlots);

		String text = renderActionBrief(
				actionMeta,
				asText(context.getAttributes().get(AssistantStateKeys.CONVERSATION_PHASE)),
				slotDefinitions,
				collectedSlots,
				missingSlots);
		if (!StringUtils.hasText(text)) {
			return PromptContribution.empty();
		}

		return PromptContribution.builder()
				.append(new UserMessage(text))
				.build();
	}

	private Optional<ActionMeta> resolveActionMeta(Map<String, Object> attrs) {
		if (attrs == null || attrs.isEmpty()) {
			return Optional.empty();
		}

		Object rawMeta = attrs.get(AssistantStateKeys.MATCHED_TOOL_META);
		if (rawMeta == null) {
			return Optional.empty();
		}

		if (rawMeta instanceof ToolMeta toolMeta) {
			return Optional.of(fromToolMeta(toolMeta));
		}
		if (rawMeta instanceof ToolMetaSnapshot snapshot) {
			return Optional.of(fromSnapshot(snapshot));
		}
		if (rawMeta instanceof Map<?, ?> rawMap) {
			Optional<ActionMeta> actionMeta = fromMap(rawMap);
			if (actionMeta.isPresent()) {
				return actionMeta;
			}
		}
		if (rawMeta instanceof String jsonText && StringUtils.hasText(jsonText)) {
			try {
				Map<String, Object> rawMap = objectMapper.readValue(jsonText, new TypeReference<Map<String, Object>>() {
				});
				Optional<ActionMeta> actionMeta = fromMap(rawMap);
				if (actionMeta.isPresent()) {
					return actionMeta;
				}
			}
			catch (Exception ignored) {
				// Ignore and fallback to ToolMetaSnapshot conversion.
			}
			try {
				ToolMetaSnapshot snapshot = objectMapper.readValue(jsonText, ToolMetaSnapshot.class);
				if (StringUtils.hasText(snapshot.getToolCode())) {
					return Optional.of(fromSnapshot(snapshot));
				}
			}
			catch (Exception ignored) {
				// Ignore and fallback to generic conversion.
			}
		}

		try {
			ToolMeta toolMeta = objectMapper.convertValue(rawMeta, ToolMeta.class);
			if (StringUtils.hasText(toolMeta.getToolCode()) || StringUtils.hasText(toolMeta.getToolName())) {
				return Optional.of(fromToolMeta(toolMeta));
			}
		}
		catch (Exception ignored) {
			// Ignore and fallback to ToolMetaSnapshot conversion.
		}

		try {
			ToolMetaSnapshot snapshot = objectMapper.convertValue(rawMeta, ToolMetaSnapshot.class);
			if (StringUtils.hasText(snapshot.getToolCode())) {
				return Optional.of(fromSnapshot(snapshot));
			}
		}
		catch (Exception ignored) {
			// Ignore.
		}
		return Optional.empty();
	}

	private ActionMeta fromToolMeta(ToolMeta toolMeta) {
		return new ActionMeta(
				toolMeta.getToolCode(),
				toolMeta.getToolName(),
				toolMeta.getDescription(),
				toolMeta.getSystemCode(),
				toolMeta.getParameterSchema(),
				null,
				toolMeta.getRiskLevel(),
				toolMeta.getRequiresConfirm());
	}

	private ActionMeta fromSnapshot(ToolMetaSnapshot snapshot) {
		return new ActionMeta(
				snapshot.getToolCode(),
				null,
				null,
				snapshot.getSystemCode(),
				snapshot.getSlotSchema(),
				snapshot.getRequestSchema(),
				null,
				null);
	}

	private Optional<ActionMeta> fromMap(Map<?, ?> rawMeta) {
		if (rawMeta == null || rawMeta.isEmpty()) {
			return Optional.empty();
		}
		String toolCode = firstNonEmpty(asText(rawMeta.get("toolCode")), asText(rawMeta.get("tool_code")));
		String toolName = firstNonEmpty(asText(rawMeta.get("toolName")), asText(rawMeta.get("tool_name")));
		String description = firstNonEmpty(
				asText(rawMeta.get("description")),
				asText(rawMeta.get("toolDescription")),
				asText(rawMeta.get("tool_description")));
		String systemCode = firstNonEmpty(asText(rawMeta.get("systemCode")), asText(rawMeta.get("system_code")));
		String parameterSchema = firstNonEmpty(
				asText(rawMeta.get("parameterSchema")),
				asText(rawMeta.get("parameter_schema")));
		String slotSchema = firstNonEmpty(
				asText(rawMeta.get("slotSchema")),
				asText(rawMeta.get("slot_schema")),
				parameterSchema);
		String requestSchema = firstNonEmpty(
				asText(rawMeta.get("requestSchema")),
				asText(rawMeta.get("request_schema")),
				parameterSchema);
		String riskLevel = firstNonEmpty(asText(rawMeta.get("riskLevel")), asText(rawMeta.get("risk_level")));
		Boolean requiresConfirm = asBoolean(firstNonNull(rawMeta.get("requiresConfirm"), rawMeta.get("requires_confirm")));
		if (!StringUtils.hasText(toolCode) && !StringUtils.hasText(toolName)) {
			return Optional.empty();
		}
		return Optional.of(new ActionMeta(
				toolCode,
				toolName,
				description,
				systemCode,
				slotSchema,
				requestSchema,
				riskLevel,
				requiresConfirm));
	}

	private List<SlotDefinition> resolveSlotDefinitions(ActionMeta actionMeta) {
		if ((!StringUtils.hasText(actionMeta.slotSchema()) && !StringUtils.hasText(actionMeta.requestSchema()))
				|| !StringUtils.hasText(actionMeta.toolCode())) {
			return List.of();
		}
		try {
			ToolMetaSnapshot snapshot = new ToolMetaSnapshot();
			snapshot.setToolCode(actionMeta.toolCode());
			snapshot.setSlotSchema(actionMeta.slotSchema());
			snapshot.setRequestSchema(actionMeta.requestSchema());
			snapshot.setSystemCode(actionMeta.systemCode());

			List<SlotDefinition> definitions = slotSchemaParser.parse(snapshot);
			if (definitions == null || definitions.isEmpty()) {
				return List.of();
			}
			return definitions.stream()
					.sorted(Comparator
							.comparingInt((SlotDefinition slot) -> priorityOrder(slot.getPriority()))
							.thenComparing(slot -> !slot.isRequired()))
					.toList();
		}
		catch (Exception ignored) {
			return List.of();
		}
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> resolveCollectedSlots(Object rawCollected) {
		if (!(rawCollected instanceof Map<?, ?> rawMap) || rawMap.isEmpty()) {
			return Map.of();
		}
		Map<String, Object> collected = new LinkedHashMap<>();
		for (Map.Entry<?, ?> entry : rawMap.entrySet()) {
			String slotName = asText(entry.getKey());
			if (!StringUtils.hasText(slotName)) {
				continue;
			}
			Object resolved = resolveCollectedValue(entry.getValue());
			if (resolved != null) {
				collected.put(slotName, resolved);
			}
		}
		return collected;
	}

	private Object resolveCollectedValue(Object raw) {
		if (raw == null) {
			return null;
		}
		if (raw instanceof SlotValue slotValue) {
			return slotValue.getResolvedValue();
		}
		if (raw instanceof Map<?, ?> rawMap) {
			Map<String, Object> map = objectMapper.convertValue(rawMap, new TypeReference<Map<String, Object>>() {
			});
			Object candidate = firstNonNull(
					map.get("resolvedValue"),
					map.get("resolved_value"),
					map.get("value"),
					map.get("rawValue"),
					map.get("raw_value"));
			return candidate != null ? candidate : map;
		}
		return raw;
	}

	private List<SlotDefinition> resolveMissingSlots(List<SlotDefinition> slotDefinitions, Map<String, Object> collected) {
		if (slotDefinitions == null || slotDefinitions.isEmpty()) {
			return List.of();
		}
		Map<String, Object> safeCollected = collected != null ? collected : Map.of();
		List<SlotDefinition> missing = new ArrayList<>();
		for (SlotDefinition definition : slotDefinitions) {
			if (definition == null || !StringUtils.hasText(definition.getName())) {
				continue;
			}
			if (definition.getAskMode() == SlotAskMode.FORM_ONLY) {
				continue;
			}
			if (!isStrongRequired(definition)) {
				continue;
			}
			if (safeCollected.containsKey(definition.getName())) {
				continue;
			}
			if (shouldDeferInferredSlot(definition, slotDefinitions, safeCollected)) {
				continue;
			}
			if (isNoFollowUpNeeded(definition)) {
				continue;
			}
			missing.add(definition);
		}
		return missing;
	}

	private List<SlotDefinition> resolveAutoFillableSlots(List<SlotDefinition> slotDefinitions,
			Map<String, Object> collectedSlots) {
		if (slotDefinitions == null || slotDefinitions.isEmpty()) {
			return List.of();
		}
		Map<String, Object> safeCollected = collectedSlots != null ? collectedSlots : Map.of();
		List<SlotDefinition> autoFillable = new ArrayList<>();
		for (SlotDefinition definition : slotDefinitions) {
			if (definition == null || !StringUtils.hasText(definition.getName())) {
				continue;
			}
			if (safeCollected.containsKey(definition.getName())) {
				continue;
			}
			if (shouldDeferInferredSlot(definition, slotDefinitions, safeCollected)) {
				autoFillable.add(definition);
				continue;
			}
			if (isNoFollowUpNeeded(definition)) {
				autoFillable.add(definition);
			}
		}
		return autoFillable;
	}

	private String renderActionBrief(ActionMeta actionMeta,
			String phase,
			List<SlotDefinition> slotDefinitions,
			Map<String, Object> collectedSlots,
			List<SlotDefinition> missingSlots) {
		List<SlotDefinition> autoFillableSlots = resolveAutoFillableSlots(slotDefinitions, collectedSlots);
		StringBuilder sb = new StringBuilder();
		sb.append("【当前动作收集指引】\n");
		sb.append("- 动作：");
		if (StringUtils.hasText(actionMeta.toolName())) {
			sb.append(actionMeta.toolName());
			if (StringUtils.hasText(actionMeta.toolCode())) {
				sb.append(" (").append(actionMeta.toolCode()).append(")");
			}
		}
		else {
			sb.append(actionMeta.toolCode());
		}
		sb.append("\n");

		if (StringUtils.hasText(actionMeta.description())) {
			sb.append("- 动作说明：").append(actionMeta.description()).append("\n");
		}
		if (StringUtils.hasText(phase)) {
			sb.append("- 当前阶段：").append(phase).append("\n");
		}
		if (StringUtils.hasText(actionMeta.riskLevel())) {
			sb.append("- 风险等级：").append(actionMeta.riskLevel()).append("\n");
		}
		if (actionMeta.requiresConfirm() != null) {
			sb.append("- 是否需要确认：").append(Boolean.TRUE.equals(actionMeta.requiresConfirm()) ? "是" : "否").append("\n");
		}

		if (collectedSlots != null && !collectedSlots.isEmpty()) {
			sb.append("已识别参数：\n");
			int index = 0;
			for (Map.Entry<String, Object> entry : collectedSlots.entrySet()) {
				if (index >= MAX_COLLECTED_SLOT_LINES) {
					break;
				}
				sb.append("- ").append(entry.getKey()).append("=")
						.append(String.valueOf(entry.getValue())).append("\n");
				index++;
			}
		}

		if (slotDefinitions != null && !slotDefinitions.isEmpty()) {
			sb.append("参数定义：\n");
			for (int i = 0; i < Math.min(slotDefinitions.size(), MAX_SLOT_DEFINITION_LINES); i++) {
				SlotDefinition slot = slotDefinitions.get(i);
				sb.append("- ").append(renderSlotDefinition(slot)).append("\n");
			}
		}

		if (autoFillableSlots != null && !autoFillableSlots.isEmpty()) {
			sb.append("可自动填充/无需追问字段：\n");
			for (int i = 0; i < Math.min(autoFillableSlots.size(), MAX_MISSING_SLOT_LINES); i++) {
				SlotDefinition slot = autoFillableSlots.get(i);
				sb.append("- ").append(slot.getName());
				if (StringUtils.hasText(slot.getTitle())) {
					sb.append("（").append(slot.getTitle()).append("）");
				}
				if (slot.getComputed() != null && slot.getComputed().isEnabled()) {
					sb.append(" [计算字段]");
				}
				else if (slot.getDefaultValue() != null) {
					sb.append(" [默认值可用]");
				}
				else if (slot.getAskMode() == SlotAskMode.AUTO) {
					sb.append(" [自动推断]");
				}
				else if (hasInferenceMetadata(slot) && areInferenceSourcesCollected(slot, collectedSlots)) {
					sb.append(" [可由前序字段推断]");
				}
				else if (hasInferenceMetadata(slot)) {
					sb.append(" [由前序字段推断]");
				}
				sb.append("\n");
			}
		}

		if (missingSlots != null && !missingSlots.isEmpty()) {
			sb.append("仍需补充参数（优先这些）：\n");
			for (int i = 0; i < Math.min(missingSlots.size(), MAX_MISSING_SLOT_LINES); i++) {
				SlotDefinition slot = missingSlots.get(i);
				sb.append("- ").append(slot.getName());
				if (StringUtils.hasText(slot.getTitle())) {
					sb.append("（").append(slot.getTitle()).append("）");
				}
				if (StringUtils.hasText(slot.getAiHint())) {
					sb.append("：").append(slot.getAiHint());
				}
				sb.append("\n");
			}
			sb.append("追问规则：仅围绕上述必填缺失字段，不要重复询问已识别参数、默认值可用字段、计算字段、");
			sb.append("askMode=AUTO 字段或 inferred_from 可推断字段。");
		}
		else {
			sb.append("必填参数已齐全，可进入 slot_confirm。");
		}
		return sb.toString().trim();
	}

	private String renderSlotDefinition(SlotDefinition slot) {
		StringBuilder sb = new StringBuilder();
		sb.append(slot.getName());
		if (StringUtils.hasText(slot.getTitle())) {
			sb.append("（").append(slot.getTitle()).append("）");
		}
		sb.append(isStrongRequired(slot) ? " [必填]" : " [选填]");
		if (StringUtils.hasText(slot.getDescription())) {
			sb.append("：").append(slot.getDescription());
		}
		if (StringUtils.hasText(slot.getAiHint())) {
			sb.append("；提示：").append(slot.getAiHint());
		}
		String optionsText = renderOptions(slot);
		if (StringUtils.hasText(optionsText)) {
			sb.append("；可选值：").append(optionsText);
		}
		return sb.toString();
	}

	private String renderOptions(SlotDefinition slot) {
		if (slot == null || slot.getOptions() == null || slot.getOptions().getEnumMapping() == null
				|| slot.getOptions().getEnumMapping().isEmpty()) {
			return null;
		}
		return slot.getOptions().getEnumMapping().entrySet().stream()
				.limit(6)
				.map(entry -> entry.getKey() + "=" + String.valueOf(entry.getValue()))
				.collect(Collectors.joining("，"));
	}

	private boolean isStrongRequired(SlotDefinition slot) {
		if (slot == null) {
			return false;
		}
		return slot.isRequired() || slot.getPriority() == SlotPriority.CORE;
	}

	private boolean isNoFollowUpNeeded(SlotDefinition slot) {
		if (slot == null) {
			return false;
		}
		if (slot.getComputed() != null && slot.getComputed().isEnabled()) {
			return true;
		}
		if (slot.getDefaultValue() != null) {
			return true;
		}
		return slot.getAskMode() == SlotAskMode.AUTO;
	}

	private boolean shouldDeferInferredSlot(SlotDefinition slot,
			List<SlotDefinition> slotDefinitions,
			Map<String, Object> collectedSlots) {
		if (!hasInferenceMetadata(slot)) {
			return false;
		}
		if (collectedSlots != null && collectedSlots.containsKey(slot.getName())) {
			return false;
		}
		for (String source : slot.getInferredFrom()) {
			if (!StringUtils.hasText(source)) {
				continue;
			}
			if (collectedSlots != null && collectedSlots.containsKey(source)) {
				continue;
			}
			SlotDefinition sourceDefinition = findSlotDefinition(slotDefinitions, source);
			if (sourceDefinition != null && isNoFollowUpNeeded(sourceDefinition)) {
				continue;
			}
			return true;
		}
		return false;
	}

	private boolean hasInferenceMetadata(SlotDefinition slot) {
		return slot != null && slot.getInferredFrom() != null && !slot.getInferredFrom().isEmpty();
	}

	private boolean areInferenceSourcesCollected(SlotDefinition slot, Map<String, Object> collectedSlots) {
		if (!hasInferenceMetadata(slot)) {
			return false;
		}
		if (collectedSlots == null || collectedSlots.isEmpty()) {
			return false;
		}
		for (String source : slot.getInferredFrom()) {
			if (!StringUtils.hasText(source)) {
				continue;
			}
			if (!collectedSlots.containsKey(source)) {
				return false;
			}
		}
		return true;
	}

	private SlotDefinition findSlotDefinition(List<SlotDefinition> slotDefinitions, String slotName) {
		if (slotDefinitions == null || slotDefinitions.isEmpty() || !StringUtils.hasText(slotName)) {
			return null;
		}
		for (SlotDefinition definition : slotDefinitions) {
			if (definition != null && slotName.equals(definition.getName())) {
				return definition;
			}
		}
		return null;
	}

	private int priorityOrder(SlotPriority priority) {
		if (priority == null) {
			return 1;
		}
		return switch (priority) {
			case CORE -> 0;
			case CONFIRM -> 1;
			case OPTIONAL -> 2;
			case SUPPLEMENTARY -> 3;
		};
	}

	private String asText(Object value) {
		if (value == null) {
			return null;
		}
		String text = String.valueOf(value).trim();
		return StringUtils.hasText(text) ? text : null;
	}

	private String firstNonEmpty(String... values) {
		if (values == null || values.length == 0) {
			return null;
		}
		for (String value : values) {
			if (StringUtils.hasText(value)) {
				return value;
			}
		}
		return null;
	}

	private Boolean asBoolean(Object value) {
		if (value instanceof Boolean booleanValue) {
			return booleanValue;
		}
		String text = asText(value);
		if (!StringUtils.hasText(text)) {
			return null;
		}
		if ("true".equalsIgnoreCase(text)) {
			return true;
		}
		if ("false".equalsIgnoreCase(text)) {
			return false;
		}
		return null;
	}

	private Object firstNonNull(Object... values) {
		if (values == null || values.length == 0) {
			return null;
		}
		for (Object value : values) {
			if (value != null) {
				return value;
			}
		}
		return null;
	}

	private record ActionMeta(String toolCode,
			String toolName,
			String description,
			String systemCode,
			String slotSchema,
			String requestSchema,
			String riskLevel,
			Boolean requiresConfirm) {
	}

}
