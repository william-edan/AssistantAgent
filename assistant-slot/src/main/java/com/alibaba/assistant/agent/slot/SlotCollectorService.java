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

import com.alibaba.assistant.agent.slot.model.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Service for managing slot collection state.
 * Determines which slots to collect next based on priority, dependencies, and behavior strategy.
 *
 * @author Assistant Agent Team
 * @since 1.0.0
 */
@Service
public class SlotCollectorService {

	private static final Logger logger = LoggerFactory.getLogger(SlotCollectorService.class);

	/**
	 * Get next batch of slots to collect.
	 *
	 * @param allSlots all slot definitions
	 * @param collectedSlots already collected slot values
	 * @param behavior collection behavior configuration
	 * @param currentRound current collection round
	 * @return list of slots to collect in next batch
	 */
	public List<SlotDefinition> getNextSlotsToCollect(List<SlotDefinition> allSlots,
			Map<String, SlotValue> collectedSlots, CollectBehavior behavior, int currentRound) {

		if (allSlots == null || allSlots.isEmpty()) {
			return Collections.emptyList();
		}

		CollectBehavior effectiveBehavior = behavior != null ? behavior : CollectBehavior.defaults();
		int batchSize = effectiveBehavior.getBatchSize();

		logger.debug("SlotCollectorService#getNextSlotsToCollect - totalSlots={}, collectedCount={}, batchSize={}, "
				+ "round={}", allSlots.size(), collectedSlots != null ? collectedSlots.size() : 0, batchSize,
				currentRound);

		// Filter to uncollected slots with satisfied dependencies
		List<SlotDefinition> pendingSlots = allSlots.stream()
			.filter(slot -> !isCollected(slot.getName(), collectedSlots))
			.filter(slot -> areDependenciesSatisfied(slot, collectedSlots))
			.filter(slot -> areInferenceSourcesReady(slot, allSlots, collectedSlots))
			.collect(Collectors.toList());

		if (pendingSlots.isEmpty()) {
			return Collections.emptyList();
		}

		// Sort by priority: CORE first, then CONFIRM, then OPTIONAL, then SUPPLEMENTARY
		pendingSlots.sort((a, b) -> {
			int priorityCompare = getPriorityOrder(a.getPriority()) - getPriorityOrder(b.getPriority());
			if (priorityCompare != 0) {
				return priorityCompare;
			}
			return Boolean.compare(b.isRequired(), a.isRequired());
		});

		// Apply ask mode logic
		SlotAskMode askMode = effectiveBehavior.getAskMode();
		if (askMode == SlotAskMode.SINGLE) {
			return Collections.singletonList(pendingSlots.get(0));
		}

		// Batch mode: return up to batchSize slots (excluding FORM_ONLY)
		return pendingSlots.stream()
			.filter(slot -> slot.getAskMode() != SlotAskMode.FORM_ONLY)
			.limit(batchSize)
			.collect(Collectors.toList());
	}

	/**
	 * Check overall collection status.
	 */
	public SlotCollectStatus checkCollectionStatus(List<SlotDefinition> allSlots,
			Map<String, SlotValue> collectedSlots) {

		if (allSlots == null || allSlots.isEmpty()) {
			return SlotCollectStatus.COMPLETE;
		}

		boolean allCoreCollected = allSlots.stream()
			.filter(slot -> slot.getPriority() == SlotPriority.CORE || slot.isRequired())
			.allMatch(slot -> isCollected(slot.getName(), collectedSlots) || hasDefaultValue(slot));

		if (!allCoreCollected) {
			boolean hasBlockedSlots = allSlots.stream()
				.filter(slot -> slot.getPriority() == SlotPriority.CORE || slot.isRequired())
				.filter(slot -> !isCollected(slot.getName(), collectedSlots))
				.anyMatch(slot -> !areDependenciesSatisfied(slot, collectedSlots)
						&& hasCyclicDependency(slot, allSlots));

			return hasBlockedSlots ? SlotCollectStatus.BLOCKED : SlotCollectStatus.COLLECTING;
		}

		return SlotCollectStatus.COMPLETE;
	}

	/**
	 * Get missing required slots.
	 */
	public List<String> getMissingRequiredSlots(List<SlotDefinition> allSlots,
			Map<String, SlotValue> collectedSlots) {

		if (allSlots == null) {
			return Collections.emptyList();
		}

		return allSlots.stream()
			.filter(slot -> slot.getPriority() == SlotPriority.CORE || slot.isRequired())
			.filter(slot -> !isCollected(slot.getName(), collectedSlots))
			.filter(slot -> !hasDefaultValue(slot))
			.map(SlotDefinition::getName)
			.collect(Collectors.toList());
	}

	/**
	 * Build final parameters map from collected slots.
	 */
	public Map<String, Object> buildFinalParams(List<SlotDefinition> allSlots,
			Map<String, SlotValue> collectedSlots) {

		Map<String, Object> params = new HashMap<>();

		for (SlotDefinition slot : allSlots) {
			String name = slot.getName();

			if (collectedSlots.containsKey(name)) {
				SlotValue slotValue = collectedSlots.get(name);
				params.put(name, slotValue.getResolvedValue());
			}
			else if (slot.getDefaultValue() != null) {
				params.put(name, slot.getDefaultValue());
			}
		}

		return params;
	}

	/**
	 * Collect slot values from Agent output (JSON string version).
	 */
	public Map<String, SlotValue> collectFromAgent(String agentOutputJson, List<SlotDefinition> allSlots,
			Map<String, SlotValue> existingCollectedSlots) {

		Map<String, SlotValue> collected = new HashMap<>();

		if (agentOutputJson == null || agentOutputJson.trim().isEmpty()) {
			return collected;
		}

		if (allSlots == null || allSlots.isEmpty()) {
			return collected;
		}

		try {
			ObjectMapper objectMapper = new ObjectMapper();
			Map<String, Object> agentData = objectMapper.readValue(agentOutputJson,
					new TypeReference<Map<String, Object>>() {
					});

			@SuppressWarnings("unchecked")
			Map<String, Object> extractionResult = (Map<String, Object>) agentData.get("slotExtractionResult");

			if (extractionResult == null || extractionResult.isEmpty()) {
				return collected;
			}

			return processExtractionResult(extractionResult, allSlots, existingCollectedSlots);
		}
		catch (Exception e) {
			logger.error("SlotCollectorService#collectFromAgent - failed to parse agent output, error={}",
					e.getMessage(), e);
		}

		return collected;
	}

	/**
	 * Collect slots from LLM extraction result (direct Map version).
	 */
	public Map<String, SlotValue> collectFromAgent(Map<String, Object> extractionResult,
			List<SlotDefinition> allSlots, Map<String, SlotValue> existingCollectedSlots) {

		if (extractionResult == null || extractionResult.isEmpty() || allSlots == null || allSlots.isEmpty()) {
			return new HashMap<>();
		}

		return processExtractionResult(extractionResult, allSlots, existingCollectedSlots);
	}

	private Map<String, SlotValue> processExtractionResult(Map<String, Object> extractionResult,
			List<SlotDefinition> allSlots, Map<String, SlotValue> existingCollectedSlots) {

		Map<String, SlotValue> collected = new HashMap<>();

		try {
			// Merge existing collected slots first (new values override old)
			if (existingCollectedSlots != null) {
				collected.putAll(existingCollectedSlots);
			}

			for (Map.Entry<String, Object> entry : extractionResult.entrySet()) {
				String slotName = entry.getKey();
				Object extractedValue = entry.getValue();

				if (extractedValue == null) {
					continue;
				}

				SlotDefinition slotDef = allSlots.stream()
					.filter(s -> s.getName().equals(slotName))
					.findFirst()
					.orElse(null);

				if (slotDef == null) {
					logger.warn("SlotCollectorService#collectFromAgent - unknown slot, slotName={}", slotName);
					continue;
				}

				SlotValue slotValue = convertToSlotValue(slotDef, extractedValue);
				if (slotValue != null) {
					collected.put(slotName, slotValue);
					logger.info("SlotCollectorService#collectFromAgent - collected slot, slotName={}, rawValue={}",
							slotName, slotValue.getRawValue());
				}
			}
		}
		catch (Exception e) {
			logger.error("SlotCollectorService#processExtractionResult - error={}", e.getMessage(), e);
		}

		return collected;
	}

	private SlotValue convertToSlotValue(SlotDefinition slotDef, Object extractedValue) {
		String slotName = slotDef.getName();
		String slotType = slotDef.getType();
		Object normalizedValue = normalizeExtractedValueByOptions(slotDef, extractedValue);

		try {
			if ("date".equalsIgnoreCase(slotType) || "datetime".equalsIgnoreCase(slotType)) {
				String dateStr = normalizedValue.toString();
				return SlotValue.resolved(slotName, dateStr, dateStr, dateStr);
			}
			else if ("integer".equalsIgnoreCase(slotType)) {
				if (normalizedValue instanceof Number) {
					int value = ((Number) normalizedValue).intValue();
					return SlotValue.resolved(slotName, String.valueOf(value), value, String.valueOf(value));
				}
				else {
					int value = Integer.parseInt(normalizedValue.toString());
					return SlotValue.resolved(slotName, normalizedValue.toString(), value, normalizedValue.toString());
				}
			}
			else if ("number".equalsIgnoreCase(slotType)) {
				if (normalizedValue instanceof Number) {
					double value = ((Number) normalizedValue).doubleValue();
					return SlotValue.resolved(slotName, String.valueOf(value), value, String.valueOf(value));
				}
				else {
					double value = Double.parseDouble(normalizedValue.toString());
					return SlotValue.resolved(slotName, normalizedValue.toString(), value, normalizedValue.toString());
				}
			}
			else if ("boolean".equalsIgnoreCase(slotType) || "bool".equalsIgnoreCase(slotType)) {
				boolean value;
				if (normalizedValue instanceof Boolean) {
					value = (Boolean) normalizedValue;
				}
				else {
					value = Boolean.parseBoolean(normalizedValue.toString());
				}
				return SlotValue.resolved(slotName, normalizedValue.toString(), value, normalizedValue.toString());
			}
			else {
				return SlotValue.resolved(slotName, normalizedValue.toString(), normalizedValue,
						normalizedValue.toString());
			}
		}
		catch (Exception e) {
			logger.warn(
					"SlotCollectorService#convertToSlotValue - conversion failed, slotName={}, slotType={}, error={}",
					slotName, slotType, e.getMessage());
			return null;
		}
	}

	private Object normalizeExtractedValueByOptions(SlotDefinition slotDef, Object extractedValue) {
		if (slotDef == null || extractedValue == null || slotDef.getOptions() == null) {
			return extractedValue;
		}
		Map<String, Object> enumMapping = slotDef.getOptions().getEnumMapping();
		if (enumMapping == null || enumMapping.isEmpty()) {
			return extractedValue;
		}
		if (extractedValue instanceof Number) {
			return extractedValue;
		}
		String rawText = String.valueOf(extractedValue).trim();
		if (rawText.isEmpty()) {
			return extractedValue;
		}
		if (enumMapping.containsKey(rawText)) {
			return enumMapping.get(rawText);
		}
		for (Map.Entry<String, Object> entry : enumMapping.entrySet()) {
			String label = entry.getKey();
			if (label != null && label.trim().equalsIgnoreCase(rawText)) {
				return entry.getValue();
			}
		}
		return extractedValue;
	}

	private boolean isCollected(String slotName, Map<String, SlotValue> collectedSlots) {
		return collectedSlots != null && collectedSlots.containsKey(slotName);
	}

	private boolean hasDefaultValue(SlotDefinition slot) {
		return slot.getDefaultValue() != null;
	}

	private boolean areDependenciesSatisfied(SlotDefinition slot, Map<String, SlotValue> collectedSlots) {
		List<String> dependsOn = slot.getDependsOn();
		if (dependsOn == null || dependsOn.isEmpty()) {
			return true;
		}
		return dependsOn.stream().allMatch(dep -> isCollected(dep, collectedSlots));
	}

	private boolean areInferenceSourcesReady(SlotDefinition slot, List<SlotDefinition> allSlots,
			Map<String, SlotValue> collectedSlots) {
		List<String> inferredFrom = slot != null ? slot.getInferredFrom() : null;
		if (inferredFrom == null || inferredFrom.isEmpty()) {
			return true;
		}
		for (String source : inferredFrom) {
			if (source == null || source.isBlank()) {
				continue;
			}
			SlotDefinition sourceSlot = findSlotDefinition(allSlots, source);
			if (sourceSlot == null) {
				if (!isCollected(source, collectedSlots)) {
					return false;
				}
				continue;
			}
			if (!isCollected(source, collectedSlots) && !hasDefaultValue(sourceSlot) && !sourceSlot.isComputed()) {
				return false;
			}
		}
		return true;
	}

	private SlotDefinition findSlotDefinition(List<SlotDefinition> allSlots, String slotName) {
		if (allSlots == null || allSlots.isEmpty() || slotName == null || slotName.isBlank()) {
			return null;
		}
		return allSlots.stream()
				.filter(slot -> slot != null && slotName.equals(slot.getName()))
				.findFirst()
				.orElse(null);
	}

	private boolean hasCyclicDependency(SlotDefinition slot, List<SlotDefinition> allSlots) {
		Set<String> visited = new HashSet<>();
		return hasCycle(slot.getName(), allSlots, visited);
	}

	private boolean hasCycle(String slotName, List<SlotDefinition> allSlots, Set<String> visited) {
		if (visited.contains(slotName)) {
			return true;
		}
		visited.add(slotName);

		SlotDefinition slot = allSlots.stream().filter(s -> s.getName().equals(slotName)).findFirst().orElse(null);

		if (slot == null || slot.getDependsOn() == null) {
			return false;
		}

		for (String dep : slot.getDependsOn()) {
			if (hasCycle(dep, allSlots, new HashSet<>(visited))) {
				return true;
			}
		}

		return false;
	}

	private int getPriorityOrder(SlotPriority priority) {
		if (priority == null) {
			return 1;
		}
		switch (priority) {
			case CORE:
				return 0;
			case CONFIRM:
				return 1;
			case OPTIONAL:
				return 2;
			case SUPPLEMENTARY:
				return 3;
			default:
				return 1;
		}
	}

}

