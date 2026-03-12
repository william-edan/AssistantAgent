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
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class MysqlCheckpointSaverTest {

	private static JdbcTemplate jdbcTemplate;

	private MysqlCheckpointSaver saver;

	@BeforeAll
	static void initDatabase() {
		DriverManagerDataSource dataSource = new DriverManagerDataSource();
		dataSource.setDriverClassName("org.h2.Driver");
		dataSource.setUrl("jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1;MODE=MYSQL");
		dataSource.setUsername("sa");
		dataSource.setPassword("");
		jdbcTemplate = new JdbcTemplate(dataSource);
		jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS checkpoint ("
				+ "id BIGINT AUTO_INCREMENT PRIMARY KEY, "
				+ "thread_id VARCHAR(128) NOT NULL, "
				+ "checkpoint_id VARCHAR(128) NOT NULL, "
				+ "state_json CLOB NOT NULL, "
				+ "parent_id VARCHAR(128), "
				+ "created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, "
				+ "UNIQUE (thread_id, checkpoint_id))");
	}

	@BeforeEach
	void setUp() {
		jdbcTemplate.execute("DELETE FROM checkpoint");
		saver = new MysqlCheckpointSaver(jdbcTemplate);
	}

	@Test
	void shouldPutAndGetCheckpoint() throws Exception {
		RunnableConfig config = RunnableConfig.builder().threadId("thread-1").build();
		Map<String, Object> state = Map.of("key1", "value1", "count", 42);
		Checkpoint checkpoint = Checkpoint.builder().state(state).nodeId("node1").nextNodeId("node2").build();

		RunnableConfig result = saver.put(config, checkpoint);

		assertTrue(result.checkPointId().isPresent());
		Optional<Checkpoint> loaded = saver.get(result);
		assertTrue(loaded.isPresent());
		assertEquals("value1", loaded.get().getState().get("key1"));
		assertEquals(42, loaded.get().getState().get("count"));
	}

	@Test
	void shouldGetLatestCheckpointWhenNoCheckpointIdSpecified() throws Exception {
		RunnableConfig config = RunnableConfig.builder().threadId("thread-2").build();
		Checkpoint cp1 = Checkpoint.builder()
			.state(Map.of("round", 1))
			.nodeId("n1")
			.nextNodeId("n2")
			.build();
		Checkpoint cp2 = Checkpoint.builder()
			.state(Map.of("round", 2))
			.nodeId("n2")
			.nextNodeId("n3")
			.build();

		saver.put(config, cp1);
		saver.put(config, cp2);

		Optional<Checkpoint> latest = saver.get(config);
		assertTrue(latest.isPresent());
		assertEquals(2, latest.get().getState().get("round"));
	}

	@Test
	void shouldPreferNewestRowWhenCreatedAtTies() {
		jdbcTemplate.update(
				"INSERT INTO checkpoint (thread_id, checkpoint_id, state_json, parent_id, created_at) VALUES (?, ?, ?, ?, ?)",
				"thread-tie", "cp-1", "{\"round\":1}", null, java.sql.Timestamp.valueOf("2026-03-11 22:30:00"));
		jdbcTemplate.update(
				"INSERT INTO checkpoint (thread_id, checkpoint_id, state_json, parent_id, created_at) VALUES (?, ?, ?, ?, ?)",
				"thread-tie", "cp-2", "{\"round\":2}", "cp-1", java.sql.Timestamp.valueOf("2026-03-11 22:30:00"));

		Optional<Checkpoint> latest = saver.get(RunnableConfig.builder().threadId("thread-tie").build());

		assertTrue(latest.isPresent());
		assertEquals("cp-2", latest.get().getId());
		assertEquals(2, latest.get().getState().get("round"));
	}
	@Test
	void shouldListAllCheckpointsForThread() throws Exception {
		RunnableConfig config = RunnableConfig.builder().threadId("thread-3").build();
		saver.put(config,
				Checkpoint.builder().state(Map.of("step", 1)).nodeId("n1").nextNodeId("n2").build());
		saver.put(config,
				Checkpoint.builder().state(Map.of("step", 2)).nodeId("n2").nextNodeId("n3").build());

		Collection<Checkpoint> all = saver.list(config);
		assertEquals(2, all.size());
	}

	@Test
	void shouldReleaseAllCheckpointsForThread() throws Exception {
		RunnableConfig config = RunnableConfig.builder().threadId("thread-4").build();
		saver.put(config,
				Checkpoint.builder().state(Map.of("data", "test")).nodeId("n1").nextNodeId("n2").build());

		BaseCheckpointSaver.Tag tag = saver.release(config);
		assertEquals("thread-4", tag.threadId());
		assertEquals(1, tag.checkpoints().size());

		Collection<Checkpoint> afterRelease = saver.list(config);
		assertTrue(afterRelease.isEmpty());
	}

	@Test
	void shouldUpdateExistingCheckpoint() throws Exception {
		RunnableConfig config = RunnableConfig.builder().threadId("thread-5").build();
		Checkpoint cp = Checkpoint.builder()
			.state(Map.of("version", 1))
			.nodeId("n1")
			.nextNodeId("n2")
			.build();
		RunnableConfig saved = saver.put(config, cp);

		// Update with same checkpoint id
		Checkpoint updated = Checkpoint.builder()
			.id(saved.checkPointId().get())
			.state(Map.of("version", 2))
			.nodeId("n1")
			.nextNodeId("n2")
			.build();
		saver.put(config, updated);

		Collection<Checkpoint> all = saver.list(config);
		assertEquals(1, all.size());
		assertEquals(2, all.iterator().next().getState().get("version"));
	}

	@Test
	void shouldIsolateCheckpointsByThread() throws Exception {
		RunnableConfig config1 = RunnableConfig.builder().threadId("thread-A").build();
		RunnableConfig config2 = RunnableConfig.builder().threadId("thread-B").build();

		saver.put(config1,
				Checkpoint.builder().state(Map.of("thread", "A")).nodeId("n1").nextNodeId("n2").build());
		saver.put(config2,
				Checkpoint.builder().state(Map.of("thread", "B")).nodeId("n1").nextNodeId("n2").build());

		assertEquals(1, saver.list(config1).size());
		assertEquals(1, saver.list(config2).size());
		assertEquals("A", saver.get(config1).get().getState().get("thread"));
		assertEquals("B", saver.get(config2).get().getState().get("thread"));
	}

}
