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

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CapabilityToToolMetaMigratorTest {

	@Test
	void shouldConvertLegacyRowToToolMeta() {
		CapabilityToToolMetaMigrator migrator = new CapabilityToToolMetaMigrator(
				mock(JdbcTemplate.class),
				mock(ToolMetaService.class));

		Map<String, Object> row = new HashMap<>();
		row.put("tenant_id", "default");
		row.put("system_code", "gougu_oa");
		row.put("capability_code", "leave_application");
		row.put("capability_name", "leave_application");
		row.put("capability_desc", "submit leave request");
		row.put("api_endpoint", "/home/leaves/add");
		row.put("http_method", "post");
		row.put("content_type", "application/x-www-form-urlencoded");
		row.put("slot_schema", "{\"slots\":[]}");
		row.put("flow_steps", "{\"version\":\"2.0\"}");
		row.put("behavior_strategy", "{\"confirm\":true}");
		row.put("risk_level", "HIGH");
		row.put("requires_auth", 1);
		row.put("capability_type", "write");
		row.put("status", "enabled");

		ToolMeta converted = migrator.convert(row);

		assertEquals("default", converted.getTenantId());
		assertEquals("gougu_oa.leave_application", converted.getToolCode());
		assertEquals("leave_application", converted.getToolName());
		assertEquals("submit leave request", converted.getDescription());
		assertEquals("gougu_oa", converted.getSystemCode());
		assertEquals("/home/leaves/add", converted.getApiEndpoint());
		assertEquals("POST", converted.getHttpMethod());
		assertEquals("application/x-www-form-urlencoded", converted.getContentType());
		assertEquals("{\"slots\":[]}", converted.getParameterSchema());
		assertEquals("{\"version\":\"2.0\"}", converted.getExecutionPlan());
		assertEquals("{\"confirm\":true}", converted.getInteractionPolicy());
		assertEquals("HIGH", converted.getRiskLevel());
		assertEquals(Boolean.TRUE, converted.getRequiresAuth());
		assertEquals(Boolean.TRUE, converted.getRequiresConfirm());
		assertEquals("WRITE", converted.getCapabilityType());
		assertEquals(1, converted.getVersion());
		assertEquals("enabled", converted.getStatus());
	}

	@Test
	void shouldInsertWhenNoExistingToolMeta() {
		JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
		ToolMetaService toolMetaService = mock(ToolMetaService.class);
		CapabilityToToolMetaMigrator migrator = new CapabilityToToolMetaMigrator(jdbcTemplate, toolMetaService);

		Map<String, Object> row = legacyRow("/home/leaves/add");
		when(jdbcTemplate.queryForList("SELECT * FROM assistant_capability_registry ORDER BY id ASC"))
				.thenReturn(List.of(row));
		when(toolMetaService.getOne(any(Wrapper.class), eq(false))).thenReturn(null);
		when(toolMetaService.save(any(ToolMeta.class))).thenReturn(true);

		CapabilityToToolMetaMigrator.MigrationResult result = migrator.migrateAll();

		assertEquals(1, result.scanned());
		assertEquals(1, result.inserted());
		assertEquals(0, result.updated());
		assertEquals(0, result.skipped());
		assertEquals("assistant_capability_registry", result.sourceTable());

		ArgumentCaptor<ToolMeta> captor = ArgumentCaptor.forClass(ToolMeta.class);
		verify(toolMetaService, times(1)).save(captor.capture());
		assertEquals("gougu_oa.leave_application", captor.getValue().getToolCode());
	}

	@Test
	void shouldUpdateWhenToolMetaAlreadyExists() {
		JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
		ToolMetaService toolMetaService = mock(ToolMetaService.class);
		CapabilityToToolMetaMigrator migrator = new CapabilityToToolMetaMigrator(jdbcTemplate, toolMetaService);

		Map<String, Object> row = legacyRow("/home/leaves/new_add");
		when(jdbcTemplate.queryForList("SELECT * FROM assistant_capability_registry ORDER BY id ASC"))
				.thenReturn(List.of(row));

		ToolMeta existing = new ToolMeta();
		existing.setId(11L);
		existing.setTenantId("default");
		existing.setToolCode("gougu_oa.leave_application");
		existing.setVersion(1);
		existing.setApiEndpoint("/home/leaves/old_add");
		when(toolMetaService.getOne(any(Wrapper.class), eq(false))).thenReturn(existing);
		when(toolMetaService.updateById(any(ToolMeta.class))).thenReturn(true);

		CapabilityToToolMetaMigrator.MigrationResult result = migrator.migrateAll();

		assertEquals(1, result.scanned());
		assertEquals(0, result.inserted());
		assertEquals(1, result.updated());
		assertEquals(0, result.skipped());

		ArgumentCaptor<ToolMeta> captor = ArgumentCaptor.forClass(ToolMeta.class);
		verify(toolMetaService, times(1)).updateById(captor.capture());
		assertEquals(11L, captor.getValue().getId());
		assertEquals("/home/leaves/new_add", captor.getValue().getApiEndpoint());
		assertTrue(captor.getValue().getRequiresAuth());
	}

	private Map<String, Object> legacyRow(String endpoint) {
		Map<String, Object> row = new HashMap<>();
		row.put("tenant_id", "default");
		row.put("system_code", "gougu_oa");
		row.put("capability_code", "leave_application");
		row.put("capability_name", "leave_application");
		row.put("capability_desc", "submit leave request");
		row.put("api_endpoint", endpoint);
		row.put("http_method", "POST");
		row.put("content_type", "application/x-www-form-urlencoded");
		row.put("slot_schema", "{\"slots\":[]}");
		row.put("flow_steps", "{\"version\":\"2.0\"}");
		row.put("behavior_strategy", "{\"confirm\":false}");
		row.put("risk_level", "LOW");
		row.put("requires_auth", 1);
		row.put("requires_confirm", 0);
		row.put("capability_type", "ACTION");
		row.put("version", 1);
		row.put("status", "enabled");
		return row;
	}

}
