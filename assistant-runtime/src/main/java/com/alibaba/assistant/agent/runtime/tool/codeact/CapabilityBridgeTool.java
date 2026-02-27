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
package com.alibaba.assistant.agent.runtime.tool.codeact;

import com.alibaba.assistant.agent.common.enums.Language;
import com.alibaba.assistant.agent.common.tools.CodeactToolMetadata;
import com.alibaba.assistant.agent.common.tools.DefaultCodeactToolMetadata;
import com.alibaba.assistant.agent.controlplane.toolregistry.ToolMeta;
import com.alibaba.assistant.agent.execution.flow.DAGFlowExecutor;
import com.alibaba.assistant.agent.execution.flow.FlowContext;
import com.alibaba.assistant.agent.execution.flow.FlowDefinition;
import com.alibaba.assistant.agent.execution.flow.FlowDefinitionConverter;
import com.alibaba.assistant.agent.execution.flow.FlowExecutionResult;
import com.alibaba.assistant.agent.execution.model.StepConfig;
import com.alibaba.assistant.agent.execution.model.StepResult;
import com.alibaba.assistant.agent.execution.step.HttpStepExecutor;
import com.alibaba.assistant.agent.extension.dynamic.tool.AbstractDynamicCodeactTool;
import com.alibaba.assistant.agent.runtime.agent.AssistantStateKeys;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.agent.tools.ToolContextConstants;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.lang.Nullable;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Bridge tool that wraps a ToolMeta capability for CodeAct phase execution.
 * Supports FLOW mode (DAGFlowExecutor) and SIMPLE mode (single HttpStepExecutor).
 *
 * @author Assistant Agent Team
 * @since 1.0.0
 */
public class CapabilityBridgeTool extends AbstractDynamicCodeactTool {

	private static final Logger logger = LoggerFactory.getLogger(CapabilityBridgeTool.class);

	private final ToolMeta toolMeta;

	private final FlowDefinitionConverter flowDefinitionConverter;

	private final DAGFlowExecutor dagFlowExecutor;

	private final HttpStepExecutor httpStepExecutor;

	public CapabilityBridgeTool(ObjectMapper objectMapper,
			ToolMeta toolMeta,
			FlowDefinitionConverter flowDefinitionConverter,
			DAGFlowExecutor dagFlowExecutor,
			HttpStepExecutor httpStepExecutor,
			String bridgeToolName,
			String targetClassName) {
		super(objectMapper, buildToolDefinition(objectMapper, toolMeta, bridgeToolName),
				buildMetadata(toolMeta, bridgeToolName, targetClassName));
		this.toolMeta = toolMeta;
		this.flowDefinitionConverter = flowDefinitionConverter;
		this.dagFlowExecutor = dagFlowExecutor;
		this.httpStepExecutor = httpStepExecutor;
	}

	public ToolMeta getToolMeta() {
		return toolMeta;
	}

	@Override
	protected String doCall(Map<String, Object> args, @Nullable ToolContext toolContext) throws Exception {
		Map<String, Object> inputParams = extractInputParams(args);
		FlowContext flowContext = buildFlowContext(inputParams, toolContext);

		Map<String, Object> resultPayload;
		if (StringUtils.hasText(toolMeta.getExecutionPlan())) {
			resultPayload = executeFlowMode(flowContext);
		}
		else {
			resultPayload = executeSimpleMode(inputParams, flowContext);
		}

		persistExecutionResult(toolContext, resultPayload);
		return objectMapper.writeValueAsString(resultPayload);
	}

	private Map<String, Object> executeFlowMode(FlowContext flowContext) {
		FlowDefinition flow = flowDefinitionConverter.parseFromExecutionPlan(toolMeta.getExecutionPlan(), toolMeta);
		if (!flowDefinitionConverter.validate(flow)) {
			Map<String, Object> payload = new LinkedHashMap<>();
			payload.put("success", false);
			payload.put("mode", "FLOW");
			payload.put("toolCode", toolMeta.getToolCode());
			payload.put("error", "Invalid execution flow definition");
			return payload;
		}

		FlowExecutionResult flowResult = dagFlowExecutor.execute(flow, flowContext);
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("success", flowResult.isSuccess());
		payload.put("mode", "FLOW");
		payload.put("toolCode", toolMeta.getToolCode());
		payload.put("finalOutputs", flowResult.getFinalOutputs());
		payload.put("stepStatuses", flowResult.getStepStatuses());
		payload.put("stepResults", flowResult.getStepResults());
		payload.put("durationMs", flowResult.getDurationMs());
		payload.put("error", flowResult.getErrorMessage());
		return payload;
	}

	private Map<String, Object> executeSimpleMode(Map<String, Object> inputParams, FlowContext flowContext) {
		StepConfig stepConfig = new StepConfig();
		stepConfig.setMethod(defaultIfBlank(toolMeta.getHttpMethod(), "POST"));
		stepConfig.setEndpoint(toolMeta.getApiEndpoint());
		stepConfig.setContentType(defaultIfBlank(toolMeta.getContentType(), "application/json"));
		stepConfig.setSuccessCondition("$.code == 0");

		Map<String, String> inputMapping = new LinkedHashMap<>();
		for (Map.Entry<String, Object> entry : inputParams.entrySet()) {
			inputMapping.put(entry.getKey(), "${" + entry.getKey() + "}");
		}
		stepConfig.setInputMapping(inputMapping);

		StepResult stepResult = httpStepExecutor.execute(stepConfig, flowContext);
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("success", stepResult.isSuccess());
		payload.put("mode", "SIMPLE");
		payload.put("toolCode", toolMeta.getToolCode());
		payload.put("outputs", stepResult.getOutputs());
		payload.put("error", stepResult.getErrorMessage());
		return payload;
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> extractInputParams(Map<String, Object> args) {
		if (args == null || args.isEmpty()) {
			return Collections.emptyMap();
		}

		Object nestedParams = args.get("params");
		if (nestedParams instanceof Map<?, ?> mapParams) {
			Map<String, Object> extracted = new LinkedHashMap<>();
			for (Map.Entry<?, ?> entry : mapParams.entrySet()) {
				if (entry.getKey() != null) {
					extracted.put(String.valueOf(entry.getKey()), entry.getValue());
				}
			}
			return extracted;
		}
		return new LinkedHashMap<>(args);
	}

	private FlowContext buildFlowContext(Map<String, Object> inputParams, @Nullable ToolContext toolContext) {
		FlowContext flowContext = new FlowContext(inputParams);

		// Priority: state > tool meta > LLM args (LLM often hallucinates identity values)
		String systemCode = firstNonBlank(
				readStateValue(toolContext, AssistantStateKeys.SYSTEM_CODE),
				toolMeta.getSystemCode(),
				valueAsString(inputParams.get("system_code")),
				valueAsString(inputParams.get(AssistantStateKeys.SYSTEM_CODE)));
		String assistantUid = firstNonBlank(
				readStateValue(toolContext, AssistantStateKeys.ASSISTANT_UID),
				valueAsString(inputParams.get("assistant_uid")),
				valueAsString(inputParams.get(AssistantStateKeys.ASSISTANT_UID)));
		String threadId = firstNonBlank(
				valueAsString(inputParams.get("thread_id")),
				readStateValue(toolContext, AssistantStateKeys.THREAD_ID),
				getThreadId(toolContext).orElse(null));

		flowContext.setSystemCode(systemCode);
		flowContext.setAssistantUid(assistantUid);
		flowContext.setThreadId(threadId);
		return flowContext;
	}

	@SuppressWarnings("unchecked")
	private String readStateValue(@Nullable ToolContext toolContext, String key) {
		if (toolContext == null || toolContext.getContext() == null || !StringUtils.hasText(key)) {
			return null;
		}
		Object stateObject = toolContext.getContext().get(ToolContextConstants.AGENT_STATE_CONTEXT_KEY);
		if (!(stateObject instanceof OverAllState overAllState)) {
			return null;
		}
		return overAllState.value(key, String.class).orElse(null);
	}

	@SuppressWarnings("unchecked")
	private void persistExecutionResult(@Nullable ToolContext toolContext, Map<String, Object> payload) {
		if (toolContext == null || toolContext.getContext() == null || payload == null) {
			return;
		}
		Object stateObject = toolContext.getContext().get(ToolContextConstants.AGENT_STATE_CONTEXT_KEY);
		if (!(stateObject instanceof OverAllState overAllState)) {
			return;
		}
		Map<String, Object> updates = new HashMap<>();
		updates.put(AssistantStateKeys.EXECUTION_RESULT, payload);
		updates.put(AssistantStateKeys.CONVERSATION_PHASE, payload.getOrDefault("success", Boolean.FALSE)
				.equals(Boolean.TRUE) ? "DONE" : "EXECUTING");
		overAllState.updateState(updates);
	}

	private static ToolDefinition buildToolDefinition(ObjectMapper objectMapper,
			ToolMeta toolMeta,
			String bridgeToolName) {
		return DefaultToolDefinition.builder()
				.name(bridgeToolName)
				.description(defaultIfBlank(toolMeta.getDescription(), "Capability bridge tool: " + bridgeToolName))
				.inputSchema(resolveInputSchema(objectMapper, toolMeta.getParameterSchema()))
				.build();
	}

	private static String resolveInputSchema(ObjectMapper objectMapper, String parameterSchema) {
		if (!StringUtils.hasText(parameterSchema)) {
			return "{\"type\":\"object\",\"properties\":{}}";
		}

		try {
			JsonNode root = objectMapper.readTree(parameterSchema);
			if (isJsonSchema(root)) {
				return parameterSchema;
			}
			if (root.has("slots") && root.get("slots").isArray()) {
				ObjectNode schema = objectMapper.createObjectNode();
				schema.put("type", "object");
				ObjectNode properties = schema.putObject("properties");
				ArrayNode required = schema.putArray("required");

				for (JsonNode slot : root.get("slots")) {
					String name = text(slot, "name");
					if (!StringUtils.hasText(name)) {
						continue;
					}
					ObjectNode property = properties.putObject(name);
					property.put("type", mapSlotType(text(slot, "type")));
					String description = firstNonBlank(text(slot, "title"), text(slot, "description"), text(slot, "ai_hint"));
					if (StringUtils.hasText(description)) {
						property.put("description", description);
					}

					JsonNode optionsNode = slot.get("options");
					if (optionsNode != null && optionsNode.isObject()) {
						JsonNode enumMapping = optionsNode.get("enum_mapping");
						if (enumMapping != null && enumMapping.isObject()) {
							ArrayNode enumValues = property.putArray("enum");
							enumMapping.fields().forEachRemaining(entry -> enumValues.add(entry.getValue()));
						}
					}

					if (slot.path("required").asBoolean(false)) {
						required.add(name);
					}
				}

				if (required.isEmpty()) {
					schema.remove("required");
				}
				return objectMapper.writeValueAsString(schema);
			}
		}
		catch (Exception ignored) {
			// Fallback to generic object schema.
		}
		return "{\"type\":\"object\",\"properties\":{}}";
	}

	private static boolean isJsonSchema(JsonNode root) {
		return root != null && "object".equals(root.path("type").asText()) && root.has("properties");
	}

	private static String mapSlotType(String slotType) {
		if (!StringUtils.hasText(slotType)) {
			return "string";
		}
		return switch (slotType.toLowerCase()) {
			case "integer", "int", "long" -> "integer";
			case "number", "float", "double", "decimal" -> "number";
			case "bool", "boolean" -> "boolean";
			case "array", "list" -> "array";
			case "object", "map" -> "object";
			default -> "string";
		};
	}

	private static CodeactToolMetadata buildMetadata(ToolMeta toolMeta, String bridgeToolName, String targetClassName) {
		String invocationTemplate = bridgeToolName + "(**kwargs) -> Dict[str, Any]";
		DefaultCodeactToolMetadata.Builder builder = DefaultCodeactToolMetadata.builder()
				.addSupportedLanguage(Language.PYTHON)
				.targetClassName(defaultIfBlank(targetClassName, "capability_tools"))
				.targetClassDescription("Capability bridge tools for enterprise systems")
				.codeInvocationTemplate(invocationTemplate)
				.returnDirect(false)
				.displayName(firstNonBlank(toolMeta.getToolName(), bridgeToolName))
				.alwaysAvailable(false);

		if (StringUtils.hasText(toolMeta.getToolCode())) {
			builder.addAlias(toolMeta.getToolCode());
		}
		return builder.build();
	}

	private static String text(JsonNode node, String key) {
		if (node == null || !node.has(key) || node.get(key).isNull()) {
			return null;
		}
		return node.get(key).asText();
	}

	private static String valueAsString(Object value) {
		return value != null ? String.valueOf(value) : null;
	}

	private static String firstNonBlank(String... values) {
		if (values == null) {
			return null;
		}
		for (String value : values) {
			if (StringUtils.hasText(value)) {
				return value;
			}
		}
		return null;
	}

	private static String defaultIfBlank(String value, String fallback) {
		return StringUtils.hasText(value) ? value : fallback;
	}
}
