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
package com.alibaba.assistant.agent.runtime.policy;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * SQL guard policy for DataAgent query execution.
 * Enforces read-only semantics, denylist checks and sensitive column masking.
 *
 * @author Assistant Agent Team
 * @since 1.0.0
 */
@Component
@Profile("migration")
public class SqlGuardPolicy {

	private static final Set<String> DANGEROUS_TOKENS = new LinkedHashSet<>(List.of(
			";",
			"--",
			"/*",
			"*/",
			" drop ",
			" delete ",
			" update ",
			" insert ",
			" alter ",
			" truncate ",
			" create ",
			" grant ",
			" revoke ",
			" merge ",
			" call ",
			" exec ",
			" sleep(",
			" benchmark("));

	private static final Set<String> SENSITIVE_COLUMN_MARKERS = new LinkedHashSet<>(List.of(
			"password",
			"passwd",
			"secret",
			"token",
			"credential",
			"mobile",
			"phone",
			"email",
			"id_card",
			"bank_card"));

	/**
	 * Build row-level filter from assistant identity.
	 */
	public String buildRowFilter(String assistantUid) {
		if (!StringUtils.hasText(assistantUid)) {
			return null;
		}
		String escaped = assistantUid.replace("'", "''");
		return "assistant_uid = '" + escaped + "'";
	}

	/**
	 * Validate SQL for read-only execution.
	 *
	 * @throws IllegalArgumentException when SQL is unsafe
	 */
	public void validate(String sql) {
		if (!StringUtils.hasText(sql)) {
			throw new IllegalArgumentException("SQL is empty");
		}
		String normalized = normalize(sql);
		if (!(normalized.startsWith(" select ") || normalized.startsWith(" with "))) {
			throw new IllegalArgumentException("Only read-only SELECT/WITH SQL is allowed");
		}
		for (String token : DANGEROUS_TOKENS) {
			if (normalized.contains(token)) {
				throw new IllegalArgumentException("SQL contains denied token: " + token.trim());
			}
		}
	}

	/**
	 * Mask sensitive columns in query rows.
	 */
	public List<Map<String, Object>> maskSensitiveColumns(List<Map<String, Object>> rows) {
		if (rows == null || rows.isEmpty()) {
			return rows == null ? List.of() : rows;
		}

		List<Map<String, Object>> masked = new ArrayList<>(rows.size());
		for (Map<String, Object> row : rows) {
			Map<String, Object> maskedRow = new LinkedHashMap<>();
			if (row != null) {
				for (Map.Entry<String, Object> entry : row.entrySet()) {
					String key = entry.getKey();
					Object value = entry.getValue();
					if (isSensitiveColumn(key) && value != null) {
						maskedRow.put(key, maskValue(value));
					}
					else {
						maskedRow.put(key, value);
					}
				}
			}
			masked.add(maskedRow);
		}
		return masked;
	}

	private boolean isSensitiveColumn(String columnName) {
		if (!StringUtils.hasText(columnName)) {
			return false;
		}
		String normalized = columnName.toLowerCase(Locale.ROOT);
		for (String marker : SENSITIVE_COLUMN_MARKERS) {
			if (normalized.contains(marker)) {
				return true;
			}
		}
		return false;
	}

	private Object maskValue(Object value) {
		String text = String.valueOf(value);
		if (!StringUtils.hasText(text)) {
			return "***";
		}
		if (text.length() <= 4) {
			return "***";
		}
		return text.substring(0, 2) + "***" + text.substring(text.length() - 2);
	}

	private String normalize(String sql) {
		return " " + sql.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim() + " ";
	}

}
