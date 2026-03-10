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
import com.alibaba.assistant.agent.controlplane.identity.LocalUserGrantService;
import com.alibaba.assistant.agent.controlplane.identity.mapper.LocalUserAccountMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MigrationAuthServiceAuthorizationTest {

    @Mock
    private LocalUserAccountMapper localUserAccountMapper;

    @Mock
    private LocalUserGrantService localUserGrantService;

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
                localUserGrantService,
                stringRedisTemplate,
                new ObjectMapper(),
                "assistant-agent",
                "gougu_oa",
                7200,
                86400,
                "assistant:auth:test");
        redisSessionStore.clear();
        stubRedisSessionStoreReadWrite();
    }

    @Test
    void shouldIncludeGrantedRolesAndPermissionsWhenIntrospectingSession() {
        when(localUserAccountMapper.selectOne(any())).thenReturn(buildUser(1001L, "admin", "admin123", 1L, "gougu_oa"));
        when(localUserGrantService.findRoles(1001L)).thenReturn(List.of("assistant_user", "assistant_controlplane_admin"));
        when(localUserGrantService.findPermissions(1001L))
                .thenReturn(List.of("assistant:chat", "assistant:controlplane"));

        LoginResult loginResult = authService.login("admin", "admin123", 1L, "gougu_oa");
        AuthenticatedUserContext context = authService.introspect(loginResult.accessToken()).orElseThrow();

        assertEquals(List.of("assistant_user", "assistant_controlplane_admin"), context.roles());
        assertEquals(List.of("assistant:chat", "assistant:controlplane"), context.permissions());
    }

    private void stubRedisSessionStoreReadWrite() {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        doAnswer(invocation -> {
            String key = invocation.getArgument(0);
            String value = invocation.getArgument(1);
            redisSessionStore.put(key, value);
            return null;
        }).when(valueOperations).set(anyString(), anyString(), any(Duration.class));
        when(valueOperations.get(anyString())).thenAnswer(invocation -> redisSessionStore.get(invocation.getArgument(0)));
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
