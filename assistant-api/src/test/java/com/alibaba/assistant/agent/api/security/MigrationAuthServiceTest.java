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

import com.alibaba.assistant.agent.api.security.dto.LoginResult;
import com.alibaba.assistant.agent.controlplane.identity.LocalUserAccount;
import com.alibaba.assistant.agent.controlplane.identity.mapper.LocalUserAccountMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MigrationAuthServiceTest {

	@Mock
	private LocalUserAccountMapper localUserAccountMapper;

	@Mock
	private StringRedisTemplate stringRedisTemplate;

	@Mock
	private ValueOperations<String, String> valueOperations;

	private MigrationAuthService authService;

	private final Map<String, String> redisSessionStore = new LinkedHashMap<>();

	@BeforeEach
	void setUp() {
		authService = new MigrationAuthService(
				localUserAccountMapper,
				stringRedisTemplate,
				new ObjectMapper(),
				"assistant-agent",
				"gougu_oa",
				7200,
				86400,
				"assistant:auth:test");
		redisSessionStore.clear();
	}

	@Test
	void shouldLoginAndCreateSessionWhenLocalUserValid() {
		stubRedisSessionStoreWriteOnly();
		when(localUserAccountMapper.selectOne(any())).thenReturn(buildUser(1001L, "admin", "admin123", 1L, "gougu_oa"));

		LoginResult result = authService.login("admin", "admin123", 1L, "gougu_oa");

		assertTrue(result.accessToken().startsWith("atk_"));
		assertTrue(result.refreshToken().startsWith("rtk_"));
		assertEquals("1001", result.userId());
		assertTrue(redisSessionStore.containsKey("assistant:auth:test:atk:" + result.accessToken()));
		assertTrue(redisSessionStore.containsKey("assistant:auth:test:rtk:" + result.refreshToken()));
	}

	@Test
	void shouldRejectLoginWhenPasswordInvalid() {
		when(localUserAccountMapper.selectOne(any())).thenReturn(buildUser(1001L, "admin", "admin123", 1L, "gougu_oa"));

		ResponseStatusException error = assertThrows(ResponseStatusException.class,
				() -> authService.login("admin", "bad-password", 1L, "gougu_oa"));
		assertEquals(401, error.getStatusCode().value());
	}

	@Test
	void shouldRefreshSessionTokenWhenRefreshTokenValid() {
		stubRedisSessionStoreReadWrite();
		when(localUserAccountMapper.selectOne(any())).thenReturn(buildUser(1001L, "admin", "admin123", 1L, "gougu_oa"));

		LoginResult loginResult = authService.login("admin", "admin123", 1L, "gougu_oa");
		LoginResult refreshResult = authService.refresh(loginResult.refreshToken());

		assertNotEquals(loginResult.accessToken(), refreshResult.accessToken());
		assertEquals(loginResult.refreshToken(), refreshResult.refreshToken());
		assertEquals("1001", refreshResult.userId());
	}

	@Test
	void shouldIntrospectAndLogoutAccessToken() {
		stubRedisSessionStoreReadWrite();
		when(localUserAccountMapper.selectOne(any())).thenReturn(buildUser(1001L, "admin", "admin123", 1L, "gougu_oa"));

		LoginResult loginResult = authService.login("admin", "admin123", 1L, "gougu_oa");
		Optional<AuthenticatedUserContext> beforeLogout = authService.introspect(loginResult.accessToken());
		assertTrue(beforeLogout.isPresent());
		assertEquals("1001", beforeLogout.get().userId());

		authService.logout(loginResult.accessToken(), loginResult.refreshToken());
		Optional<AuthenticatedUserContext> afterLogout = authService.introspect(loginResult.accessToken());
		assertTrue(afterLogout.isEmpty());
	}

	private void stubRedisSessionStoreReadWrite() {
		stubRedisSessionStoreWriteOnly();
		when(valueOperations.get(anyString())).thenAnswer(invocation ->
				redisSessionStore.get(invocation.getArgument(0)));
		when(stringRedisTemplate.delete(anyString())).thenAnswer(invocation ->
				redisSessionStore.remove(invocation.getArgument(0)) != null);
	}

	private void stubRedisSessionStoreWriteOnly() {
		when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
		doAnswer(invocation -> {
			String key = invocation.getArgument(0);
			String value = invocation.getArgument(1);
			redisSessionStore.put(key, value);
			return null;
		}).when(valueOperations).set(anyString(), anyString(), any(Duration.class));
	}

	private LocalUserAccount buildUser(Long id, String username, String rawPassword, Long tenantId, String systemCode) {
		LocalUserAccount user = new LocalUserAccount();
		user.setId(id);
		user.setUsername(username);
		user.setPasswordHash(sha256(rawPassword));
		user.setDisplayName("管理员");
		user.setTenantId(tenantId);
		user.setSystemCode(systemCode);
		user.setStatus("active");
		return user;
	}

	private String sha256(String value) {
		try {
			MessageDigest messageDigest = MessageDigest.getInstance("SHA-256");
			return HexFormat.of().formatHex(messageDigest.digest(value.getBytes(StandardCharsets.UTF_8)));
		}
		catch (Exception e) {
			throw new IllegalStateException(e);
		}
	}

}
