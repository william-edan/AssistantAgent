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
package com.alibaba.assistant.agent.controlplane.identity;

import com.alibaba.assistant.agent.controlplane.identity.mapper.LocalUserAccountMapper;
import com.alibaba.assistant.agent.controlplane.space.PlatformSpace;
import com.alibaba.assistant.agent.controlplane.space.PlatformSpaceService;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LocalUserManagementServiceTest {

    @Mock
    private PlatformSpaceService platformSpaceService;

    @Mock
    private LocalUserAccountMapper localUserAccountMapper;

    private LocalUserManagementService service;

    @BeforeEach
    void setUp() {
        service = new LocalUserManagementService(platformSpaceService, localUserAccountMapper);
    }

    @Test
    void shouldListLocalUsersByKeywordUnderSpace() {
        when(platformSpaceService.findActiveByCode("enterprise-default", "prod"))
                .thenReturn(Optional.of(space(9L, "enterprise-default", "prod")));
        when(localUserAccountMapper.selectList(any(Wrapper.class)))
                .thenReturn(List.of(
                        localUser(1001L, "admin", "管理员", 1L, "gougu_oa", "active", "hash-a"),
                        localUser(1002L, "ops", "运维", 1L, "legacy", "disabled", "hash-b")));

        List<ResolvedLocalUserManagementView> resolved = service.listLocalUsers("enterprise-default", "prod", "admin");

        assertEquals(1, resolved.size());
        assertEquals(1001L, resolved.get(0).localUserId());
        assertEquals("admin", resolved.get(0).username());
    }

    @Test
    void shouldGetLocalUserByUsernameAndSystemCode() {
        when(platformSpaceService.findActiveByCode("enterprise-default", "prod"))
                .thenReturn(Optional.of(space(9L, "enterprise-default", "prod")));
        when(localUserAccountMapper.selectOne(any(Wrapper.class)))
                .thenReturn(localUser(1001L, "admin", "管理员", 1L, "gougu_oa", "active", "hash-a"));

        ResolvedLocalUserManagementView resolved = service
                .getLocalUser("enterprise-default", "prod", "admin", "gougu_oa")
                .orElseThrow();

        assertEquals(1001L, resolved.localUserId());
        assertEquals("gougu_oa", resolved.systemCode());
    }

    @Test
    void shouldCreateLocalUserWithPasswordHash() {
        when(platformSpaceService.findActiveByCode("enterprise-default", "prod"))
                .thenReturn(Optional.of(space(9L, "enterprise-default", "prod")));
        when(localUserAccountMapper.selectOne(any(Wrapper.class))).thenReturn(null);
        when(localUserAccountMapper.insert(any(LocalUserAccount.class))).thenAnswer(invocation -> {
            LocalUserAccount account = invocation.getArgument(0);
            account.setId(1001L);
            return 1;
        });

        ResolvedLocalUserManagementView resolved = service
                .upsertLocalUser(
                        "enterprise-default",
                        "prod",
                        "admin",
                        new LocalUserUpsertCommand("管理员", "admin123", 1L, "gougu_oa", "active"))
                .orElseThrow();

        assertEquals(1001L, resolved.localUserId());
        assertEquals("admin", resolved.username());

        ArgumentCaptor<LocalUserAccount> accountCaptor = ArgumentCaptor.forClass(LocalUserAccount.class);
        verify(localUserAccountMapper).insert(accountCaptor.capture());
        assertEquals(sha256Hex("admin123"), accountCaptor.getValue().getPasswordHash());
        assertEquals("管理员", accountCaptor.getValue().getDisplayName());
    }

    @Test
    void shouldPreserveExistingPasswordHashWhenUpdatingWithoutPassword() {
        LocalUserAccount existing = localUser(1001L, "admin", "管理员", 1L, "gougu_oa", "active", "keep-hash");
        when(platformSpaceService.findActiveByCode("enterprise-default", "prod"))
                .thenReturn(Optional.of(space(9L, "enterprise-default", "prod")));
        when(localUserAccountMapper.selectOne(any(Wrapper.class))).thenReturn(existing);
        when(localUserAccountMapper.updateById(any(LocalUserAccount.class))).thenReturn(1);

        ResolvedLocalUserManagementView resolved = service
                .upsertLocalUser(
                        "enterprise-default",
                        "prod",
                        "admin",
                        new LocalUserUpsertCommand("管理员-新", null, 2L, "gougu_oa", "inactive"))
                .orElseThrow();

        assertEquals("inactive", resolved.status());

        ArgumentCaptor<LocalUserAccount> accountCaptor = ArgumentCaptor.forClass(LocalUserAccount.class);
        verify(localUserAccountMapper).updateById(accountCaptor.capture());
        assertEquals("keep-hash", accountCaptor.getValue().getPasswordHash());
        assertEquals("管理员-新", accountCaptor.getValue().getDisplayName());
        assertEquals(2L, accountCaptor.getValue().getTenantId());
    }

    private PlatformSpace space(Long id, String code, String environment) {
        PlatformSpace space = new PlatformSpace();
        space.setId(id);
        space.setSpaceCode(code);
        space.setEnvironment(environment);
        space.setStatus("active");
        return space;
    }

    private LocalUserAccount localUser(
            Long id,
            String username,
            String displayName,
            Long tenantId,
            String systemCode,
            String status,
            String passwordHash) {
        LocalUserAccount user = new LocalUserAccount();
        user.setId(id);
        user.setUsername(username);
        user.setDisplayName(displayName);
        user.setTenantId(tenantId);
        user.setSystemCode(systemCode);
        user.setStatus(status);
        user.setPasswordHash(passwordHash);
        return user;
    }

    private String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        }
        catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
