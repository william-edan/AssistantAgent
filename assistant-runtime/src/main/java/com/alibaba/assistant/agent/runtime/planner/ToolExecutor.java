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
package com.alibaba.assistant.agent.runtime.planner;

import com.alibaba.assistant.agent.controlplane.toolregistry.ToolMeta;
import com.alibaba.assistant.agent.controlplane.toolregistry.ToolMetaService;
import com.alibaba.assistant.agent.execution.flow.DAGFlowExecutor;
import com.alibaba.assistant.agent.execution.flow.FlowContext;
import com.alibaba.assistant.agent.execution.flow.FlowDefinition;
import com.alibaba.assistant.agent.execution.flow.FlowDefinitionConverter;
import com.alibaba.assistant.agent.execution.flow.FlowExecutionResult;
import com.alibaba.assistant.agent.execution.model.StepConfig;
import com.alibaba.assistant.agent.execution.model.StepResult;
import com.alibaba.assistant.agent.execution.step.HttpStepExecutor;
import com.alibaba.assistant.agent.runtime.agent.AssistantStateKeys;
import com.alibaba.assistant.agent.runtime.compiler.RuntimeArtifact;
import com.alibaba.assistant.agent.runtime.execution.ArtifactRuntimeExecutor;
import com.alibaba.assistant.agent.runtime.registry.ArtifactPublicationLookupService;
import com.alibaba.assistant.agent.runtime.registry.LegacyCompatibilityLogHelper;
import com.alibaba.assistant.agent.runtime.registry.PublicationScopeResolver;
import com.alibaba.assistant.agent.runtime.registry.PublishedToolDescriptor;
import com.alibaba.assistant.agent.runtime.registry.ToolPublicationProvider;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallExecutionContext;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallHandler;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallRequest;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallResponse;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolInterceptor;
import com.alibaba.cloud.ai.graph.agent.tools.ToolContextConstants;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Executes a ToolMeta capability by toolCode and returns normalized outputs.
 *
 * <p>This executor reuses the runtime interception chain for governance and auditing.
 *
 * @author Assistant Agent Team
 * @since 1.0.0
 */
@Component
@Profile("migration")
public class ToolExecutor {

	private static final Logger logger = LoggerFactory.getLogger(ToolExecutor.class);

	private static final String DEFAULT_TENANT = "default";

	public static final String INTERNAL_DEPENDENCY_CALL_KEY = "_internal_dependency_call";

	private final ToolMetaService toolMetaService;

	private final FlowDefinitionConverter flowDefinitionConverter;

	private final DAGFlowExecutor dagFlowExecutor;

	private final HttpStepExecutor httpStepExecutor;

	private final ObjectMapper objectMapper;

	private final List<ToolInterceptor> toolInterceptors;

	private final ArtifactPublicationLookupService artifactPublicationLookupService;

	private final ArtifactRuntimeExecutor artifactRuntimeExecutor;

	private final PublicationScopeResolver publicationScopeResolver;

	public ToolExecutor(
			ToolMetaService toolMetaService,
			FlowDefinitionConverter flowDefinitionConverter,
			DAGFlowExecutor dagFlowExecutor,
			HttpStepExecutor httpStepExecutor,
			ObjectMapper objectMapper,
			List<ToolInterceptor> toolInterceptors) {
		this(
				toolMetaService,
				flowDefinitionConverter,
				dagFlowExecutor,
				httpStepExecutor,
				objectMapper,
				toolInterceptors,
				null,
				null,
				null);
	}

	@Autowired
	public ToolExecutor(
			ToolMetaService toolMetaService,
			FlowDefinitionConverter flowDefinitionConverter,
			DAGFlowExecutor dagFlowExecutor,
			HttpStepExecutor httpStepExecutor,
			ObjectMapper objectMapper,
			@Autowired(required = false) List<ToolInterceptor> toolInterceptors,
			@Autowired(required = false) @Nullable ArtifactPublicationLookupService artifactPublicationLookupService,
			@Autowired(required = false) @Nullable ArtifactRuntimeExecutor artifactRuntimeExecutor,
			@Autowired(required = false) @Nullable PublicationScopeResolver publicationScopeResolver) {
		this.toolMetaService = toolMetaService;
		this.flowDefinitionConverter = flowDefinitionConverter;
		this.dagFlowExecutor = dagFlowExecutor;
		this.httpStepExecutor = httpStepExecutor;
		this.objectMapper = objectMapper;
		this.toolInterceptors = toolInterceptors != null ? new ArrayList<>(toolInterceptors) : Collections.emptyList();
		this.artifactPublicationLookupService = artifactPublicationLookupService;
		this.artifactRuntimeExecutor = artifactRuntimeExecutor;
		this.publicationScopeResolver = publicationScopeResolver;
	}

	/**
	 * Execute capability by tool code.
	 *
	 * @param tenantId tenant id, defaults to "default"
	 * @param toolCode tool code
	 * @param arguments input args
	 * @param toolContext runtime tool context
	 * @return execution result
	 */
	public ExecutionResult execute(String tenantId, String toolCode, Map<String, Object> arguments, ToolContext toolContext) {
		if (!StringUtils.hasText(toolCode)) {
			return ExecutionResult.error(toolCode, "Dependency toolCode is blank", null);
		}

		String effectiveToolCode = toolCode.trim();
		ToolPublicationProvider.PublicationScope scope = resolvePublicationScope(toolContext);
		if (artifactPublicationLookupService != null) {
			Optional<PublishedToolDescriptor> descriptor = artifactPublicationLookupService
					.findPublishedArtifact(effectiveToolCode, toolContext);
			if (descriptor.isPresent()) {
				if (artifactRuntimeExecutor == null) {
					return ExecutionResult.error(effectiveToolCode,
							"Artifact dependency executor is unavailable for toolCode=" + effectiveToolCode,
							null);
				}
				return execute(descriptor.get(), arguments, toolContext);
			}
		}

		if (!allowsLegacyToolMetaFallback(scope)) {
			return ExecutionResult.error(effectiveToolCode,
					"Dependency tool not found or disabled in artifact-first scope: toolCode=" + effectiveToolCode,
					null);
		}

		String effectiveTenant = StringUtils.hasText(tenantId) ? tenantId : DEFAULT_TENANT;
		Optional<ToolMeta> toolMetaOptional = toolMetaService != null
				? toolMetaService.findLatestEnabledByToolCode(effectiveTenant, effectiveToolCode)
				: Optional.empty();
		if (toolMetaOptional.isEmpty()) {
			return ExecutionResult.error(effectiveToolCode,
					"Dependency tool not found or disabled: tenant=" + effectiveTenant + ", toolCode=" + effectiveToolCode,
					null);
		}
		LegacyCompatibilityLogHelper.logFallback(
				logger,
				"ToolExecutor#execute",
				"legacy ToolMeta",
				scope,
				effectiveTenant,
				effectiveToolCode);
		return execute(toolMetaOptional.get(), arguments, toolContext);
	}

	ExecutionResult execute(ToolMeta toolMeta, Map<String, Object> arguments, ToolContext toolContext) {
		if (toolMeta == null || !StringUtils.hasText(toolMeta.getToolCode())) {
			return ExecutionResult.error(null, "Dependency ToolMeta is invalid", null);
		}

		OverAllState state = extractState(toolContext);
		Object previousMatchedToolMeta = state != null
				? state.value(AssistantStateKeys.MATCHED_TOOL_META, Object.class).orElse(null)
				: null;

		try {
			if (state != null) {
				state.updateState(Map.of(AssistantStateKeys.MATCHED_TOOL_META, toolMeta));
			}
			ToolCallRequest request = buildRequest(toolMeta.getToolCode(), arguments, toolContext, state);
			ToolCallResponse response = invokeWithInterceptors(request, req -> invokeToolMeta(toolMeta, req));
			return toExecutionResult(toolMeta.getToolCode(), response);
		}
		catch (Exception e) {
			logger.error("ToolExecutor#execute - failed, toolCode={}, error={}", toolMeta.getToolCode(), e.getMessage(), e);
			return ExecutionResult.error(toolMeta.getToolCode(), e.getMessage(), null);
		}
		finally {
			restoreMatchedToolMeta(state, previousMatchedToolMeta);
		}
	}

	ExecutionResult execute(PublishedToolDescriptor descriptor, Map<String, Object> arguments, ToolContext toolContext) {
		if (descriptor == null || descriptor.artifact() == null || !StringUtils.hasText(descriptor.artifact().getArtifactCode())) {
			return ExecutionResult.error(null, "Dependency published artifact is invalid", null);
		}
		if (artifactRuntimeExecutor == null) {
			return ExecutionResult.error(descriptor.artifact().getArtifactCode(),
					"Artifact dependency executor is unavailable", null);
		}

		String toolCode = descriptor.artifact().getArtifactCode();
		OverAllState state = extractState(toolContext);
		Object previousMatchedToolMeta = state != null
				? state.value(AssistantStateKeys.MATCHED_TOOL_META, Object.class).orElse(null)
				: null;

		try {
			if (state != null) {
				state.updateState(Map.of(AssistantStateKeys.MATCHED_TOOL_META, buildMatchedToolMetaSnapshot(descriptor)));
			}
			ToolCallRequest request = buildRequest(toolCode, arguments, toolContext, state);
			ToolCallResponse response = invokeWithInterceptors(request, req -> invokePublishedArtifact(descriptor, req));
			return toExecutionResult(toolCode, response);
		}
		catch (Exception e) {
			logger.error("ToolExecutor#execute - failed, toolCode={}, error={}", toolCode, e.getMessage(), e);
			return ExecutionResult.error(toolCode, e.getMessage(), null);
		}
		finally {
			restoreMatchedToolMeta(state, previousMatchedToolMeta);
		}
	}

	private ToolCallRequest buildRequest(
			String toolName,
			Map<String, Object> arguments,
			ToolContext toolContext,
			OverAllState state) throws Exception {
		Map<String, Object> contextMap = new LinkedHashMap<>();
		if (toolContext != null && toolContext.getContext() != null) {
			contextMap.putAll(toolContext.getContext());
		}
		if (state != null) {
			contextMap.putIfAbsent(ToolContextConstants.AGENT_STATE_CONTEXT_KEY, state);
		}
		contextMap.put(INTERNAL_DEPENDENCY_CALL_KEY, true);

		ToolCallRequest.Builder builder = ToolCallRequest.builder()
				.toolName(toolName)
				.toolCallId(UUID.randomUUID().toString())
				.arguments(objectMapper.writeValueAsString(arguments != null ? arguments : Collections.emptyMap()))
				.context(contextMap);

		if (state != null) {
			RunnableConfig.Builder configBuilder = RunnableConfig.builder();
			String threadId = readStateValue(state, AssistantStateKeys.THREAD_ID);
			if (StringUtils.hasText(threadId)) {
				configBuilder.threadId(threadId);
			}
			builder.executionContext(new ToolCallExecutionContext(configBuilder.build(), state));
		}
		return builder.build();
	}

	private ToolCallResponse invokeWithInterceptors(ToolCallRequest request, ToolCallHandler terminalHandler) {
		ToolCallHandler chain = terminalHandler;
		for (int i = toolInterceptors.size() - 1; i >= 0; i--) {
			ToolInterceptor interceptor = toolInterceptors.get(i);
			ToolCallHandler next = chain;
			chain = current -> interceptor.interceptToolCall(current, next);
		}
		return chain.call(request);
	}

	private ToolCallResponse invokeToolMeta(ToolMeta toolMeta, ToolCallRequest request) {
		try {
			Map<String, Object> args = parseArguments(request.getArguments());
			ToolContext context = new ToolContext(request.getContext() != null ? request.getContext() : Collections.emptyMap());
			FlowContext flowContext = buildFlowContext(toolMeta, args, context);

			Map<String, Object> payload = StringUtils.hasText(toolMeta.getExecutionPlan())
					? executeFlowMode(toolMeta, flowContext)
					: executeSimpleMode(toolMeta, args, flowContext);
			return ToolCallResponse.of(request.getToolName(), request.getToolCallId(), objectMapper.writeValueAsString(payload));
		}
		catch (Exception e) {
			String message = "Dependency execution failed: " + e.getMessage();
			return ToolCallResponse.error(request.getToolName(), request.getToolCallId(), message);
		}
	}

	private ToolCallResponse invokePublishedArtifact(PublishedToolDescriptor descriptor, ToolCallRequest request) {
		try {
			Map<String, Object> args = parseArguments(request.getArguments());
			ToolContext context = new ToolContext(request.getContext() != null ? request.getContext() : Collections.emptyMap());
			Map<String, Object> payload = artifactRuntimeExecutor.execute(descriptor, args, context);
			return ToolCallResponse.of(request.getToolName(), request.getToolCallId(), objectMapper.writeValueAsString(payload));
		}
		catch (Exception e) {
			String message = "Dependency execution failed: " + e.getMessage();
			return ToolCallResponse.error(request.getToolName(), request.getToolCallId(), message);
		}
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> parseArguments(String arguments) {
		if (!StringUtils.hasText(arguments)) {
			return Collections.emptyMap();
		}
		try {
			Map<String, Object> parsed = objectMapper.readValue(arguments, Map.class);
			return parsed != null ? parsed : Collections.emptyMap();
		}
		catch (Exception e) {
			logger.warn("ToolExecutor#parseArguments - parse failed, error={}", e.getMessage());
			return Collections.emptyMap();
		}
	}

	private FlowContext buildFlowContext(ToolMeta toolMeta, Map<String, Object> inputParams, ToolContext toolContext) {
		Map<String, Object> safeParams = inputParams != null ? new LinkedHashMap<>(inputParams) : new LinkedHashMap<>();
		FlowContext flowContext = new FlowContext(safeParams);

		String systemCode = firstNonBlank(
				readStateValue(toolContext, AssistantStateKeys.SYSTEM_CODE),
				readStateValue(toolContext, "systemCode"),
				toolMeta.getSystemCode(),
				asText(safeParams.get("systemCode")),
				asText(safeParams.get("system_code")),
				asText(safeParams.get(AssistantStateKeys.SYSTEM_CODE)));
		String assistantUid = firstNonBlank(
				readStateValue(toolContext, AssistantStateKeys.ASSISTANT_UID),
				readStateValue(toolContext, "assistantUid"),
				asText(safeParams.get("assistantUid")),
				asText(safeParams.get("assistant_uid")),
				asText(safeParams.get(AssistantStateKeys.ASSISTANT_UID)));
		String threadId = firstNonBlank(
				asText(safeParams.get("thread_id")),
				readStateValue(toolContext, AssistantStateKeys.THREAD_ID));

		flowContext.setSystemCode(systemCode);
		flowContext.setAssistantUid(assistantUid);
		flowContext.setThreadId(threadId);
		return flowContext;
	}

	private Map<String, Object> executeFlowMode(ToolMeta toolMeta, FlowContext flowContext) {
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

	private Map<String, Object> executeSimpleMode(ToolMeta toolMeta, Map<String, Object> inputParams, FlowContext flowContext) {
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

	private ExecutionResult toExecutionResult(String toolCode, ToolCallResponse response) {
		if (response == null) {
			return ExecutionResult.error(toolCode, "Dependency response is null", null);
		}
		if (response.isError()) {
			String message = StringUtils.hasText(response.getStatus())
					? response.getStatus()
					: "Dependency execution failed";
			return ExecutionResult.error(toolCode, message, null);
		}

		Map<String, Object> payload = parsePayload(response.getResult());
		if (Boolean.FALSE.equals(payload.get("success"))) {
			return ExecutionResult.error(toolCode, asText(payload.get("error")), payload);
		}
		Map<String, Object> outputFields = extractOutputFields(payload);
		return ExecutionResult.success(toolCode, payload, outputFields);
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> parsePayload(String rawPayload) {
		if (!StringUtils.hasText(rawPayload)) {
			return new LinkedHashMap<>();
		}
		try {
			Map<String, Object> parsed = objectMapper.readValue(rawPayload, Map.class);
			return parsed != null ? new LinkedHashMap<>(parsed) : new LinkedHashMap<>();
		}
		catch (Exception e) {
			Map<String, Object> wrapped = new LinkedHashMap<>();
			wrapped.put("raw", rawPayload);
			return wrapped;
		}
	}

	private Map<String, Object> extractOutputFields(Map<String, Object> payload) {
		Map<String, Object> finalOutputs = toMap(payload.get("finalOutputs"));
		if (!finalOutputs.isEmpty()) {
			return finalOutputs;
		}

		Map<String, Object> outputs = toMap(payload.get("outputs"));
		if (!outputs.isEmpty()) {
			return outputs;
		}

		Map<String, Object> data = toMap(payload.get("data"));
		if (!data.isEmpty()) {
			return data;
		}

		return new LinkedHashMap<>(payload);
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> toMap(Object value) {
		if (value == null) {
			return Collections.emptyMap();
		}
		if (value instanceof Map<?, ?> map) {
			Map<String, Object> result = new LinkedHashMap<>();
			for (Map.Entry<?, ?> entry : map.entrySet()) {
				if (entry.getKey() != null) {
					result.put(String.valueOf(entry.getKey()), entry.getValue());
				}
			}
			return result;
		}
		try {
			Map<String, Object> converted = objectMapper.convertValue(value, Map.class);
			return converted != null ? converted : Collections.emptyMap();
		}
		catch (Exception e) {
			return Collections.emptyMap();
		}
	}

	private boolean allowsLegacyToolMetaFallback(@Nullable ToolContext toolContext) {
		return allowsLegacyToolMetaFallback(resolvePublicationScope(toolContext));
	}

	private boolean allowsLegacyToolMetaFallback(@Nullable ToolPublicationProvider.PublicationScope scope) {
		if (scope == null) {
			return true;
		}
		if (containsIgnoreCase(scope.requestedSourceIds(), "legacy-bridge")) {
			return true;
		}
		if (containsIgnoreCase(scope.blockedSourceIds(), "legacy-bridge")) {
			return false;
		}
		return scope.sourceSelectionMode() != ToolPublicationProvider.SourceSelectionMode.EXCLUSIVE;
	}

	@Nullable
	private ToolPublicationProvider.PublicationScope resolvePublicationScope(@Nullable ToolContext toolContext) {
		if (publicationScopeResolver == null || toolContext == null) {
			return null;
		}
		return publicationScopeResolver.resolve(toolContext);
	}

	private boolean containsIgnoreCase(List<String> source, String target) {
		if (source == null || source.isEmpty() || !StringUtils.hasText(target)) {
			return false;
		}
		for (String item : source) {
			if (item != null && target.equalsIgnoreCase(item.trim())) {
				return true;
			}
		}
		return false;
	}

	private Map<String, Object> buildMatchedToolMetaSnapshot(PublishedToolDescriptor descriptor) {
		Map<String, Object> snapshot = new LinkedHashMap<>();
		RuntimeArtifact artifact = descriptor.artifact();
		String publishedRiskLevel = resolvePublishedRiskLevel(artifact);
		snapshot.put("toolCode", artifact.getArtifactCode());
		snapshot.put("toolName", firstNonBlank(descriptor.displayName(), artifact.getDisplayName(), artifact.getArtifactCode()));
		snapshot.put("description", firstNonBlank(descriptor.displayName(), artifact.getDisplayName(), artifact.getArtifactCode()));
		snapshot.put("systemCode", descriptor.executionSystemCode());
		snapshot.put("riskLevel", publishedRiskLevel);
		snapshot.put("requiresConfirm", requiresPublishedConfirmation(artifact, publishedRiskLevel));
		return snapshot;
	}

	private String resolvePublishedRiskLevel(RuntimeArtifact artifact) {
		if (artifact == null || artifact.getActions().isEmpty()) {
			return null;
		}
		String resolved = null;
		int resolvedPriority = -1;
		for (RuntimeArtifact.ActionBinding actionBinding : artifact.getActions().values()) {
			String candidate = normalizeRiskLevel(actionBinding != null ? actionBinding.riskLevel() : null);
			int candidatePriority = riskPriority(candidate);
			if (candidatePriority > resolvedPriority) {
				resolved = candidate;
				resolvedPriority = candidatePriority;
			}
		}
		return resolved;
	}

	private boolean requiresPublishedConfirmation(RuntimeArtifact artifact, String publishedRiskLevel) {
		if (artifact == null) {
			return false;
		}
		if (artifact.getInteraction() != null
				&& StringUtils.hasText(artifact.getInteraction().confirmationPolicyJson())) {
			return true;
		}
		for (RuntimeArtifact.ActionBinding actionBinding : artifact.getActions().values()) {
			if (actionBinding != null && actionBinding.approvalPolicyId() != null) {
				return true;
			}
		}
		return riskPriority(publishedRiskLevel) >= riskPriority("MEDIUM");
	}

	private String normalizeRiskLevel(String riskLevel) {
		if (!StringUtils.hasText(riskLevel)) {
			return null;
		}
		return riskLevel.trim().toUpperCase(java.util.Locale.ROOT);
	}

	private int riskPriority(String riskLevel) {
		String normalized = normalizeRiskLevel(riskLevel);
		if (normalized == null) {
			return -1;
		}
		return switch (normalized) {
			case "LOW" -> 0;
			case "MEDIUM" -> 1;
			case "HIGH" -> 2;
			case "CRITICAL" -> 3;
			default -> -1;
		};
	}

	private OverAllState extractState(ToolContext toolContext) {
		if (toolContext == null || toolContext.getContext() == null) {
			return null;
		}
		Object stateObject = toolContext.getContext().get(ToolContextConstants.AGENT_STATE_CONTEXT_KEY);
		return stateObject instanceof OverAllState ? (OverAllState) stateObject : null;
	}

	private void restoreMatchedToolMeta(OverAllState state, Object previousMatchedToolMeta) {
		if (state == null) {
			return;
		}
		Map<String, Object> updates = new LinkedHashMap<>();
		updates.put(AssistantStateKeys.MATCHED_TOOL_META, previousMatchedToolMeta);
		state.updateState(updates);
	}

	private String readStateValue(ToolContext toolContext, String key) {
		if (toolContext == null || toolContext.getContext() == null || !StringUtils.hasText(key)) {
			return null;
		}
		Object stateObject = toolContext.getContext().get(ToolContextConstants.AGENT_STATE_CONTEXT_KEY);
		if (!(stateObject instanceof OverAllState overAllState)) {
			return null;
		}
		return overAllState.value(key, String.class).orElse(null);
	}

	private String readStateValue(OverAllState state, String key) {
		if (state == null || !StringUtils.hasText(key)) {
			return null;
		}
		return state.value(key, String.class).orElse(null);
	}

	private String asText(Object value) {
		if (value == null) {
			return null;
		}
		String text = String.valueOf(value).trim();
		return StringUtils.hasText(text) ? text : null;
	}

	private String firstNonBlank(String... values) {
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

	private String defaultIfBlank(String value, String fallback) {
		return StringUtils.hasText(value) ? value : fallback;
	}

	/**
	 * Dependency tool execution result.
	 */
	public record ExecutionResult(
			boolean success,
			String toolCode,
			Map<String, Object> payload,
			Map<String, Object> outputFields,
			String errorMessage) {

		public static ExecutionResult success(String toolCode, Map<String, Object> payload, Map<String, Object> outputFields) {
			return new ExecutionResult(
					true,
					toolCode,
					payload != null ? new LinkedHashMap<>(payload) : Collections.emptyMap(),
					outputFields != null ? new LinkedHashMap<>(outputFields) : Collections.emptyMap(),
					null);
		}

		public static ExecutionResult error(String toolCode, String errorMessage, Map<String, Object> payload) {
			String message = StringUtils.hasText(errorMessage) ? errorMessage : "Dependency execution failed";
			return new ExecutionResult(
					false,
					toolCode,
					payload != null ? new LinkedHashMap<>(payload) : Collections.emptyMap(),
					Collections.emptyMap(),
					message);
		}
	}

}
