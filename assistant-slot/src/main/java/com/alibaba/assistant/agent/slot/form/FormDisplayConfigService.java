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
package com.alibaba.assistant.agent.slot.form;

import com.alibaba.assistant.agent.slot.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Form display configuration service.
 * Generates form summary from slot_schema displayConfig, replacing runtime LLM calls.
 * Uses rule engine fallback when displayConfig is not provided.
 *
 * @author Assistant Agent Team
 * @since 1.0.0
 */
@Service
public class FormDisplayConfigService {

	private static final Logger logger = LoggerFactory.getLogger(FormDisplayConfigService.class);

	private static final Map<String, String> ICON_MAPPING = Map.ofEntries(
			Map.entry("date", "\uD83D\uDCC5"), Map.entry("start_date", "\uD83D\uDCC5"),
			Map.entry("end_date", "\uD83D\uDCC5"), Map.entry("time", "\uD83D\uDD50"),
			Map.entry("datetime", "\uD83D\uDCC5"), Map.entry("duration", "\u23F1"),
			Map.entry("days", "\u23F1"), Map.entry("type", "\uD83D\uDCCB"),
			Map.entry("category", "\uD83D\uDCCB"), Map.entry("user", "\uD83D\uDC64"),
			Map.entry("approver", "\uD83D\uDC64"), Map.entry("check_uids", "\uD83D\uDC64"),
			Map.entry("person", "\uD83D\uDC64"), Map.entry("cc", "\uD83D\uDCE7"),
			Map.entry("cc_uids", "\uD83D\uDCE7"), Map.entry("reason", "\uD83D\uDCDD"),
			Map.entry("description", "\uD83D\uDCDD"), Map.entry("remark", "\uD83D\uDCDD"),
			Map.entry("content", "\uD83D\uDCDD"), Map.entry("amount", "\uD83D\uDCB0"),
			Map.entry("price", "\uD83D\uDCB0"), Map.entry("location", "\uD83D\uDCCD"),
			Map.entry("address", "\uD83D\uDCCD"), Map.entry("status", "\uD83D\uDD16"),
			Map.entry("priority", "\uD83D\uDD25"), Map.entry("name", "\uD83C\uDFF7\uFE0F"),
			Map.entry("title", "\uD83D\uDCCC"));

	/**
	 * Build form summary from slot definitions and collected values.
	 * Uses displayConfig if available, otherwise falls back to rule engine.
	 *
	 * @param slotDefinitions list of slot definitions with displayConfig
	 * @param collectedSlots map of collected slot values
	 * @return FormSummary containing summaryItems and secondaryItems
	 */
	public FormSummary buildSummary(List<SlotDefinition> slotDefinitions, Map<String, SlotValue> collectedSlots) {
		FormSummary summary = new FormSummary();

		if (slotDefinitions == null || slotDefinitions.isEmpty()) {
			return summary;
		}

		List<SummaryCandidate> coreCandidates = new ArrayList<>();
		List<SummaryCandidate> secondaryCandidates = new ArrayList<>();
		Set<String> processedSlots = new HashSet<>();

		for (SlotDefinition slotDef : slotDefinitions) {
			if (processedSlots.contains(slotDef.getName())) {
				continue;
			}
			processedSlots.add(slotDef.getName());

			SlotValue slotValue = collectedSlots.get(slotDef.getName());
			if (slotValue == null || slotValue.getResolvedValue() == null) {
				continue;
			}

			SummaryCandidate candidate = buildCandidate(slotDef, slotValue);
			if (candidate == null || !candidate.showInSummary) {
				continue;
			}

			if (candidate.isSecondaryGroup) {
				secondaryCandidates.add(candidate);
			}
			else {
				coreCandidates.add(candidate);
			}
		}

		coreCandidates.sort(Comparator.comparingInt(c -> c.order));
		secondaryCandidates.sort(Comparator.comparingInt(c -> c.order));

		for (SummaryCandidate candidate : coreCandidates) {
			FormSummary.SummaryItem item = FormSummary.SummaryItem.of(candidate.displayLabel,
					candidate.formattedValue, candidate.icon, candidate.order, "CORE");
			summary.getSummaryItems().add(item);
		}

		for (SummaryCandidate candidate : secondaryCandidates) {
			FormSummary.SecondaryItem item = FormSummary.SecondaryItem.of(candidate.displayLabel,
					candidate.formattedValue, candidate.icon);
			item.setType(candidate.displayType);
			item.setGroup(candidate.group);
			summary.getSecondaryItems().add(item);
		}

		logger.info("FormDisplayConfigService#buildSummary - coreCount={}, secondaryCount={}",
				summary.getSummaryItems().size(), summary.getSecondaryItems().size());

		return summary;
	}

	private SummaryCandidate buildCandidate(SlotDefinition slotDef, SlotValue slotValue) {
		SlotDisplayConfig displayConfig = slotDef.getDisplayConfig();
		Object rawValue = slotValue.getResolvedValue();
		String preferredDisplayValue = slotValue.getDisplayValue();
		boolean hasDisplayValue = preferredDisplayValue != null && !preferredDisplayValue.isEmpty();

		if (displayConfig == null) {
			return buildCandidateFromRules(slotDef, slotValue);
		}

		SummaryCandidate candidate = new SummaryCandidate();
		candidate.name = slotDef.getName();
		candidate.showInSummary = displayConfig.isShowInSummary();

		if (!candidate.showInSummary) {
			return candidate;
		}

		String slotName = slotDef.getName().toLowerCase();
		boolean isPersonField = slotName.contains("uid") || slotName.contains("user")
				|| slotName.contains("approver") || slotName.contains("check") || slotName.contains("cc");

		String tempLabel = displayConfig.getDisplayLabel();
		if (tempLabel == null || tempLabel.isEmpty()) {
			tempLabel = hasDisplayValue ? preferredDisplayValue : formatDisplayValue(rawValue, slotDef);
		}
		else {
			tempLabel = hasDisplayValue ? preferredDisplayValue : displayConfig.formatValue(rawValue);
		}

		if (isPersonField && tempLabel != null && tempLabel.equals(String.valueOf(rawValue))) {
			String title = slotDef.getTitle();
			candidate.displayLabel = (title != null && !title.isEmpty()) ? title : tempLabel;
		}
		else {
			candidate.displayLabel = tempLabel;
		}

		if (hasDisplayValue) {
			candidate.formattedValue = preferredDisplayValue;
		}
		else {
			candidate.formattedValue = displayConfig.formatValue(rawValue);
			if (candidate.formattedValue == null || candidate.formattedValue.isEmpty()) {
				candidate.formattedValue = formatDisplayValue(rawValue, slotDef);
			}
		}

		candidate.icon = displayConfig.getSummaryIcon();
		if (candidate.icon == null || candidate.icon.isEmpty()) {
			candidate.icon = inferIcon(slotDef.getName(), slotDef.getType());
		}

		candidate.order = displayConfig.getSummaryOrder() != null ? displayConfig.getSummaryOrder()
				: inferOrder(slotDef);
		candidate.isSecondaryGroup = displayConfig.isSecondaryGroup();
		candidate.group = displayConfig.getSummaryGroup();
		candidate.displayType = displayConfig.getDisplayType();
		if (candidate.displayType == null) {
			candidate.displayType = inferDisplayType(slotDef.getName());
		}

		return candidate;
	}

	private SummaryCandidate buildCandidateFromRules(SlotDefinition slotDef, SlotValue slotValue) {
		SummaryCandidate candidate = new SummaryCandidate();
		candidate.name = slotDef.getName();

		Object rawValue = slotValue.getResolvedValue();
		String preferredDisplayValue = slotValue.getDisplayValue();
		boolean hasDisplayValue = preferredDisplayValue != null && !preferredDisplayValue.isEmpty();

		SlotPriority priority = slotDef.getPriority();
		if (priority == SlotPriority.OPTIONAL) {
			candidate.showInSummary = false;
			return candidate;
		}

		String name = slotDef.getName().toLowerCase();
		boolean isPersonField = name.contains("uid") || name.contains("user") || name.contains("approver")
				|| name.contains("check") || name.contains("cc");

		candidate.showInSummary = true;
		candidate.isSecondaryGroup = isPersonField;
		candidate.group = isPersonField ? "SECONDARY" : "CORE";

		String displayVal = hasDisplayValue ? preferredDisplayValue : formatDisplayValue(rawValue, slotDef);

		if (isPersonField && displayVal != null && displayVal.equals(String.valueOf(rawValue))) {
			String title = slotDef.getTitle();
			candidate.displayLabel = (title != null && !title.isEmpty()) ? title : displayVal;
		}
		else {
			candidate.displayLabel = displayVal;
		}
		candidate.formattedValue = displayVal;

		candidate.icon = inferIcon(slotDef.getName(), slotDef.getType());
		candidate.order = inferOrder(slotDef);
		candidate.displayType = inferDisplayType(slotDef.getName());

		return candidate;
	}

	private String inferIcon(String fieldName, String fieldType) {
		if (fieldName == null) {
			return "\uD83D\uDCCB";
		}

		String lowerName = fieldName.toLowerCase();

		if (ICON_MAPPING.containsKey(lowerName)) {
			return ICON_MAPPING.get(lowerName);
		}

		for (Map.Entry<String, String> entry : ICON_MAPPING.entrySet()) {
			if (lowerName.contains(entry.getKey())) {
				return entry.getValue();
			}
		}

		if (fieldType != null) {
			switch (fieldType.toLowerCase()) {
				case "date":
				case "datetime":
					return "\uD83D\uDCC5";
				case "number":
				case "integer":
					return "\uD83D\uDD22";
				case "boolean":
					return "\u2705";
				default:
					break;
			}
		}

		return "\uD83D\uDCCB";
	}

	private int inferOrder(SlotDefinition slotDef) {
		SlotPriority priority = slotDef.getPriority();
		if (priority == SlotPriority.CORE) {
			return 1;
		}
		else if (priority == SlotPriority.SUPPLEMENTARY) {
			return 50;
		}
		else {
			return 100;
		}
	}

	private String inferDisplayType(String fieldName) {
		if (fieldName == null) {
			return "text";
		}
		String lowerName = fieldName.toLowerCase();
		if (lowerName.contains("uid") || lowerName.contains("user") || lowerName.contains("approver")
				|| lowerName.contains("person")) {
			return "avatar";
		}
		return "text";
	}

	private String formatDisplayValue(Object value, SlotDefinition slotDef) {
		if (value == null) {
			return "";
		}

		if (slotDef != null && slotDef.hasOptions()) {
			SlotOptions options = slotDef.getOptions();

			if (options.getValues() != null) {
				for (SlotOptions.OptionValue opt : options.getValues()) {
					if (value.equals(opt.getValue())
							|| String.valueOf(value).equals(String.valueOf(opt.getValue()))) {
						return opt.getLabel();
					}
				}
			}

			if (options.getEnumMapping() != null) {
				for (Map.Entry<String, Object> entry : options.getEnumMapping().entrySet()) {
					if (value.equals(entry.getValue())
							|| String.valueOf(value).equals(String.valueOf(entry.getValue()))) {
						return entry.getKey();
					}
				}
			}
		}

		return String.valueOf(value);
	}

	private static class SummaryCandidate {

		String name;

		boolean showInSummary;

		String displayLabel;

		String formattedValue;

		String icon;

		int order;

		boolean isSecondaryGroup;

		String group;

		String displayType;

	}

}
