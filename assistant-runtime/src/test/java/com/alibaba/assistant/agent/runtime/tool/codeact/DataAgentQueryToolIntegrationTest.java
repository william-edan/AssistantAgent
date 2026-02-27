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
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DataAgentQueryToolIntegrationTest {

	private DataAgentQueryTool dataAgentQueryTool;

	private ObjectMapper objectMapper;

	@BeforeEach
	void setUp() {
		DriverManagerDataSource dataSource = new DriverManagerDataSource();
		dataSource.setDriverClassName("org.h2.Driver");
		dataSource.setUrl("jdbc:h2:mem:data_agent_test;MODE=MySQL;DB_CLOSE_DELAY=-1");
		dataSource.setUsername("sa");
		dataSource.setPassword("");

		JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
		jdbcTemplate.execute("DROP TABLE IF EXISTS leave_records");
		jdbcTemplate.execute("""
				CREATE TABLE leave_records (
				    id BIGINT AUTO_INCREMENT PRIMARY KEY,
				    assistant_uid VARCHAR(64),
				    person_name VARCHAR(64),
				    phone VARCHAR(32),
				    leave_days INT
				)
				""");
		jdbcTemplate.update(
				"INSERT INTO leave_records(assistant_uid, person_name, phone, leave_days) VALUES(?, ?, ?, ?)",
				"assistant-1001", "Alice", "13800138000", 2);
		jdbcTemplate.update(
				"INSERT INTO leave_records(assistant_uid, person_name, phone, leave_days) VALUES(?, ?, ?, ?)",
				"assistant-2002", "Bob", "13900139000", 3);

		objectMapper = new ObjectMapper();
		SqlGuardPolicy sqlGuardPolicy = new SqlGuardPolicy();
		DataAgentQueryService queryService = new DefaultDataAgentQueryService(jdbcTemplate, sqlGuardPolicy);
		dataAgentQueryTool = new DataAgentQueryTool(objectMapper, queryService, sqlGuardPolicy);
	}

	@Test
	void shouldQueryWithRowFilterAndMaskSensitiveColumns() throws Exception {
		String payload = dataAgentQueryTool.call("""
				{
				  "query": "SQL: SELECT id, assistant_uid, person_name, phone, leave_days FROM leave_records",
				  "assistant_uid": "assistant-1001"
				}
				""");
		@SuppressWarnings("unchecked")
		Map<String, Object> result = objectMapper.readValue(payload, Map.class);

		assertEquals(Boolean.TRUE, result.get("success"));
		assertEquals(1, ((Number) result.get("rowCount")).intValue());
		assertTrue(String.valueOf(result.get("sql")).toLowerCase(Locale.ROOT).contains("assistant_uid"));

		@SuppressWarnings("unchecked")
		List<Map<String, Object>> rows = (List<Map<String, Object>>) result.get("rows");
		assertEquals(1, rows.size());
		Map<String, Object> row = rows.get(0);
		assertEquals("assistant-1001", getIgnoreCase(row, "assistant_uid"));
		assertNotEquals("13800138000", String.valueOf(getIgnoreCase(row, "phone")));
	}

	@Test
	void shouldRejectNonReadOnlySql() throws Exception {
		String payload = dataAgentQueryTool.call("""
				{
				  "query": "SQL: DELETE FROM leave_records WHERE id = 1",
				  "assistant_uid": "assistant-1001"
				}
				""");
		@SuppressWarnings("unchecked")
		Map<String, Object> result = objectMapper.readValue(payload, Map.class);

		assertEquals(Boolean.FALSE, result.get("success"));
		assertEquals(0, ((Number) result.get("rowCount")).intValue());
		assertTrue(String.valueOf(result.get("error")).toLowerCase(Locale.ROOT).contains("read-only"));
	}

	private Object getIgnoreCase(Map<String, Object> map, String key) {
		if (map.containsKey(key)) {
			return map.get(key);
		}
		for (Map.Entry<String, Object> entry : map.entrySet()) {
			if (entry.getKey() != null && key.equalsIgnoreCase(entry.getKey())) {
				return entry.getValue();
			}
		}
		return null;
	}

}
