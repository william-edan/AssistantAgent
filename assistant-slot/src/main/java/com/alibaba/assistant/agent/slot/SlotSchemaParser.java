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
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Parser for slot schema.
 * Parses slot_schema JSON and also supports backward compatibility with request_schema.
 *
 * @author Assistant Agent Team
 * @since 1.0.0
 */
@Service
public class SlotSchemaParser {

	private static final Logger logger = LoggerFactory.getLogger(SlotSchemaParser.class);

	private final ObjectMapper objectMapper;

	public SlotSchemaParser(ObjectMapper objectMapper) {
		this.objectMapper = objectMapper;
	}

	/**
	 * Parse slot definitions from a ToolMetaSnapshot.
	 * Prefers slot_schema if available, falls back to request_schema for backward compatibility.
	 *
	 * @param snapshot the tool meta snapshot
	 * @return list of slot definitions
	 */
	public List<SlotDefinition> parse(ToolMetaSnapshot snapshot) {
		if (snapshot == null) {
			return Collections.emptyList();
		}

		String slotSchema = snapshot.getSlotSchema();
		if (slotSchema != null && !slotSchema.trim().isEmpty()) {
			try {
				return parseSlotSchema(slotSchema);
			}
			catch (Exception e) {
				logger.warn("SlotSchemaParser#parse - failed to parse slot_schema, toolCode={}, error={}",
						snapshot.getToolCode(), e.getMessage());
			}
		}

		String requestSchema = snapshot.getRequestSchema();
		if (requestSchema != null && !requestSchema.trim().isEmpty()) {
			try {
				return parseRequestSchema(requestSchema);
			}
			catch (Exception e) {
				logger.warn("SlotSchemaParser#parse - failed to parse request_schema, toolCode={}, error={}",
						snapshot.getToolCode(), e.getMessage());
			}
		}

		return Collections.emptyList();
	}

	/**
	 * Parse raw slot_schema JSON string directly.
	 */
	public List<SlotDefinition> parseSlotSchema(String slotSchema) throws Exception {
		JsonNode rootNode = objectMapper.readTree(slotSchema);
		JsonNode slotsNode = rootNode.get("slots");

		if (slotsNode == null || !slotsNode.isArray()) {
			return Collections.emptyList();
		}

		List<SlotDefinition> slots = new ArrayList<>();
		for (JsonNode slotNode : slotsNode) {
			SlotDefinition slot = parseSlotNode(slotNode);
			if (slot != null) {
				slots.add(slot);
			}
		}

		return slots;
	}

	/**
	 * Parse old request_schema format (JSON Schema) to slot definitions.
	 */
	public List<SlotDefinition> parseRequestSchema(String requestSchema) throws Exception {
		JsonNode rootNode = objectMapper.readTree(requestSchema);
		JsonNode propertiesNode = rootNode.get("properties");
		JsonNode requiredNode = rootNode.get("required");

		if (propertiesNode == null) {
			return Collections.emptyList();
		}

		Set<String> requiredFields = new HashSet<>();
		if (requiredNode != null && requiredNode.isArray()) {
			for (JsonNode field : requiredNode) {
				requiredFields.add(field.asText());
			}
		}

		List<SlotDefinition> slots = new ArrayList<>();
		Iterator<Map.Entry<String, JsonNode>> fields = propertiesNode.fields();

		while (fields.hasNext()) {
			Map.Entry<String, JsonNode> entry = fields.next();
			String fieldName = entry.getKey();
			JsonNode fieldNode = entry.getValue();
			SlotDefinition slot = parsePropertyToSlot(fieldName, fieldNode, requiredFields.contains(fieldName));
			slots.add(slot);
		}

		return slots;
	}

	private SlotDefinition parseSlotNode(JsonNode slotNode) {
		SlotDefinition slot = new SlotDefinition();

		slot.setName(getTextOrNull(slotNode, "name"));
		slot.setType(getTextOrNull(slotNode, "type"));
		slot.setTitle(getTextOrNull(slotNode, "title"));
		slot.setDescription(getTextOrNull(slotNode, "description"));

		String aiHint = getTextOrNull(slotNode, "ai_hint");
		if (aiHint == null) {
			aiHint = getTextOrNull(slotNode, "aiHint");
		}
		slot.setAiHint(aiHint);

		String priority = getTextOrNull(slotNode, "priority");
		if (priority != null) {
			try {
				slot.setPriority(SlotPriority.valueOf(priority.toUpperCase()));
			}
			catch (IllegalArgumentException e) {
				slot.setPriority(SlotPriority.CORE);
			}
		}

		slot.setRequired(slotNode.has("required") && slotNode.get("required").asBoolean());

		String askMode = getTextOrNull(slotNode, "ask_mode");
		if (askMode == null) {
			askMode = getTextOrNull(slotNode, "askMode");
		}
		if (askMode != null) {
			try {
				slot.setAskMode(SlotAskMode.valueOf(askMode.toUpperCase()));
			}
			catch (IllegalArgumentException e) {
				slot.setAskMode(SlotAskMode.AUTO);
			}
		}

		JsonNode defaultNode = slotNode.get("default_value");
		if (defaultNode == null) {
			defaultNode = slotNode.get("default");
		}
		if (defaultNode == null) {
			defaultNode = slotNode.get("defaultValue");
		}
		if (defaultNode != null && !defaultNode.isNull()) {
			slot.setDefaultValue(parseJsonValue(defaultNode));
		}

		JsonNode optionsNode = slotNode.get("options");
		if (optionsNode != null) {
			slot.setOptions(parseOptions(optionsNode));
		}

		JsonNode autoSelectNode = slotNode.get("auto_select");
		if (autoSelectNode == null) {
			autoSelectNode = slotNode.get("autoSelect");
		}
		if (autoSelectNode != null) {
			slot.setAutoSelect(parseAutoSelect(autoSelectNode));
		}

		JsonNode dependsOnNode = slotNode.get("depends_on");
		if (dependsOnNode == null) {
			dependsOnNode = slotNode.get("dependsOn");
		}
		if (dependsOnNode != null && dependsOnNode.isArray()) {
			List<String> dependsOn = new ArrayList<>();
			for (JsonNode dep : dependsOnNode) {
				dependsOn.add(dep.asText());
			}
			slot.setDependsOn(dependsOn);
		}

		JsonNode inferredFromNode = slotNode.get("inferred_from");
		if (inferredFromNode == null) {
			inferredFromNode = slotNode.get("inferredFrom");
		}
		if (inferredFromNode != null && inferredFromNode.isArray()) {
			List<String> inferredFrom = new ArrayList<>();
			for (JsonNode source : inferredFromNode) {
				inferredFrom.add(source.asText());
			}
			slot.setInferredFrom(inferredFrom);
		}

		String conditionalRequired = getTextOrNull(slotNode, "conditional_required");
		if (conditionalRequired == null) {
			conditionalRequired = getTextOrNull(slotNode, "conditionalRequired");
		}
		slot.setConditionalRequired(conditionalRequired);

		JsonNode computedNode = slotNode.get("computed");
		if (computedNode != null && !computedNode.isNull()) {
			slot.setComputed(parseComputed(computedNode));
		}

		String uiComponent = getTextOrNull(slotNode, "uiComponent");
		if (uiComponent == null) {
			uiComponent = getTextOrNull(slotNode, "ui_component");
		}
		slot.setUiComponent(uiComponent);

		JsonNode displayConfigNode = slotNode.get("displayConfig");
		if (displayConfigNode == null) {
			displayConfigNode = slotNode.get("display_config");
		}
		if (displayConfigNode != null && !displayConfigNode.isNull()) {
			slot.setDisplayConfig(parseDisplayConfig(displayConfigNode));
		}

		return slot;
	}

	private SlotDisplayConfig parseDisplayConfig(JsonNode displayConfigNode) {
		SlotDisplayConfig config = new SlotDisplayConfig();

		if (displayConfigNode.has("showInSummary")) {
			config.setShowInSummary(displayConfigNode.get("showInSummary").asBoolean());
		}
		else if (displayConfigNode.has("show_in_summary")) {
			config.setShowInSummary(displayConfigNode.get("show_in_summary").asBoolean());
		}

		JsonNode orderNode = displayConfigNode.get("summaryOrder");
		if (orderNode == null) {
			orderNode = displayConfigNode.get("summary_order");
		}
		if (orderNode != null && !orderNode.isNull()) {
			config.setSummaryOrder(orderNode.asInt());
		}

		String icon = getTextOrNull(displayConfigNode, "summaryIcon");
		if (icon == null) {
			icon = getTextOrNull(displayConfigNode, "summary_icon");
		}
		config.setSummaryIcon(icon);

		String group = getTextOrNull(displayConfigNode, "summaryGroup");
		if (group == null) {
			group = getTextOrNull(displayConfigNode, "summary_group");
		}
		if (group != null) {
			config.setSummaryGroup(group);
		}

		String format = getTextOrNull(displayConfigNode, "displayFormat");
		if (format == null) {
			format = getTextOrNull(displayConfigNode, "display_format");
		}
		config.setDisplayFormat(format);

		String suffix = getTextOrNull(displayConfigNode, "displaySuffix");
		if (suffix == null) {
			suffix = getTextOrNull(displayConfigNode, "display_suffix");
		}
		config.setDisplaySuffix(suffix);

		String prefix = getTextOrNull(displayConfigNode, "displayPrefix");
		if (prefix == null) {
			prefix = getTextOrNull(displayConfigNode, "display_prefix");
		}
		config.setDisplayPrefix(prefix);

		if (displayConfigNode.has("inlineEditable")) {
			config.setInlineEditable(displayConfigNode.get("inlineEditable").asBoolean());
		}
		else if (displayConfigNode.has("inline_editable")) {
			config.setInlineEditable(displayConfigNode.get("inline_editable").asBoolean());
		}

		String label = getTextOrNull(displayConfigNode, "displayLabel");
		if (label == null) {
			label = getTextOrNull(displayConfigNode, "display_label");
		}
		config.setDisplayLabel(label);

		String type = getTextOrNull(displayConfigNode, "displayType");
		if (type == null) {
			type = getTextOrNull(displayConfigNode, "display_type");
		}
		config.setDisplayType(type);

		return config;
	}

	private ComputedFieldConfig parseComputed(JsonNode computedNode) {
		ComputedFieldConfig config = new ComputedFieldConfig();

		if (computedNode.has("enabled")) {
			config.setEnabled(computedNode.get("enabled").asBoolean());
		}

		String type = getTextOrNull(computedNode, "type");
		if (type != null) {
			try {
				config.setType(ComputedFieldConfig.ComputationType.valueOf(type.toUpperCase()));
			}
			catch (IllegalArgumentException e) {
				logger.warn("SlotSchemaParser#parseComputed - unknown computation type: {}", type);
			}
		}

		config.setFunction(getTextOrNull(computedNode, "function"));
		config.setExpression(getTextOrNull(computedNode, "expression"));
		config.setScript(getTextOrNull(computedNode, "script"));

		JsonNode paramsNode = computedNode.get("params");
		if (paramsNode != null && paramsNode.isObject()) {
			try {
				Map<String, Object> params = objectMapper.convertValue(paramsNode,
						new TypeReference<Map<String, Object>>() {
						});
				config.setParams(params);
			}
			catch (Exception e) {
				logger.warn("SlotSchemaParser#parseComputed - failed to parse params: {}", e.getMessage());
			}
		}

		JsonNode defaultValueNode = computedNode.get("default_value");
		if (defaultValueNode == null) {
			defaultValueNode = computedNode.get("defaultValue");
		}
		if (defaultValueNode != null && !defaultValueNode.isNull()) {
			config.setDefaultValue(parseJsonValue(defaultValueNode));
		}

		return config;
	}

	private SlotDefinition parsePropertyToSlot(String name, JsonNode fieldNode, boolean required) {
		SlotDefinition slot = new SlotDefinition();

		slot.setName(name);
		slot.setType(getTextOrNull(fieldNode, "type"));
		slot.setTitle(getTextOrNull(fieldNode, "title"));
		slot.setDescription(getTextOrNull(fieldNode, "description"));
		slot.setAiHint(getTextOrNull(fieldNode, "ai_hint"));
		slot.setRequired(required);
		slot.setPriority(required ? SlotPriority.CORE : SlotPriority.OPTIONAL);

		JsonNode defaultNode = fieldNode.get("default");
		if (defaultNode == null) {
			defaultNode = fieldNode.get("default_value");
		}
		if (defaultNode == null) {
			defaultNode = fieldNode.get("defaultValue");
		}
		if (defaultNode != null && !defaultNode.isNull()) {
			slot.setDefaultValue(parseJsonValue(defaultNode));
		}

		JsonNode enumMappingNode = fieldNode.get("enum_mapping");
		if (enumMappingNode != null && enumMappingNode.isObject()) {
			slot.setOptions(parseEnumMapping(enumMappingNode));
		}

		JsonNode dataSourceNode = fieldNode.get("data_source");
		if (dataSourceNode != null && dataSourceNode.isObject()) {
			slot.setOptions(parseDataSource(dataSourceNode));
		}

		return slot;
	}

	private SlotOptions parseOptions(JsonNode optionsNode) {
		SlotOptions options = new SlotOptions();

		String source = getTextOrNull(optionsNode, "source");
		if (source != null) {
			try {
				options.setSource(SlotOptions.SourceType.valueOf(source.toUpperCase()));
			}
			catch (IllegalArgumentException e) {
				options.setSource(SlotOptions.SourceType.STATIC);
			}
		}

		JsonNode valuesNode = optionsNode.get("values");
		if (valuesNode != null && valuesNode.isArray()) {
			List<SlotOptions.OptionValue> values = new ArrayList<>();
			for (JsonNode valueNode : valuesNode) {
				SlotOptions.OptionValue optionValue = new SlotOptions.OptionValue();
				optionValue.setValue(getTextOrNull(valueNode, "value"));
				optionValue.setLabel(getTextOrNull(valueNode, "label"));
				optionValue.setDescription(getTextOrNull(valueNode, "description"));
				values.add(optionValue);
			}
			options.setValues(values);
		}

		JsonNode apiConfigNode = optionsNode.get("api");
		if (apiConfigNode == null) {
			apiConfigNode = optionsNode.get("apiConfig");
		}
		if (apiConfigNode != null) {
			options.setApiConfig(parseApiConfigFromDb(apiConfigNode));
		}

		JsonNode autoSelectNode = optionsNode.get("auto_select");
		if (autoSelectNode == null) {
			autoSelectNode = optionsNode.get("autoSelect");
		}
		if (autoSelectNode != null) {
			options.setAutoSelect(parseAutoSelectFromOptions(autoSelectNode));
		}

		JsonNode enumMappingNode = optionsNode.get("enum_mapping");
		if (enumMappingNode == null) {
			enumMappingNode = optionsNode.get("enumMapping");
		}
		if (enumMappingNode != null && enumMappingNode.isObject()) {
			try {
				Map<String, Object> enumMapping = objectMapper.convertValue(enumMappingNode,
						new TypeReference<Map<String, Object>>() {
						});
				options.setEnumMapping(enumMapping);

				if (options.getSource() == null) {
					options.setSource(SlotOptions.SourceType.ENUM);
				}
			}
			catch (Exception e) {
				logger.warn("SlotSchemaParser#parseOptions - failed to parse enumMapping: {}", e.getMessage());
			}
		}

		options.setMultiSelect(optionsNode.has("multiSelect") && optionsNode.get("multiSelect").asBoolean());

		return options;
	}

	private SlotOptions.ApiConfig parseApiConfigFromDb(JsonNode apiConfigNode) {
		SlotOptions.ApiConfig apiConfig = new SlotOptions.ApiConfig();
		apiConfig.setEndpoint(getTextOrNull(apiConfigNode, "endpoint"));
		apiConfig.setMethod(getTextOrNull(apiConfigNode, "method"));

		String valueField = getTextOrNull(apiConfigNode, "value_field");
		if (valueField == null) {
			valueField = getTextOrNull(apiConfigNode, "valueField");
		}
		apiConfig.setValueField(valueField);

		String labelField = getTextOrNull(apiConfigNode, "label_field");
		if (labelField == null) {
			labelField = getTextOrNull(apiConfigNode, "labelField");
		}
		apiConfig.setLabelField(labelField);

		String childrenField = getTextOrNull(apiConfigNode, "children_field");
		if (childrenField == null) {
			childrenField = getTextOrNull(apiConfigNode, "childrenField");
		}
		apiConfig.setChildrenField(childrenField);

		JsonNode treeStructureNode = apiConfigNode.get("tree_structure");
		if (treeStructureNode == null) {
			treeStructureNode = apiConfigNode.get("treeStructure");
		}
		if (treeStructureNode != null && treeStructureNode.isBoolean()) {
			apiConfig.setTreeStructure(treeStructureNode.asBoolean());
		}

		JsonNode paramsNode = apiConfigNode.get("params");
		if (paramsNode != null && paramsNode.isObject()) {
			try {
				apiConfig.setParams(objectMapper.convertValue(paramsNode, new TypeReference<Map<String, String>>() {
				}));
			}
			catch (Exception e) {
				logger.warn("SlotSchemaParser#parseApiConfigFromDb - failed to parse params", e);
			}
		}

		return apiConfig;
	}

	private SlotOptions.AutoSelectConfig parseAutoSelectFromOptions(JsonNode autoSelectNode) {
		SlotOptions.AutoSelectConfig autoSelect = new SlotOptions.AutoSelectConfig();
		autoSelect.setEnabled(autoSelectNode.has("enabled") && autoSelectNode.get("enabled").asBoolean());
		autoSelect.setStrategy(getTextOrNull(autoSelectNode, "strategy"));
		autoSelect.setWhen(getTextOrNull(autoSelectNode, "when"));
		return autoSelect;
	}

	private SlotOptions parseEnumMapping(JsonNode enumMappingNode) {
		SlotOptions options = new SlotOptions();
		options.setSource(SlotOptions.SourceType.STATIC);

		List<SlotOptions.OptionValue> values = new ArrayList<>();
		Iterator<Map.Entry<String, JsonNode>> fields = enumMappingNode.fields();
		while (fields.hasNext()) {
			Map.Entry<String, JsonNode> entry = fields.next();
			SlotOptions.OptionValue optionValue = new SlotOptions.OptionValue();
			optionValue.setLabel(entry.getKey());
			optionValue.setValue(entry.getValue().asText());
			values.add(optionValue);
		}
		options.setValues(values);

		return options;
	}

	private SlotOptions parseDataSource(JsonNode dataSourceNode) {
		SlotOptions options = new SlotOptions();

		String type = getTextOrNull(dataSourceNode, "type");
		if ("api".equalsIgnoreCase(type)) {
			options.setSource(SlotOptions.SourceType.API);
		}
		else if ("search".equalsIgnoreCase(type)) {
			options.setSource(SlotOptions.SourceType.SEARCH);
		}
		else {
			options.setSource(SlotOptions.SourceType.STATIC);
		}

		SlotOptions.ApiConfig apiConfig = new SlotOptions.ApiConfig();
		apiConfig.setEndpoint(getTextOrNull(dataSourceNode, "endpoint"));
		apiConfig.setMethod(getTextOrNull(dataSourceNode, "method"));
		apiConfig.setValueField(getTextOrNull(dataSourceNode, "value_field"));
		apiConfig.setLabelField(getTextOrNull(dataSourceNode, "label_field"));

		JsonNode paramsNode = dataSourceNode.get("params");
		if (paramsNode != null && paramsNode.isObject()) {
			Map<String, String> params = new HashMap<>();
			Iterator<Map.Entry<String, JsonNode>> fields = paramsNode.fields();
			while (fields.hasNext()) {
				Map.Entry<String, JsonNode> entry = fields.next();
				params.put(entry.getKey(), entry.getValue().asText());
			}
			apiConfig.setParams(params);
		}

		options.setApiConfig(apiConfig);

		return options;
	}

	private SlotAutoSelect parseAutoSelect(JsonNode autoSelectNode) {
		SlotAutoSelect autoSelect = new SlotAutoSelect();

		String strategy = getTextOrNull(autoSelectNode, "strategy");
		if (strategy != null) {
			try {
				autoSelect.setStrategy(SlotAutoSelect.Strategy.valueOf(strategy.toUpperCase()));
			}
			catch (IllegalArgumentException e) {
				autoSelect.setStrategy(SlotAutoSelect.Strategy.NONE);
			}
		}

		autoSelect.setValue(getTextOrNull(autoSelectNode, "value"));
		autoSelect.setCondition(getTextOrNull(autoSelectNode, "condition"));
		autoSelect.setConfirmRequired(
				autoSelectNode.has("confirmRequired") && autoSelectNode.get("confirmRequired").asBoolean());

		return autoSelect;
	}

	private String getTextOrNull(JsonNode node, String fieldName) {
		if (node == null || !node.has(fieldName)) {
			return null;
		}
		JsonNode fieldNode = node.get(fieldName);
		return fieldNode.isNull() ? null : fieldNode.asText();
	}

	private Object parseJsonValue(JsonNode node) {
		if (node.isTextual()) {
			return node.asText();
		}
		else if (node.isInt()) {
			return node.asInt();
		}
		else if (node.isLong()) {
			return node.asLong();
		}
		else if (node.isDouble()) {
			return node.asDouble();
		}
		else if (node.isBoolean()) {
			return node.asBoolean();
		}
		else if (node.isArray() || node.isObject()) {
			try {
				return objectMapper.treeToValue(node, Object.class);
			}
			catch (Exception e) {
				return node.toString();
			}
		}
		return node.asText();
	}

}
