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
package com.alibaba.assistant.agent.infra.persistence.mysql;

import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.checkpoint.BaseCheckpointSaver;
import com.alibaba.cloud.ai.graph.checkpoint.Checkpoint;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;

/**
 * MySQL-backed implementation of {@link BaseCheckpointSaver}.
 * Persists checkpoint state as JSON in the checkpoint table (V5 migration).
 *
 * @author Assistant Agent Team
 * @since 1.0.0
 */
public class MysqlCheckpointSaver implements BaseCheckpointSaver {

	private static final Logger logger = LoggerFactory.getLogger(MysqlCheckpointSaver.class);

	private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
	};

	private final JdbcTemplate jdbcTemplate;

	private final ObjectMapper objectMapper;

	public MysqlCheckpointSaver(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
		this.jdbcTemplate = jdbcTemplate;
		this.objectMapper = objectMapper;
	}

	public MysqlCheckpointSaver(JdbcTemplate jdbcTemplate) {
		this(jdbcTemplate, new ObjectMapper());
	}

	@Override
	public Collection<Checkpoint> list(RunnableConfig config) {
		String threadId = resolveThreadId(config);
		List<Checkpoint> results = jdbcTemplate.query(
				"SELECT checkpoint_id, state_json, parent_id FROM checkpoint WHERE thread_id = ? ORDER BY created_at DESC",
				(rs, rowNum) -> mapRowToCheckpoint(rs), threadId);
		logger.debug("MysqlCheckpointSaver#list - threadId={}, count={}", threadId, results.size());
		return results;
	}

	@Override
	public Optional<Checkpoint> get(RunnableConfig config) {
		String threadId = resolveThreadId(config);
		Optional<String> checkPointId = config.checkPointId();
		if (checkPointId.isPresent()) {
			List<Checkpoint> results = jdbcTemplate.query(
					"SELECT checkpoint_id, state_json, parent_id FROM checkpoint WHERE thread_id = ? AND checkpoint_id = ?",
					(rs, rowNum) -> mapRowToCheckpoint(rs), threadId, checkPointId.get());
			return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
		}
		// Return latest checkpoint
		List<Checkpoint> results = jdbcTemplate.query(
				"SELECT checkpoint_id, state_json, parent_id FROM checkpoint WHERE thread_id = ? ORDER BY created_at DESC LIMIT 1",
				(rs, rowNum) -> mapRowToCheckpoint(rs), threadId);
		return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
	}

	@Override
	public RunnableConfig put(RunnableConfig config, Checkpoint checkpoint) throws Exception {
		String threadId = resolveThreadId(config);
		String checkpointId = checkpoint.getId();
		String stateJson = serializeState(checkpoint.getState());

		// Check if checkpoint already exists
		Integer count = jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM checkpoint WHERE thread_id = ? AND checkpoint_id = ?", Integer.class, threadId,
				checkpointId);

		if (count != null && count > 0) {
			jdbcTemplate.update("UPDATE checkpoint SET state_json = ? WHERE thread_id = ? AND checkpoint_id = ?",
					stateJson, threadId, checkpointId);
			logger.debug("MysqlCheckpointSaver#put - updated threadId={}, checkpointId={}", threadId, checkpointId);
		}
		else {
			// Get parent_id from previous latest checkpoint
			List<String> parentIds = jdbcTemplate.query(
					"SELECT checkpoint_id FROM checkpoint WHERE thread_id = ? ORDER BY created_at DESC LIMIT 1",
					(rs, rowNum) -> rs.getString("checkpoint_id"), threadId);
			String parentId = parentIds.isEmpty() ? null : parentIds.get(0);

			jdbcTemplate.update(
					"INSERT INTO checkpoint (thread_id, checkpoint_id, state_json, parent_id) VALUES (?, ?, ?, ?)",
					threadId, checkpointId, stateJson, parentId);
			logger.debug("MysqlCheckpointSaver#put - inserted threadId={}, checkpointId={}, parentId={}", threadId,
					checkpointId, parentId);
		}

		return RunnableConfig.builder(config).checkPointId(checkpointId).build();
	}

	@Override
	public Tag release(RunnableConfig config) throws Exception {
		String threadId = resolveThreadId(config);
		Collection<Checkpoint> checkpoints = list(config);
		jdbcTemplate.update("DELETE FROM checkpoint WHERE thread_id = ?", threadId);
		logger.debug("MysqlCheckpointSaver#release - threadId={}, released={}", threadId, checkpoints.size());
		return new Tag(threadId, checkpoints);
	}

	private String resolveThreadId(RunnableConfig config) {
		return config.threadId().orElse(THREAD_ID_DEFAULT);
	}

	private Checkpoint mapRowToCheckpoint(ResultSet rs) throws SQLException {
		String checkpointId = rs.getString("checkpoint_id");
		String stateJson = rs.getString("state_json");
		Map<String, Object> state = deserializeState(stateJson);
		return Checkpoint.builder().id(checkpointId).state(state).nodeId("").nextNodeId("").build();
	}

	private String serializeState(Map<String, Object> state) throws JsonProcessingException {
		return objectMapper.writeValueAsString(state != null ? state : Collections.emptyMap());
	}

	private Map<String, Object> deserializeState(String json) {
		if (json == null || json.isEmpty()) {
			return new HashMap<>();
		}
		try {
			return objectMapper.readValue(json, MAP_TYPE);
		}
		catch (JsonProcessingException e) {
			logger.error("MysqlCheckpointSaver#deserializeState - failed to parse JSON: {}", e.getMessage());
			return new HashMap<>();
		}
	}

}
