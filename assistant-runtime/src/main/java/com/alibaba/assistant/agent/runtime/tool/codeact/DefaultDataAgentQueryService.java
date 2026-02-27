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

import com.alibaba.assistant.agent.runtime.policy.SqlGuardPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Default DataAgent query service backed by JDBC.
 * Supports minimal NL-to-SQL fallback for migration stage.
 *
 * @author Assistant Agent Team
 * @since 1.0.0
 */
@Service
@Profile("migration")
public class DefaultDataAgentQueryService implements DataAgentQueryService {

	private static final Logger logger = LoggerFactory.getLogger(DefaultDataAgentQueryService.class);

	private final JdbcTemplate jdbcTemplate;

	private final SqlGuardPolicy sqlGuardPolicy;

	public DefaultDataAgentQueryService(JdbcTemplate jdbcTemplate, SqlGuardPolicy sqlGuardPolicy) {
		this.jdbcTemplate = jdbcTemplate;
		this.sqlGuardPolicy = sqlGuardPolicy;
	}

	@Override
	public DataAgentQueryResult query(String agentId, String query, String rowFilter) {
		String normalizedAgentId = StringUtils.hasText(agentId) ? agentId : "default_data_agent";
		String resolvedSql = resolveSqlFromQuery(query);
		String finalSql = appendRowFilter(resolvedSql, rowFilter);

		try {
			sqlGuardPolicy.validate(finalSql);
			List<Map<String, Object>> rows = jdbcTemplate.queryForList(finalSql);
			List<Map<String, Object>> maskedRows = sqlGuardPolicy.maskSensitiveColumns(rows);
			return DataAgentQueryResult.success(normalizedAgentId, query, finalSql, maskedRows);
		}
		catch (Exception e) {
			logger.warn("DefaultDataAgentQueryService#query - failed, agentId={}, error={}",
					normalizedAgentId, e.getMessage());
			return DataAgentQueryResult.failure(normalizedAgentId, query, finalSql, e.getMessage());
		}
	}

	private String resolveSqlFromQuery(String query) {
		if (!StringUtils.hasText(query)) {
			return "SELECT 1 AS value";
		}
		String trimmed = query.trim();
		if (trimmed.regionMatches(true, 0, "SQL:", 0, 4)) {
			return trimmed.substring(4).trim();
		}

		String normalized = trimmed.toLowerCase(Locale.ROOT);
		if (normalized.contains("请假人数") || normalized.contains("leave count")) {
			return "SELECT COUNT(*) AS leave_count FROM leave_records";
		}
		if (normalized.contains("请假") || normalized.contains("leave")) {
			return "SELECT id, assistant_uid, person_name, phone, leave_days FROM leave_records ORDER BY id DESC";
		}
		return "SELECT 1 AS value";
	}

	private String appendRowFilter(String sql, String rowFilter) {
		if (!StringUtils.hasText(sql) || !StringUtils.hasText(rowFilter)) {
			return sql;
		}
		String normalized = sql.toLowerCase(Locale.ROOT);
		if (normalized.contains(" where ")) {
			return sql + " AND (" + rowFilter + ")";
		}
		return sql + " WHERE " + rowFilter;
	}

}
