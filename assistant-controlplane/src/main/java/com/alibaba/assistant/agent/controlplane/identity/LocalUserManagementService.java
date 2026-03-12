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
import com.alibaba.assistant.agent.controlplane.support.ManagementKeywordMatcher;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

/**
 * Typed control-plane facade for migration local-user management.
 */
@Service
public class LocalUserManagementService {

    private static final String DEFAULT_ENVIRONMENT = "prod";

    private static final String DEFAULT_SYSTEM_CODE = "gougu_oa";

    private static final String DEFAULT_STATUS = "active";

    private final PlatformSpaceService platformSpaceService;

    private final LocalUserAccountMapper localUserAccountMapper;

    public LocalUserManagementService(
            PlatformSpaceService platformSpaceService,
            LocalUserAccountMapper localUserAccountMapper) {
        this.platformSpaceService = platformSpaceService;
        this.localUserAccountMapper = localUserAccountMapper;
    }

    public List<ResolvedLocalUserManagementView> listLocalUsers(String spaceCode, String environment, String keyword) {
        Optional<SpaceResolution> resolution = resolveSpace(spaceCode, environment);
        if (resolution.isEmpty()) {
            return List.of();
        }
        String normalizedKeyword = ManagementKeywordMatcher.normalizeKeyword(keyword);
        LambdaQueryWrapper<LocalUserAccount> query = new LambdaQueryWrapper<>();
        query.orderByDesc(LocalUserAccount::getUpdatedAt);
        query.orderByDesc(LocalUserAccount::getId);
        return localUserAccountMapper.selectList(query).stream()
                .filter(account -> StringUtils.hasText(account.getUsername()))
                .map(account -> toResolved(resolution.get(), account))
                .filter(view -> ManagementKeywordMatcher.matches(
                        normalizedKeyword,
                        view.username(),
                        view.displayName(),
                        view.systemCode(),
                        view.status(),
                        view.localUserId() == null ? null : String.valueOf(view.localUserId()),
                        view.tenantId() == null ? null : String.valueOf(view.tenantId())))
                .toList();
    }

    public Optional<ResolvedLocalUserManagementView> getLocalUser(
            String spaceCode,
            String environment,
            String username,
            String systemCode) {
        Optional<SpaceResolution> resolution = resolveSpace(spaceCode, environment);
        String normalizedUsername = normalize(username);
        if (resolution.isEmpty() || !StringUtils.hasText(normalizedUsername)) {
            return Optional.empty();
        }
        return Optional.ofNullable(localUserAccountMapper.selectOne(buildUserQuery(normalizedUsername, normalizeSystemCode(systemCode))))
                .map(account -> toResolved(resolution.get(), account));
    }

    public Optional<ResolvedLocalUserManagementView> upsertLocalUser(
            String spaceCode,
            String environment,
            String username,
            LocalUserUpsertCommand command) {
        Optional<SpaceResolution> resolution = resolveSpace(spaceCode, environment);
        String normalizedUsername = normalize(username);
        if (resolution.isEmpty() || !StringUtils.hasText(normalizedUsername) || command == null) {
            return Optional.empty();
        }
        String normalizedSystemCode = normalizeSystemCode(command.systemCode());
        LocalUserAccount existing = localUserAccountMapper.selectOne(buildUserQuery(normalizedUsername, normalizedSystemCode));
        boolean creating = existing == null;
        if (creating && !StringUtils.hasText(command.password())) {
            return Optional.empty();
        }

        LocalUserAccount target = creating ? new LocalUserAccount() : existing;
        LocalDateTime now = LocalDateTime.now();
        if (creating) {
            target.setCreatedAt(now);
        }
        target.setUsername(normalizedUsername);
        target.setDisplayName(resolveDisplayName(command.displayName(), target.getDisplayName(), normalizedUsername));
        target.setTenantId(command.tenantId() != null ? command.tenantId() : target.getTenantId());
        target.setSystemCode(normalizedSystemCode);
        target.setStatus(resolveStatus(command.status(), target.getStatus()));
        if (StringUtils.hasText(command.password())) {
            target.setPasswordHash(sha256Hex(command.password()));
        }
        target.setUpdatedAt(now);

        int affected = creating
                ? localUserAccountMapper.insert(target)
                : localUserAccountMapper.updateById(target);
        if (affected <= 0) {
            return Optional.empty();
        }
        return Optional.of(toResolved(resolution.get(), target));
    }

    private Optional<SpaceResolution> resolveSpace(String spaceCode, String environment) {
        String normalizedSpaceCode = normalize(spaceCode);
        String normalizedEnvironment = normalizeEnvironment(environment);
        if (!StringUtils.hasText(normalizedSpaceCode)) {
            return Optional.empty();
        }
        return platformSpaceService.findActiveByCode(normalizedSpaceCode, normalizedEnvironment)
                .map(space -> new SpaceResolution(space, normalizedEnvironment));
    }

    private LambdaQueryWrapper<LocalUserAccount> buildUserQuery(String username, String systemCode) {
        LambdaQueryWrapper<LocalUserAccount> query = new LambdaQueryWrapper<>();
        query.eq(LocalUserAccount::getUsername, username);
        query.eq(LocalUserAccount::getSystemCode, systemCode);
        query.orderByDesc(LocalUserAccount::getId);
        query.last("LIMIT 1");
        return query;
    }

    private ResolvedLocalUserManagementView toResolved(SpaceResolution resolution, LocalUserAccount account) {
        return new ResolvedLocalUserManagementView(
                account.getId(),
                resolution.space().getSpaceCode(),
                resolution.environment(),
                account.getUsername(),
                resolveDisplayName(null, account.getDisplayName(), account.getUsername()),
                account.getTenantId(),
                normalizeSystemCode(account.getSystemCode()),
                resolveStatus(account.getStatus(), DEFAULT_STATUS));
    }

    private String normalizeEnvironment(String environment) {
        return StringUtils.hasText(environment) ? environment.trim() : DEFAULT_ENVIRONMENT;
    }

    private String normalizeSystemCode(String systemCode) {
        return StringUtils.hasText(systemCode) ? systemCode.trim() : DEFAULT_SYSTEM_CODE;
    }

    private String normalize(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private String resolveDisplayName(String requestedDisplayName, String existingDisplayName, String username) {
        if (StringUtils.hasText(requestedDisplayName)) {
            return requestedDisplayName.trim();
        }
        if (StringUtils.hasText(existingDisplayName)) {
            return existingDisplayName.trim();
        }
        return username;
    }

    private String resolveStatus(String requestedStatus, String existingStatus) {
        if (StringUtils.hasText(requestedStatus)) {
            return requestedStatus.trim().toLowerCase();
        }
        if (StringUtils.hasText(existingStatus)) {
            return existingStatus.trim().toLowerCase();
        }
        return DEFAULT_STATUS;
    }

    private String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        }
        catch (Exception ex) {
            throw new IllegalStateException("Failed to hash local-user password", ex);
        }
    }

    private record SpaceResolution(PlatformSpace space, String environment) {
    }
}
