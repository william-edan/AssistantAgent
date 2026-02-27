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

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Result payload for DataAgent query execution.
 *
 * @author Assistant Agent Team
 * @since 1.0.0
 */
public class DataAgentQueryResult {

	private boolean success;

	private String agentId;

	private String query;

	private String sql;

	private Integer rowCount;

	private List<Map<String, Object>> rows;

	private String error;

	public static DataAgentQueryResult success(String agentId, String query, String sql, List<Map<String, Object>> rows) {
		DataAgentQueryResult result = new DataAgentQueryResult();
		result.setSuccess(true);
		result.setAgentId(agentId);
		result.setQuery(query);
		result.setSql(sql);
		result.setRows(rows != null ? rows : List.of());
		result.setRowCount(result.getRows().size());
		return result;
	}

	public static DataAgentQueryResult failure(String agentId, String query, String sql, String error) {
		DataAgentQueryResult result = new DataAgentQueryResult();
		result.setSuccess(false);
		result.setAgentId(agentId);
		result.setQuery(query);
		result.setSql(sql);
		result.setRows(Collections.emptyList());
		result.setRowCount(0);
		result.setError(error);
		return result;
	}

	public boolean isSuccess() {
		return success;
	}

	public void setSuccess(boolean success) {
		this.success = success;
	}

	public String getAgentId() {
		return agentId;
	}

	public void setAgentId(String agentId) {
		this.agentId = agentId;
	}

	public String getQuery() {
		return query;
	}

	public void setQuery(String query) {
		this.query = query;
	}

	public String getSql() {
		return sql;
	}

	public void setSql(String sql) {
		this.sql = sql;
	}

	public Integer getRowCount() {
		return rowCount;
	}

	public void setRowCount(Integer rowCount) {
		this.rowCount = rowCount;
	}

	public List<Map<String, Object>> getRows() {
		return rows;
	}

	public void setRows(List<Map<String, Object>> rows) {
		this.rows = rows;
	}

	public String getError() {
		return error;
	}

	public void setError(String error) {
		this.error = error;
	}

}
