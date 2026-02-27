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
package com.alibaba.assistant.agent.controlplane.toolregistry;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * One-time migrator that converts legacy CapabilityRegistry rows into ToolMeta records.
 * <p>
 * Compatible with legacy JSON fields:
 * {@code slot_schema -> parameter_schema}, {@code flow_steps -> execution_plan},
 * {@code behavior_strategy -> interaction_policy}.
 *
 * @author Assistant Agent Team
 * @since 1.0.0
 */
@Service
@Profile("migration")
public class CapabilityToToolMetaMigrator {

	private static final Logger logger = LoggerFactory.getLogger(CapabilityToToolMetaMigrator.class);

	private static final List<String> LEGACY_TABLE_CANDIDATES = List.of(
			"assistant_capability_registry",
			"capability_registry");

	private final JdbcTemplate jdbcTemplate;

	private final ToolMetaService toolMetaService;

	public CapabilityToToolMetaMigrator(JdbcTemplate jdbcTemplate, ToolMetaService toolMetaService) {
		this.jdbcTemplate = jdbcTemplate;
		this.toolMetaService = toolMetaService;
	}

	/**
	 * Migrate all legacy capability rows to {@code tool_meta}.
	 * The operation is idempotent by unique key ({@code tenantId + toolCode + version}).
	 *
	 * @return migration statistics
	 */
	@Transactional
	public MigrationResult migrateAll() {
		LoadedRows loadedRows = loadLegacyRows();
		if (loadedRows.rows().isEmpty()) {
			logger.warn("CapabilityToToolMetaMigrator#migrateAll - no legacy rows found");
			return new MigrationResult(0, 0, 0, 0, loadedRows.tableName());
		}

		int scanned = 0;
		int inserted = 0;
		int updated = 0;
		int skipped = 0;

		for (Map<String, Object> row : loadedRows.rows()) {
			scanned++;
			ToolMeta candidate = convert(row);
			if (candidate == null) {
				skipped++;
				continue;
			}

			ToolMeta existing = findExisting(candidate);
			if (existing == null) {
				toolMetaService.save(candidate);
				inserted++;
			}
			else {
				merge(existing, candidate);
				toolMetaService.updateById(existing);
				updated++;
			}
		}

		MigrationResult result = new MigrationResult(scanned, inserted, updated, skipped, loadedRows.tableName());
		logger.info("CapabilityToToolMetaMigrator#migrateAll - sourceTable={}, scanned={}, inserted={}, updated={}, "
				+ "skipped={}",
				result.sourceTable(), result.scanned(), result.inserted(), result.updated(), result.skipped());
		return result;
	}

	/**
	 * Convert one legacy row into a {@link ToolMeta}.
	 *
	 * @param legacyRow raw row from legacy capability table
	 * @return converted {@link ToolMeta}, or null when mandatory fields are absent
	 */
	public ToolMeta convert(Map<String, Object> legacyRow) {
		if (legacyRow == null || legacyRow.isEmpty()) {
			return null;
		}

		String systemCode = readString(legacyRow, "system_code", "systemCode");
		String capabilityCode = firstNonBlank(
				readString(legacyRow, "capability_code", "capabilityCode"),
				readString(legacyRow, "tool_code", "toolCode"));
		if (!StringUtils.hasText(systemCode) || !StringUtils.hasText(capabilityCode)) {
			logger.warn("CapabilityToToolMetaMigrator#convert - skip row due to missing system/capability code, row={}",
					legacyRow);
			return null;
		}

		ToolMeta toolMeta = new ToolMeta();
		toolMeta.setTenantId(defaultIfBlank(readString(legacyRow, "tenant_id", "tenantId"), "default"));
		toolMeta.setToolCode(buildToolCode(systemCode, capabilityCode));
		toolMeta.setToolName(defaultIfBlank(
				firstNonBlank(readString(legacyRow, "capability_name", "capabilityName"),
						readString(legacyRow, "tool_name", "toolName")),
				capabilityCode));
		toolMeta.setDescription(firstNonBlank(
				readString(legacyRow, "capability_desc", "capabilityDesc"),
				readString(legacyRow, "description")));
		toolMeta.setSystemCode(systemCode);
		toolMeta.setApiEndpoint(firstNonBlank(
				readString(legacyRow, "api_endpoint", "apiEndpoint"),
				readString(legacyRow, "endpoint")));
		toolMeta.setHttpMethod(normalizeHttpMethod(readString(legacyRow, "http_method", "httpMethod")));
		toolMeta.setContentType(defaultIfBlank(
				readString(legacyRow, "content_type", "contentType"), "application/json"));
		toolMeta.setParameterSchema(firstNonBlank(
				readString(legacyRow, "slot_schema", "slotSchema"),
				readString(legacyRow, "parameter_schema", "parameterSchema"),
				readString(legacyRow, "request_schema", "requestSchema")));
		toolMeta.setExecutionPlan(firstNonBlank(
				readString(legacyRow, "flow_steps", "flowSteps"),
				readString(legacyRow, "execution_plan", "executionPlan")));
		toolMeta.setInteractionPolicy(firstNonBlank(
				readString(legacyRow, "behavior_strategy", "behaviorStrategy"),
				readString(legacyRow, "interaction_policy", "interactionPolicy")));

		String riskLevel = normalizeRiskLevel(readString(legacyRow, "risk_level", "riskLevel"));
		toolMeta.setRiskLevel(riskLevel);
		toolMeta.setRequiresAuth(readBoolean(legacyRow, true, "requires_auth", "requiresAuth"));
		toolMeta.setRequiresConfirm(readBoolean(legacyRow,
				inferRequiresConfirm(riskLevel, toolMeta.getInteractionPolicy()),
				"requires_confirm", "requiresConfirm"));
		toolMeta.setCapabilityType(normalizeCapabilityType(readString(legacyRow, "capability_type", "capabilityType")));
		toolMeta.setVersion(readInteger(legacyRow, 1, "version"));
		toolMeta.setStatus(defaultIfBlank(readString(legacyRow, "status"), "enabled"));
		return toolMeta;
	}

	private LoadedRows loadLegacyRows() {
		for (String tableName : LEGACY_TABLE_CANDIDATES) {
			try {
				List<Map<String, Object>> rows = jdbcTemplate.queryForList(
						"SELECT * FROM " + tableName + " ORDER BY id ASC");
				logger.info("CapabilityToToolMetaMigrator#loadLegacyRows - table={}, rowCount={}",
						tableName, rows.size());
				return new LoadedRows(tableName, rows);
			}
			catch (DataAccessException e) {
				logger.debug("CapabilityToToolMetaMigrator#loadLegacyRows - table unavailable, table={}, error={}",
						tableName, e.getMessage());
			}
		}
		return new LoadedRows("none", List.of());
	}

	private ToolMeta findExisting(ToolMeta candidate) {
		LambdaQueryWrapper<ToolMeta> query = new LambdaQueryWrapper<>();
		query.eq(ToolMeta::getTenantId, candidate.getTenantId())
				.eq(ToolMeta::getToolCode, candidate.getToolCode())
				.eq(ToolMeta::getVersion, candidate.getVersion());
		return toolMetaService.getOne(query, false);
	}

	private void merge(ToolMeta existing, ToolMeta incoming) {
		existing.setToolName(incoming.getToolName());
		existing.setDescription(incoming.getDescription());
		existing.setSystemCode(incoming.getSystemCode());
		existing.setApiEndpoint(incoming.getApiEndpoint());
		existing.setHttpMethod(incoming.getHttpMethod());
		existing.setContentType(incoming.getContentType());
		existing.setParameterSchema(incoming.getParameterSchema());
		existing.setExecutionPlan(incoming.getExecutionPlan());
		existing.setInteractionPolicy(incoming.getInteractionPolicy());
		existing.setRiskLevel(incoming.getRiskLevel());
		existing.setRequiresAuth(incoming.getRequiresAuth());
		existing.setRequiresConfirm(incoming.getRequiresConfirm());
		existing.setCapabilityType(incoming.getCapabilityType());
		existing.setStatus(incoming.getStatus());
	}

	private String buildToolCode(String systemCode, String capabilityCode) {
		String normalizedCapabilityCode = capabilityCode.trim();
		if (normalizedCapabilityCode.contains(".")) {
			return normalizedCapabilityCode;
		}
		return systemCode.trim() + "." + normalizedCapabilityCode;
	}

	private String normalizeHttpMethod(String rawHttpMethod) {
		String normalized = defaultIfBlank(rawHttpMethod, "POST");
		return normalized.toUpperCase(Locale.ROOT);
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

	private String normalizeCapabilityType(String rawCapabilityType) {
		if (!StringUtils.hasText(rawCapabilityType)) {
			return "ACTION";
		}
		String normalized = rawCapabilityType.trim().toUpperCase(Locale.ROOT);
		return switch (normalized) {
			case "READ", "WRITE", "ACTION" -> normalized;
			default -> "ACTION";
		};
	}

	private boolean inferRequiresConfirm(String riskLevel, String interactionPolicy) {
		if ("HIGH".equalsIgnoreCase(riskLevel) || "CRITICAL".equalsIgnoreCase(riskLevel)) {
			return true;
		}
		return interactionPolicy != null && interactionPolicy.toLowerCase(Locale.ROOT).contains("confirm");
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

	private record LoadedRows(String tableName, List<Map<String, Object>> rows) {

	}

	/**
	 * Migration statistics.
	 *
	 * @param scanned scanned row count from legacy table
	 * @param inserted newly inserted tool_meta rows
	 * @param updated updated existing tool_meta rows
	 * @param skipped skipped invalid rows
	 * @param sourceTable selected legacy table
	 */
	public record MigrationResult(int scanned, int inserted, int updated, int skipped, String sourceTable) {

	}

}
