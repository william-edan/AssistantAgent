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
package com.alibaba.assistant.agent.api.controller;

import com.alibaba.assistant.agent.api.security.MigrationAuthService;
import com.alibaba.assistant.agent.api.security.dto.LoginResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class SystemAuthProxyControllerTest {

	private MockMvc mockMvc;

	@Mock
	private MigrationAuthService migrationAuthService;

	@BeforeEach
	void setUp() {
		SystemAuthProxyController controller = new SystemAuthProxyController(migrationAuthService);
		mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
	}

	@Test
	void shouldLoginByMigrationAuthService() throws Exception {
		LoginResult result = new LoginResult(
				"access-token-x",
				"refresh-token-x",
				Instant.parse("2026-03-06T10:00:00Z"),
				"1001");
		when(migrationAuthService.login(eq("admin"), eq("admin123"), eq(1L), eq("gougu_oa"))).thenReturn(result);

		mockMvc.perform(post("/system/auth/login")
						.header("tenant-id", "1")
						.contentType("application/json")
						.content("""
								{
								  "username": "admin",
								  "password": "admin123",
								  "systemCode": "gougu_oa"
								}
								"""))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.code").value(0))
				.andExpect(jsonPath("$.data.accessToken").value("access-token-x"))
				.andExpect(jsonPath("$.data.refreshToken").value("refresh-token-x"))
				.andExpect(jsonPath("$.data.userId").value("1001"));
	}

	@Test
	void shouldRefreshTokenByMigrationAuthService() throws Exception {
		LoginResult result = new LoginResult(
				"new-access-token",
				"new-refresh-token",
				Instant.parse("2026-03-06T12:00:00Z"),
				"1001");
		when(migrationAuthService.refresh(eq("old-refresh-token"))).thenReturn(result);

		mockMvc.perform(post("/system/auth/refresh-token")
						.param("refreshToken", "old-refresh-token"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.code").value(0))
				.andExpect(jsonPath("$.data.accessToken").value("new-access-token"))
				.andExpect(jsonPath("$.data.refreshToken").value("new-refresh-token"))
				.andExpect(jsonPath("$.data.userId").value("1001"));
	}

	@Test
	void shouldGetPermissionInfoByMigrationAuthService() throws Exception {
		when(migrationAuthService.getPermissionInfo(eq("access-token-x"))).thenReturn(Map.of(
				"user",
				Map.of("id", "1001", "username", "admin", "systemCode", "gougu_oa"),
				"roles",
				List.of("assistant_user"),
				"permissions",
				List.of("assistant:chat")));

		mockMvc.perform(get("/system/auth/get-permission-info")
						.header(HttpHeaders.AUTHORIZATION, "Bearer access-token-x"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.code").value(0))
				.andExpect(jsonPath("$.data.user.id").value("1001"))
				.andExpect(jsonPath("$.data.roles[0]").value("assistant_user"));
	}

	@Test
	void shouldLogoutByMigrationAuthService() throws Exception {
		mockMvc.perform(post("/system/auth/logout")
						.header(HttpHeaders.AUTHORIZATION, "Bearer access-token-x")
						.param("refreshToken", "refresh-token-x"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.code").value(0));

		ArgumentCaptor<String> accessTokenCaptor = ArgumentCaptor.forClass(String.class);
		ArgumentCaptor<String> refreshTokenCaptor = ArgumentCaptor.forClass(String.class);
		verify(migrationAuthService).logout(accessTokenCaptor.capture(), refreshTokenCaptor.capture());
		assertEquals("access-token-x", accessTokenCaptor.getValue());
		assertEquals("refresh-token-x", refreshTokenCaptor.getValue());
	}

}
