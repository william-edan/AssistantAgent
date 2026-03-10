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
package com.alibaba.assistant.agent.controlplane.compiler;

import com.alibaba.assistant.agent.controlplane.action.ActionSpec;
import com.alibaba.assistant.agent.controlplane.action.ActionSpecService;
import com.alibaba.assistant.agent.controlplane.interaction.InteractionSpec;
import com.alibaba.assistant.agent.controlplane.interaction.InteractionSpecService;
import com.alibaba.assistant.agent.controlplane.space.PlatformSpace;
import com.alibaba.assistant.agent.controlplane.space.PlatformSpaceService;
import com.alibaba.assistant.agent.controlplane.workflow.WorkflowSpec;
import com.alibaba.assistant.agent.controlplane.workflow.WorkflowSpecService;
import com.alibaba.assistant.agent.controlplane.workflow.WorkflowStep;
import com.alibaba.assistant.agent.controlplane.workflow.WorkflowStepService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Compiler bridge that decomposes legacy capability rows into the new control-plane artifacts.
 */
@Service
@Profile("migration")
public class LegacyCapabilityCompiler {

	private static final Logger logger = LoggerFactory.getLogger(LegacyCapabilityCompiler.class);

	private static final List<String> LEGACY_TABLE_CANDIDATES = List.of(
			"assistant_capability_registry",
			"capability_registry");

	private final JdbcTemplate jdbcTemplate;
	private final PlatformSpaceService platformSpaceService;
	private final InteractionSpecService interactionSpecService;
	private final ActionSpecService actionSpecService;
	private final WorkflowSpecService workflowSpecService;
	private final WorkflowStepService workflowStepService;
	private final ObjectMapper objectMapper;

	public LegacyCapabilityCompiler(
			JdbcTemplate jdbcTemplate,
			PlatformSpaceService platformSpaceService,
			InteractionSpecService interactionSpecService,
			ActionSpecService actionSpecService,
			WorkflowSpecService workflowSpecService,
			WorkflowStepService workflowStepService,
			ObjectMapper objectMapper) {
		this.jdbcTemplate = jdbcTemplate;
		this.platformSpaceService = platformSpaceService;
		this.interactionSpecService = interactionSpecService;
		this.actionSpecService = actionSpecService;
		this.workflowSpecService = workflowSpecService;
		this.workflowStepService = workflowStepService;
		this.objectMapper = objectMapper;
	}

	@Transactional
	public CompilationResult compileAll(String environment) {
		LoadedRows loadedRows = loadLegacyRows();
		if (loadedRows.rows().isEmpty()) {
			return new CompilationResult(0, 0, 0, 0, loadedRows.tableName());
		}

		int scanned = 0;
		int inserted = 0;
		int updated = 0;
		int skipped = 0;
		String targetEnvironment = defaultIfBlank(environment, "prod");

		for (Map<String, Object> row : loadedRows.rows()) {
			scanned++;
			String tenantId = defaultIfBlank(readString(row, "tenant_id", "tenantId"), "default");
			Optional<PlatformSpace> space = platformSpaceService.findActiveByCode(tenantId, targetEnvironment);
			if (space.isEmpty()) {
				skipped++;
				continue;
			}

			CompiledLegacyCapability compiled = compile(row, space.get().getId());
			if (compiled == null) {
				skipped++;
				continue;
			}

			boolean hasUpdated = false;
			hasUpdated |= upsertInteraction(compiled.interactionSpec());
			for (ActionSpec actionSpec : compiled.actionSpecs()) {
				hasUpdated |= upsertAction(actionSpec);
			}

			if (compiled.workflowSpec() != null) {
				WorkflowSpec workflowSpec = compiled.workflowSpec();
				hasUpdated |= upsertWorkflow(workflowSpec);
				Long workflowId = workflowSpec.getId();
				for (WorkflowStep workflowStep : compiled.workflowSteps()) {
					workflowStep.setWorkflowId(workflowId);
					hasUpdated |= upsertWorkflowStep(workflowStep);
				}
			}

			if (hasUpdated) {
				updated++;
			}
			else {
				inserted++;
			}
		}

		CompilationResult result = new CompilationResult(scanned, inserted, updated, skipped, loadedRows.tableName());
		logger.info("LegacyCapabilityCompiler#compileAll - sourceTable={}, scanned={}, inserted={}, updated={}, skipped={}",
				result.sourceTable(), result.scanned(), result.inserted(), result.updated(), result.skipped());
		return result;
	}

	public CompiledLegacyCapability compile(Map<String, Object> legacyRow, Long spaceId) {
		if (legacyRow == null || legacyRow.isEmpty() || spaceId == null) {
			return null;
		}

		String systemCode = readString(legacyRow, "system_code", "systemCode");
		String capabilityCode = readString(legacyRow, "capability_code", "capabilityCode");
		if (!StringUtils.hasText(systemCode) || !StringUtils.hasText(capabilityCode)) {
			return null;
		}

		String baseCode = buildBaseCode(systemCode, capabilityCode);
		int version = readInteger(legacyRow, 1, "version");
		String status = normalizeDefinitionStatus(readString(legacyRow, "status"));

		InteractionSpec interactionSpec = buildInteractionSpec(legacyRow, spaceId, baseCode, version, status);
		String flowStepsJson = readString(legacyRow, "flow_steps", "flowSteps");
		if (StringUtils.hasText(flowStepsJson) && "FLOW".equalsIgnoreCase(readString(legacyRow, "capability_mode", "capabilityMode"))) {
			return compileFlowCapability(legacyRow, spaceId, baseCode, version, status, interactionSpec, flowStepsJson);
		}

		ActionSpec actionSpec = buildRootAction(legacyRow, spaceId, baseCode, version, status);
		return new CompiledLegacyCapability(baseCode, interactionSpec, List.of(actionSpec), null, List.of());
	}

	private CompiledLegacyCapability compileFlowCapability(
			Map<String, Object> legacyRow,
			Long spaceId,
			String baseCode,
			int version,
			String status,
			InteractionSpec interactionSpec,
			String flowStepsJson) {
		Map<String, Object> flowRoot = readJsonMap(flowStepsJson);
		Map<String, Object> steps = castMap(flowRoot.get("steps"));
		if (steps.isEmpty()) {
			ActionSpec fallbackAction = buildRootAction(legacyRow, spaceId, baseCode, version, status);
			return new CompiledLegacyCapability(baseCode, interactionSpec, List.of(fallbackAction), null, List.of());
		}

		WorkflowSpec workflowSpec = new WorkflowSpec();
		workflowSpec.setSpaceId(spaceId);
		workflowSpec.setWorkflowCode(baseCode);
		workflowSpec.setDisplayName(defaultIfBlank(readString(legacyRow, "capability_name", "capabilityName"), baseCode));
		workflowSpec.setRiskAggregationPolicy("max_step_risk");
		workflowSpec.setApprovalAggregationPolicy("strictest_step_policy");
		workflowSpec.setStatus(status);
		workflowSpec.setVersion(version);

		List<ActionSpec> actionSpecs = new ArrayList<>();
		List<WorkflowStep> workflowSteps = new ArrayList<>();
		int order = 0;
		for (Map.Entry<String, Object> entry : steps.entrySet()) {
			String stepId = entry.getKey();
			Map<String, Object> stepMap = castMap(entry.getValue());
			Map<String, Object> configMap = castMap(stepMap.get("config"));

			WorkflowStep workflowStep = new WorkflowStep();
			workflowStep.setStepId(stepId);
			workflowStep.setStepName(defaultIfBlank(readString(stepMap, "name"), stepId));
			workflowStep.setStepType(defaultIfBlank(readString(stepMap, "type"), "HTTP").toUpperCase(Locale.ROOT));
			workflowStep.setInputMappingJson(writeJson(configMap.get("inputMapping")));
			workflowStep.setOutputMappingJson(writeJson(configMap.get("outputMapping")));
			workflowStep.setDependsOnJson(writeJson(stepMap.get("dependsOn")));
			workflowStep.setConditionJson(writeJson(stepMap.get("condition")));
			workflowStep.setJoinPolicyJson(writeJson(stepMap.get("joinPolicy")));
			workflowStep.setRetryPolicyJson(writeJson(stepMap.get("retryPolicy")));
			workflowStep.setTimeoutPolicyJson(writeJson(stepMap.get("timeoutPolicy")));
			workflowStep.setApprovalGateJson(writeJson(stepMap.get("approvalGate")));
			workflowStep.setResumePolicyJson(writeJson(stepMap.get("resumePolicy")));
			workflowStep.setCompensationTargetRef(readString(stepMap, "compensationTargetRef"));
			workflowStep.setStepOrder(order++);
			workflowStep.setStatus(STATUS_ENABLED);

			if ("HTTP".equalsIgnoreCase(workflowStep.getStepType())) {
				String actionCode = baseCode + "." + stepId;
				workflowStep.setTargetRef(actionCode);
				actionSpecs.add(buildStepAction(legacyRow, spaceId, actionCode, version, status, configMap));
			}

			workflowSteps.add(workflowStep);
		}

		return new CompiledLegacyCapability(baseCode, interactionSpec, actionSpecs, workflowSpec, workflowSteps);
	}

	private InteractionSpec buildInteractionSpec(
			Map<String, Object> legacyRow, Long spaceId, String baseCode, int version, String status) {
		InteractionSpec interactionSpec = new InteractionSpec();
		interactionSpec.setSpaceId(spaceId);
		interactionSpec.setInteractionCode(baseCode + ".interaction");
		interactionSpec.setSlotSchemaJson(firstNonBlank(
				readString(legacyRow, "slot_schema", "slotSchema"),
				readString(legacyRow, "request_schema", "requestSchema")));
		String behaviorStrategy = readString(legacyRow, "behavior_strategy", "behaviorStrategy");
		interactionSpec.setAskStrategyJson(behaviorStrategy);
		interactionSpec.setConfirmationPolicyJson(behaviorStrategy);
		interactionSpec.setStatus(status);
		interactionSpec.setVersion(version);
		return interactionSpec;
	}

	private ActionSpec buildRootAction(
			Map<String, Object> legacyRow, Long spaceId, String baseCode, int version, String status) {
		ActionSpec actionSpec = baseAction(legacyRow, spaceId, baseCode, version, status);
		actionSpec.setOperationBindingJson(buildRootOperationBindingJson(legacyRow));
		return actionSpec;
	}

	private ActionSpec buildStepAction(
			Map<String, Object> legacyRow,
			Long spaceId,
			String actionCode,
			int version,
			String status,
			Map<String, Object> configMap) {
		ActionSpec actionSpec = baseAction(legacyRow, spaceId, actionCode, version, status);
		actionSpec.setOperationBindingJson(buildStepOperationBindingJson(legacyRow, configMap));
		return actionSpec;
	}

	private ActionSpec baseAction(
			Map<String, Object> legacyRow, Long spaceId, String actionCode, int version, String status) {
		ActionSpec actionSpec = new ActionSpec();
		actionSpec.setSpaceId(spaceId);
		actionSpec.setActionCode(actionCode);
		actionSpec.setInputSchemaJson(readString(legacyRow, "request_schema", "requestSchema"));
		actionSpec.setOutputSchemaJson(readString(legacyRow, "response_config", "responseConfig"));
		actionSpec.setRiskLevel(normalizeRiskLevel(readString(legacyRow, "risk_level", "riskLevel")));
		actionSpec.setSideEffectLevel(normalizeSideEffect(readString(legacyRow, "side_effect", "sideEffect")));
		actionSpec.setStatus(status);
		actionSpec.setVersion(version);
		return actionSpec;
	}

	private boolean upsertInteraction(InteractionSpec candidate) {
		InteractionSpec existing = findExistingInteraction(candidate);
		if (existing == null) {
			interactionSpecService.save(candidate);
			return false;
		}
		mergeInteraction(existing, candidate);
		interactionSpecService.updateById(existing);
		candidate.setId(existing.getId());
		return true;
	}

	private boolean upsertAction(ActionSpec candidate) {
		ActionSpec existing = findExistingAction(candidate);
		if (existing == null) {
			actionSpecService.save(candidate);
			return false;
		}
		mergeAction(existing, candidate);
		actionSpecService.updateById(existing);
		candidate.setId(existing.getId());
		return true;
	}

	private boolean upsertWorkflow(WorkflowSpec candidate) {
		WorkflowSpec existing = findExistingWorkflow(candidate);
		if (existing == null) {
			workflowSpecService.save(candidate);
			return false;
		}
		mergeWorkflow(existing, candidate);
		workflowSpecService.updateById(existing);
		candidate.setId(existing.getId());
		return true;
	}

	private boolean upsertWorkflowStep(WorkflowStep candidate) {
		WorkflowStep existing = findExistingWorkflowStep(candidate);
		if (existing == null) {
			workflowStepService.save(candidate);
			return false;
		}
		mergeWorkflowStep(existing, candidate);
		workflowStepService.updateById(existing);
		candidate.setId(existing.getId());
		return true;
	}

	private InteractionSpec findExistingInteraction(InteractionSpec candidate) {
		LambdaQueryWrapper<InteractionSpec> query = new LambdaQueryWrapper<>();
		query.eq(InteractionSpec::getSpaceId, candidate.getSpaceId());
		query.eq(InteractionSpec::getInteractionCode, candidate.getInteractionCode());
		query.eq(InteractionSpec::getVersion, candidate.getVersion());
		return interactionSpecService.getOne(query, false);
	}

	private ActionSpec findExistingAction(ActionSpec candidate) {
		LambdaQueryWrapper<ActionSpec> query = new LambdaQueryWrapper<>();
		query.eq(ActionSpec::getSpaceId, candidate.getSpaceId());
		query.eq(ActionSpec::getActionCode, candidate.getActionCode());
		query.eq(ActionSpec::getVersion, candidate.getVersion());
		return actionSpecService.getOne(query, false);
	}

	private WorkflowSpec findExistingWorkflow(WorkflowSpec candidate) {
		LambdaQueryWrapper<WorkflowSpec> query = new LambdaQueryWrapper<>();
		query.eq(WorkflowSpec::getSpaceId, candidate.getSpaceId());
		query.eq(WorkflowSpec::getWorkflowCode, candidate.getWorkflowCode());
		query.eq(WorkflowSpec::getVersion, candidate.getVersion());
		return workflowSpecService.getOne(query, false);
	}

	private WorkflowStep findExistingWorkflowStep(WorkflowStep candidate) {
		LambdaQueryWrapper<WorkflowStep> query = new LambdaQueryWrapper<>();
		query.eq(WorkflowStep::getWorkflowId, candidate.getWorkflowId());
		query.eq(WorkflowStep::getStepId, candidate.getStepId());
		return workflowStepService.getOne(query, false);
	}

	private void mergeInteraction(InteractionSpec existing, InteractionSpec incoming) {
		existing.setSlotSchemaJson(incoming.getSlotSchemaJson());
		existing.setAskStrategyJson(incoming.getAskStrategyJson());
		existing.setConfirmationPolicyJson(incoming.getConfirmationPolicyJson());
		existing.setStatus(incoming.getStatus());
	}

	private void mergeAction(ActionSpec existing, ActionSpec incoming) {
		existing.setOperationBindingJson(incoming.getOperationBindingJson());
		existing.setInputSchemaJson(incoming.getInputSchemaJson());
		existing.setOutputSchemaJson(incoming.getOutputSchemaJson());
		existing.setRiskLevel(incoming.getRiskLevel());
		existing.setSideEffectLevel(incoming.getSideEffectLevel());
		existing.setStatus(incoming.getStatus());
	}

	private void mergeWorkflow(WorkflowSpec existing, WorkflowSpec incoming) {
		existing.setDisplayName(incoming.getDisplayName());
		existing.setRiskAggregationPolicy(incoming.getRiskAggregationPolicy());
		existing.setApprovalAggregationPolicy(incoming.getApprovalAggregationPolicy());
		existing.setFailurePolicyJson(incoming.getFailurePolicyJson());
		existing.setAuditPolicyJson(incoming.getAuditPolicyJson());
		existing.setStatus(incoming.getStatus());
	}

	private void mergeWorkflowStep(WorkflowStep existing, WorkflowStep incoming) {
		existing.setStepName(incoming.getStepName());
		existing.setStepType(incoming.getStepType());
		existing.setTargetRef(incoming.getTargetRef());
		existing.setInputMappingJson(incoming.getInputMappingJson());
		existing.setOutputMappingJson(incoming.getOutputMappingJson());
		existing.setDependsOnJson(incoming.getDependsOnJson());
		existing.setConditionJson(incoming.getConditionJson());
		existing.setJoinPolicyJson(incoming.getJoinPolicyJson());
		existing.setRetryPolicyJson(incoming.getRetryPolicyJson());
		existing.setTimeoutPolicyJson(incoming.getTimeoutPolicyJson());
		existing.setApprovalGateJson(incoming.getApprovalGateJson());
		existing.setCompensationTargetRef(incoming.getCompensationTargetRef());
		existing.setResumePolicyJson(incoming.getResumePolicyJson());
		existing.setStepOrder(incoming.getStepOrder());
		existing.setStatus(incoming.getStatus());
	}

	private String buildRootOperationBindingJson(Map<String, Object> legacyRow) {
		Map<String, Object> binding = new LinkedHashMap<>();
		binding.put("bindingType", "legacy_http_root");
		binding.put("systemCode", readString(legacyRow, "system_code", "systemCode"));
		binding.put("endpoint", readString(legacyRow, "api_endpoint", "apiEndpoint"));
		binding.put("method", normalizeHttpMethod(readString(legacyRow, "http_method", "httpMethod")));
		binding.put("contentType", defaultIfBlank(readString(legacyRow, "content_type", "contentType"), "application/json"));
		binding.put("requestSchema", readString(legacyRow, "request_schema", "requestSchema"));
		binding.put("responseConfig", readString(legacyRow, "response_config", "responseConfig"));
		binding.put("requiresAuth", readBoolean(legacyRow, true, "requires_auth", "requiresAuth"));
		return writeJson(binding);
	}

	private String buildStepOperationBindingJson(Map<String, Object> legacyRow, Map<String, Object> configMap) {
		Map<String, Object> binding = new LinkedHashMap<>();
		binding.put("bindingType", "legacy_http_step");
		binding.put("systemCode", readString(legacyRow, "system_code", "systemCode"));
		binding.put("endpoint", readString(configMap, "endpoint"));
		binding.put("method", normalizeHttpMethod(readString(configMap, "method")));
		binding.put("contentType", defaultIfBlank(readString(configMap, "contentType"), "application/json"));
		binding.put("inputMapping", configMap.get("inputMapping"));
		binding.put("outputMapping", configMap.get("outputMapping"));
		binding.put("successCondition", readString(configMap, "successCondition"));
		binding.put("requiresAuth", readBoolean(legacyRow, true, "requires_auth", "requiresAuth"));
		return writeJson(binding);
	}

	private LoadedRows loadLegacyRows() {
		for (String tableName : LEGACY_TABLE_CANDIDATES) {
			try {
				List<Map<String, Object>> rows = jdbcTemplate.queryForList("SELECT * FROM " + tableName + " ORDER BY id ASC");
				return new LoadedRows(tableName, rows);
			}
			catch (DataAccessException e) {
				logger.debug("LegacyCapabilityCompiler#loadLegacyRows - table unavailable, table={}, error={}", tableName,
						e.getMessage());
			}
		}
		return new LoadedRows("none", List.of());
	}

	private String buildBaseCode(String systemCode, String capabilityCode) {
		if (capabilityCode.contains(".")) {
			return capabilityCode.trim();
		}
		return systemCode.trim() + "." + capabilityCode.trim();
	}

	private Map<String, Object> readJsonMap(String json) {
		if (!StringUtils.hasText(json)) {
			return Map.of();
		}
		try {
			return objectMapper.readValue(json, new TypeReference<>() { });
		}
		catch (JsonProcessingException e) {
			logger.warn("LegacyCapabilityCompiler#readJsonMap - invalid json: {}", e.getMessage());
			return Map.of();
		}
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> castMap(Object value) {
		if (value instanceof Map<?, ?> map) {
			return (Map<String, Object>) map;
		}
		return Map.of();
	}

	private String writeJson(Object value) {
		if (value == null) {
			return null;
		}
		try {
			return objectMapper.writeValueAsString(value);
		}
		catch (JsonProcessingException e) {
			throw new IllegalStateException("Failed to serialize legacy compiler binding", e);
		}
	}

	private String normalizeHttpMethod(String rawHttpMethod) {
		return defaultIfBlank(rawHttpMethod, "POST").toUpperCase(Locale.ROOT);
	}

	private String normalizeRiskLevel(String rawRiskLevel) {
		if (!StringUtils.hasText(rawRiskLevel)) {
			return "LOW";
		}
		String normalized = rawRiskLevel.trim().toUpperCase(Locale.ROOT);
		return switch (normalized) {
			case "LOW", "MEDIUM", "HIGH", "CRITICAL" -> normalized;
			default -> "LOW";
		};
	}

	private String normalizeSideEffect(String rawSideEffect) {
		if (!StringUtils.hasText(rawSideEffect)) {
			return "write";
		}
		String normalized = rawSideEffect.trim().toUpperCase(Locale.ROOT);
		return switch (normalized) {
			case "NONE" -> "none";
			case "WRITE", "MUTATE" -> "write";
			case "HIGH_IMPACT", "HIGH" -> "high_impact";
			default -> "write";
		};
	}

	private String normalizeDefinitionStatus(String rawStatus) {
		return STATUS_ENABLED.equalsIgnoreCase(defaultIfBlank(rawStatus, STATUS_ENABLED)) ? STATUS_ENABLED : STATUS_ENABLED;
	}

	private String readString(Map<String, Object> row, String... keys) {
		Object value = readValue(row, keys);
		if (value == null) {
			return null;
		}
		String text = String.valueOf(value).trim();
		return StringUtils.hasText(text) ? text : null;
	}

	private Integer readInteger(Map<String, Object> row, Integer defaultValue, String... keys) {
		Object value = readValue(row, keys);
		if (value == null) {
			return defaultValue;
		}
		if (value instanceof Number number) {
			return number.intValue();
		}
		try {
			return Integer.parseInt(String.valueOf(value).trim());
		}
		catch (NumberFormatException ex) {
			return defaultValue;
		}
	}

	private Boolean readBoolean(Map<String, Object> row, Boolean defaultValue, String... keys) {
		Object value = readValue(row, keys);
		if (value == null) {
			return defaultValue;
		}
		if (value instanceof Boolean bool) {
			return bool;
		}
		if (value instanceof Number number) {
			return number.intValue() != 0;
		}
		String text = String.valueOf(value).trim().toLowerCase(Locale.ROOT);
		if ("1".equals(text) || "true".equals(text) || "yes".equals(text) || "y".equals(text)) {
			return true;
		}
		if ("0".equals(text) || "false".equals(text) || "no".equals(text) || "n".equals(text)) {
			return false;
		}
		return defaultValue;
	}

	private Object readValue(Map<String, Object> row, String... keys) {
		if (row == null || row.isEmpty() || keys == null) {
			return null;
		}
		for (String key : keys) {
			if (!StringUtils.hasText(key)) {
				continue;
			}
			if (row.containsKey(key)) {
				return row.get(key);
			}
			for (Map.Entry<String, Object> entry : row.entrySet()) {
				if (entry.getKey() != null && key.equalsIgnoreCase(entry.getKey())) {
					return entry.getValue();
				}
			}
		}
		return null;
	}

	private String defaultIfBlank(String value, String defaultValue) {
		return StringUtils.hasText(value) ? value : defaultValue;
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

	private static final String STATUS_ENABLED = "enabled";

	public record CompilationResult(int scanned, int inserted, int updated, int skipped, String sourceTable) {
	}

	private record LoadedRows(String tableName, List<Map<String, Object>> rows) {
	}

}
