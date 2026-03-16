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
package com.alibaba.assistant.agent.api.security;

import com.alibaba.assistant.agent.controlplane.identity.IdentityBinding;
import com.alibaba.assistant.agent.controlplane.identity.mapper.IdentityBindingMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SystemIdentityBindingServiceTest {

	@Mock
	private JdbcTemplate jdbcTemplate;

	@Mock
	private IdentityBindingMapper identityBindingMapper;

	@Mock
	private RestTemplate restTemplate;

	@Test
	void shouldInsertIdentityBindingWhenDownstreamBindSucceeds() {
		SystemIdentityBindingService service = new SystemIdentityBindingService(
				jdbcTemplate,
				identityBindingMapper,
				new ObjectMapper(),
				restTemplate);
		when(jdbcTemplate.queryForList(anyString(), eq("gougu_oa"))).thenReturn(List.of(Map.of(
				"base_url", "http://office.ai.devefive.com",
				"bind_endpoint", "/api/oa_integration/bind_user",
				"bind_method", "POST",
				"bind_request", "{\"username\":\"${username}\",\"password\":\"${password}\"}",
				"bind_response", "{\"user_id_field\":\"data.user_id\"}")));
		when(restTemplate.exchange(
				eq("http://office.ai.devefive.com/api/oa_integration/bind_user"),
				eq(HttpMethod.POST),
				any(),
				eq(String.class)))
				.thenReturn(ResponseEntity.ok("{\"code\":0,\"data\":{\"user_id\":2}}"));
		when(identityBindingMapper.selectOne(any())).thenReturn(null);

		service.ensureBound("1", "gougu_oa", "admin", "admin123");

		ArgumentCaptor<IdentityBinding> captor = ArgumentCaptor.forClass(IdentityBinding.class);
		verify(identityBindingMapper).insert(captor.capture());
		IdentityBinding binding = captor.getValue();
		assertEquals("1", binding.getAssistantUid());
		assertEquals("gougu_oa", binding.getSystemCode());
		assertEquals("2", binding.getSystemUserId());
		assertEquals("TOKEN", binding.getAuthType());
		assertNull(binding.getCredentials());
		assertEquals("active", binding.getStatus());
	}

	@Test
	void shouldSkipWhenBindEndpointNotConfigured() {
		SystemIdentityBindingService service = new SystemIdentityBindingService(
				jdbcTemplate,
				identityBindingMapper,
				new ObjectMapper(),
				restTemplate);
		when(jdbcTemplate.queryForList(anyString(), eq("gougu_oa"))).thenReturn(List.of(Map.of(
				"base_url", "http://office.ai.devefive.com",
				"bind_endpoint", "",
				"bind_method", "POST",
				"bind_request", "{}",
				"bind_response", "{}")));

		service.ensureBound("1", "gougu_oa", "admin", "admin123");

		verify(restTemplate, never()).exchange(anyString(), any(), any(), eq(String.class));
		verify(identityBindingMapper, never()).insert(org.mockito.ArgumentMatchers.<IdentityBinding>any());
		verify(identityBindingMapper, never()).updateById(org.mockito.ArgumentMatchers.<IdentityBinding>any());
	}
}

