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

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class ToolMetaServiceContractTest {

	@Test
	void shouldNormalizeBlankTenantToDefault() {
		ToolMetaService service = new ToolMetaService();
		assertEquals("default", service.normalizeTenant(" "));
		assertEquals("default", service.normalizeTenant(null));
		assertEquals("tenant-a", service.normalizeTenant("tenant-a"));
	}

	@Test
	void shouldDelegateTenantEnabledQueryToList() {
		ToolMetaService service = spy(new ToolMetaService());
		ToolMeta row = new ToolMeta();
		row.setToolCode("tool_a");
		doReturn(List.of(row)).when(service).list(any(Wrapper.class));

		List<ToolMeta> result = service.listEnabledByTenant("tenant-a");

		assertEquals(1, result.size());
		assertEquals("tool_a", result.get(0).getToolCode());
		verify(service, times(1)).list(any(Wrapper.class));
	}

	@Test
	void shouldDelegateTenantSystemEnabledQueryToList() {
		ToolMetaService service = spy(new ToolMetaService());
		ToolMeta row = new ToolMeta();
		row.setToolCode("tool_b");
		doReturn(List.of(row)).when(service).list(any(Wrapper.class));

		List<ToolMeta> result = service.listEnabledByTenantAndSystem("tenant-a", "gougu_oa");

		assertEquals(1, result.size());
		assertEquals("tool_b", result.get(0).getToolCode());
		verify(service, times(1)).list(any(Wrapper.class));
	}

	@Test
	void shouldReturnEmptyWhenToolCodeBlank() {
		ToolMetaService service = spy(new ToolMetaService());
		Optional<ToolMeta> result = service.findLatestEnabledByToolCode("default", " ");

		assertTrue(result.isEmpty());
		verify(service, never()).getOne(any(Wrapper.class), eq(false));
	}

	@Test
	void shouldQueryLatestEnabledByToolCodeWithCompatibilityStatusFilter() {
		ToolMetaService service = spy(new ToolMetaService());
		ToolMeta row = new ToolMeta();
		row.setToolCode("gougu_oa.leave_application");
		doReturn(row).when(service).getOne(any(Wrapper.class), eq(false));

		Optional<ToolMeta> result =
				service.findLatestEnabledByToolCode("default", "gougu_oa.leave_application");

		assertTrue(result.isPresent());
		assertEquals("gougu_oa.leave_application", result.get().getToolCode());

		ArgumentCaptor<Wrapper<ToolMeta>> captor = ArgumentCaptor.forClass(Wrapper.class);
		verify(service).getOne(captor.capture(), eq(false));
		assertTrue(captor.getValue() != null);
	}

}
