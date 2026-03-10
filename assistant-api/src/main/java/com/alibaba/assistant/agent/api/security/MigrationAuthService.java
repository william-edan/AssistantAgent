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
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Pure local login/auth service backed by local_user_account table and Redis sessions.
 *
 * @author Assistant Agent Team
 * @since 1.0.0
 */
@Service
@Profile("migration")
public class MigrationAuthService {

	private static final List<String> DEFAULT_ROLES = List.of("assistant_user");

	private static final List<String> DEFAULT_PERMISSIONS = List.of(AuthenticatedUserAuthorityMapper.PERMISSION_CHAT);

	private static final String STATUS_ACTIVE = "active";

	private static final String DEFAULT_REDIS_KEY_PREFIX = "assistant:auth:session";

	private final LocalUserAccountMapper localUserAccountMapper;

	private final LocalUserGrantService localUserGrantService;

	private final StringRedisTemplate stringRedisTemplate;

	private final ObjectMapper objectMapper;

	private final String defaultClientId;

	private final String defaultSystemCode;

	private final long accessTokenTtlSeconds;

	private final long refreshTokenTtlSeconds;

	private final String redisKeyPrefix;

	public MigrationAuthService(
			LocalUserAccountMapper localUserAccountMapper,
			LocalUserGrantService localUserGrantService,
			StringRedisTemplate stringRedisTemplate,
			ObjectMapper objectMapper,
			@Value("${assistant.auth.local.client-id:assistant-agent}") String defaultClientId,
			@Value("${assistant.auth.current-system.default-system-code:gougu_oa}") String defaultSystemCode,
			@Value("${assistant.auth.local.access-token-ttl-seconds:7200}") long accessTokenTtlSeconds,
			@Value("${assistant.auth.local.refresh-token-ttl-seconds:604800}") long refreshTokenTtlSeconds,
			@Value("${assistant.auth.local.redis.key-prefix:" + DEFAULT_REDIS_KEY_PREFIX + "}") String redisKeyPrefix) {
		this.localUserAccountMapper = localUserAccountMapper;
		this.localUserGrantService = localUserGrantService;
		this.stringRedisTemplate = stringRedisTemplate;
		this.objectMapper = objectMapper;
		this.defaultClientId = defaultClientId;
		this.defaultSystemCode = defaultSystemCode;
		this.accessTokenTtlSeconds = Math.max(accessTokenTtlSeconds, 300L);
		this.refreshTokenTtlSeconds = Math.max(refreshTokenTtlSeconds, this.accessTokenTtlSeconds);
		this.redisKeyPrefix = normalizeRedisKeyPrefix(redisKeyPrefix);
	}

	public LoginResult login(String username, String password, @Nullable Long tenantId, @Nullable String systemCode) {
		if (!StringUtils.hasText(username) || !StringUtils.hasText(password)) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "username_or_password_missing");
		}
		String effectiveSystemCode = StringUtils.hasText(systemCode) ? systemCode.trim() : defaultSystemCode;
		if (!StringUtils.hasText(effectiveSystemCode)) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "system_code_missing");
		}
		LocalUserAccount user = localUserAccountMapper.selectOne(
				new LambdaQueryWrapper<LocalUserAccount>()
						.eq(LocalUserAccount::getUsername, username.trim())
						.eq(LocalUserAccount::getSystemCode, effectiveSystemCode)
						.eq(LocalUserAccount::getStatus, STATUS_ACTIVE)
						.last("LIMIT 1"));
		if (user == null) {
			throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid_username_or_password");
		}
		String expectedHash = user.getPasswordHash();
		String actualHash = sha256Hex(password);
		if (!StringUtils.hasText(expectedHash) || !expectedHash.equalsIgnoreCase(actualHash)) {
			throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid_username_or_password");
		}
		String assistantUid = String.valueOf(user.getId());
		Long effectiveTenantId = user.getTenantId() != null ? user.getTenantId() : tenantId;
		return issueSession(
				assistantUid,
				user.getUsername(),
				firstNonBlank(user.getDisplayName(), user.getUsername(), assistantUid),
				effectiveTenantId,
				effectiveSystemCode);
	}

	public LoginResult refresh(String refreshToken) {
		if (!StringUtils.hasText(refreshToken)) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "refresh_token_missing");
		}
		long nowEpochSecond = Instant.now().getEpochSecond();
		SessionRecord session = loadByRefreshToken(refreshToken)
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid_refresh_token"));
		if (!STATUS_ACTIVE.equalsIgnoreCase(session.getStatus())
				|| session.getRefreshExpiresAtEpochSecond() <= nowEpochSecond) {
			evictSession(session);
			throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid_refresh_token");
		}
		String oldAccessToken = session.getAccessToken();
		String newAccessToken = newTokenValue("atk");
		long accessExpiresAtEpochSecond = nowEpochSecond + accessTokenTtlSeconds;
		long remainingRefreshTtl = session.getRefreshExpiresAtEpochSecond() - nowEpochSecond;
		if (remainingRefreshTtl <= 0L) {
			evictSession(session);
			throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid_refresh_token");
		}
		session.setAccessToken(newAccessToken);
		session.setAccessExpiresAtEpochSecond(accessExpiresAtEpochSecond);
		persistSession(session, accessTokenTtlSeconds, remainingRefreshTtl);
		if (StringUtils.hasText(oldAccessToken) && !oldAccessToken.equals(newAccessToken)) {
			stringRedisTemplate.delete(accessTokenKey(oldAccessToken));
		}
		return new LoginResult(
				newAccessToken,
				session.getRefreshToken(),
				toInstant(accessExpiresAtEpochSecond),
				session.getAssistantUid());
	}

	public Optional<AuthenticatedUserContext> introspect(String accessToken) {
		if (!StringUtils.hasText(accessToken)) {
			return Optional.empty();
		}
		long nowEpochSecond = Instant.now().getEpochSecond();
		SessionRecord session = loadByAccessToken(accessToken).orElse(null);
		if (session == null) {
			return Optional.empty();
		}
		if (!STATUS_ACTIVE.equalsIgnoreCase(session.getStatus())) {
			evictSession(session);
			return Optional.empty();
		}
		if (session.getAccessExpiresAtEpochSecond() <= nowEpochSecond) {
			stringRedisTemplate.delete(accessTokenKey(accessToken));
			return Optional.empty();
		}
		List<String> roles = resolveRoles(session.getAssistantUid());
		List<String> permissions = resolvePermissions(session.getAssistantUid());
		return Optional.of(new AuthenticatedUserContext(
				session.getAssistantUid(),
				session.getTenantId(),
				session.getSystemCode(),
				defaultClientId,
				accessToken,
				session.getUsername(),
				session.getDisplayName(),
				roles,
				permissions));
	}

	public Map<String, Object> getPermissionInfo(String accessToken) {
		AuthenticatedUserContext context = introspect(accessToken)
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid_access_token"));
		Map<String, Object> user = new LinkedHashMap<>();
		user.put("id", context.userId());
		user.put("username", firstNonBlank(context.username(), context.userId()));
		user.put("nickname", firstNonBlank(context.displayName(), context.username(), context.userId()));
		user.put("tenantId", context.tenantId());
		user.put("systemCode", context.systemCode());
		return Map.of(
				"user",
				user,
				"roles",
				context.roles(),
				"permissions",
				context.permissions());
	}

	public void logout(@Nullable String accessToken, @Nullable String refreshToken) {
		if (StringUtils.hasText(accessToken)) {
			revokeByAccessToken(accessToken);
		}
		if (StringUtils.hasText(refreshToken)) {
			revokeByRefreshToken(refreshToken);
		}
	}

	private LoginResult issueSession(
			String assistantUid,
			String username,
			String displayName,
			@Nullable Long tenantId,
			String systemCode) {
		long nowEpochSecond = Instant.now().getEpochSecond();
		long accessExpiresAtEpochSecond = nowEpochSecond + accessTokenTtlSeconds;
		long refreshExpiresAtEpochSecond = nowEpochSecond + refreshTokenTtlSeconds;

		SessionRecord session = new SessionRecord();
		session.setAccessToken(newTokenValue("atk"));
		session.setRefreshToken(newTokenValue("rtk"));
		session.setAssistantUid(assistantUid);
		session.setUsername(username);
		session.setDisplayName(displayName);
		session.setTenantId(tenantId);
		session.setSystemCode(systemCode);
		session.setAccessExpiresAtEpochSecond(accessExpiresAtEpochSecond);
		session.setRefreshExpiresAtEpochSecond(refreshExpiresAtEpochSecond);
		session.setStatus(STATUS_ACTIVE);
		persistSession(session, accessTokenTtlSeconds, refreshTokenTtlSeconds);
		return new LoginResult(
				session.getAccessToken(),
				session.getRefreshToken(),
				toInstant(accessExpiresAtEpochSecond),
				assistantUid);
	}

	private Optional<SessionRecord> loadByAccessToken(String accessToken) {
		if (!StringUtils.hasText(accessToken)) {
			return Optional.empty();
		}
		return readSession(accessTokenKey(accessToken))
				.filter(session -> accessToken.equals(session.getAccessToken()));
	}

	private Optional<SessionRecord> loadByRefreshToken(String refreshToken) {
		if (!StringUtils.hasText(refreshToken)) {
			return Optional.empty();
		}
		return readSession(refreshTokenKey(refreshToken))
				.filter(session -> refreshToken.equals(session.getRefreshToken()));
	}

	private Optional<SessionRecord> readSession(String redisKey) {
		try {
			String raw = stringRedisTemplate.opsForValue().get(redisKey);
			if (!StringUtils.hasText(raw)) {
				return Optional.empty();
			}
			SessionRecord session = objectMapper.readValue(raw, SessionRecord.class);
			return Optional.of(session);
		}
		catch (Exception ex) {
			stringRedisTemplate.delete(redisKey);
			return Optional.empty();
		}
	}

	private void persistSession(SessionRecord session, long accessTtlSeconds, long refreshTtlSeconds) {
		String payload;
		try {
			payload = objectMapper.writeValueAsString(session);
		}
		catch (Exception ex) {
			throw new IllegalStateException("failed_to_serialize_auth_session", ex);
		}
		stringRedisTemplate.opsForValue().set(
				accessTokenKey(session.getAccessToken()),
				payload,
				Duration.ofSeconds(Math.max(accessTtlSeconds, 1L)));
		stringRedisTemplate.opsForValue().set(
				refreshTokenKey(session.getRefreshToken()),
				payload,
				Duration.ofSeconds(Math.max(refreshTtlSeconds, 1L)));
	}

	private void revokeByAccessToken(String accessToken) {
		Optional<SessionRecord> session = loadByAccessToken(accessToken);
		if (session.isPresent()) {
			evictSession(session.get());
			return;
		}
		stringRedisTemplate.delete(accessTokenKey(accessToken));
	}

	private void revokeByRefreshToken(String refreshToken) {
		Optional<SessionRecord> session = loadByRefreshToken(refreshToken);
		if (session.isPresent()) {
			evictSession(session.get());
			return;
		}
		stringRedisTemplate.delete(refreshTokenKey(refreshToken));
	}

	private void evictSession(SessionRecord session) {
		if (session == null) {
			return;
		}
		if (StringUtils.hasText(session.getAccessToken())) {
			stringRedisTemplate.delete(accessTokenKey(session.getAccessToken()));
		}
		if (StringUtils.hasText(session.getRefreshToken())) {
			stringRedisTemplate.delete(refreshTokenKey(session.getRefreshToken()));
		}
	}

	private List<String> resolveRoles(String assistantUid) {
		return mergeDefaultGrants(loadGrantCodes(assistantUid, true), DEFAULT_ROLES);
	}

	private List<String> resolvePermissions(String assistantUid) {
		return mergeDefaultGrants(loadGrantCodes(assistantUid, false), DEFAULT_PERMISSIONS);
	}

	private List<String> loadGrantCodes(String assistantUid, boolean roles) {
		Long localUserId = parseLocalUserId(assistantUid);
		if (localUserId == null) {
			return List.of();
		}
		List<String> grants = roles
				? localUserGrantService.findRoles(localUserId)
				: localUserGrantService.findPermissions(localUserId);
		return grants == null ? List.of() : grants;
	}

	private List<String> mergeDefaultGrants(List<String> resolvedGrants, List<String> defaults) {
		LinkedHashSet<String> merged = new LinkedHashSet<>();
		if (defaults != null) {
			merged.addAll(defaults);
		}
		if (resolvedGrants != null) {
			for (String value : resolvedGrants) {
				if (StringUtils.hasText(value)) {
					merged.add(value.trim());
				}
			}
		}
		return List.copyOf(merged);
	}

	private Long parseLocalUserId(String assistantUid) {
		if (!StringUtils.hasText(assistantUid)) {
			return null;
		}
		try {
			return Long.parseLong(assistantUid.trim());
		}
		catch (NumberFormatException ignored) {
			return null;
		}
	}

	private String sha256Hex(String raw) {
		try {
			MessageDigest messageDigest = MessageDigest.getInstance("SHA-256");
			byte[] digest = messageDigest.digest(raw.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(digest);
		}
		catch (Exception e) {
			throw new IllegalStateException("sha256 not available", e);
		}
	}

	private String newTokenValue(String prefix) {
		return prefix + "_" + UUID.randomUUID().toString().replace("-", "");
	}

	private Instant toInstant(long epochSecond) {
		return Instant.ofEpochSecond(epochSecond);
	}

	private String accessTokenKey(String accessToken) {
		return redisKeyPrefix + ":atk:" + accessToken;
	}

	private String refreshTokenKey(String refreshToken) {
		return redisKeyPrefix + ":rtk:" + refreshToken;
	}

	private String normalizeRedisKeyPrefix(@Nullable String rawPrefix) {
		String prefix = StringUtils.hasText(rawPrefix) ? rawPrefix.trim() : DEFAULT_REDIS_KEY_PREFIX;
		if (!StringUtils.hasText(prefix)) {
			return DEFAULT_REDIS_KEY_PREFIX;
		}
		while (prefix.endsWith(":")) {
			prefix = prefix.substring(0, prefix.length() - 1);
		}
		return StringUtils.hasText(prefix) ? prefix : DEFAULT_REDIS_KEY_PREFIX;
	}

	@SafeVarargs
	private final <T> T firstNonBlank(T... values) {
		for (T value : values) {
			if (value instanceof String text) {
				if (StringUtils.hasText(text)) {
					return value;
				}
				continue;
			}
			if (value != null) {
				return value;
			}
		}
		return null;
	}

	private static final class SessionRecord {

		private String accessToken;

		private String refreshToken;

		private String assistantUid;

		private String username;

		private String displayName;

		private Long tenantId;

		private String systemCode;

		private String status;

		private long accessExpiresAtEpochSecond;

		private long refreshExpiresAtEpochSecond;

		public String getAccessToken() {
			return accessToken;
		}

		public void setAccessToken(String accessToken) {
			this.accessToken = accessToken;
		}

		public String getRefreshToken() {
			return refreshToken;
		}

		public void setRefreshToken(String refreshToken) {
			this.refreshToken = refreshToken;
		}

		public String getAssistantUid() {
			return assistantUid;
		}

		public void setAssistantUid(String assistantUid) {
			this.assistantUid = assistantUid;
		}

		public String getUsername() {
			return username;
		}

		public void setUsername(String username) {
			this.username = username;
		}

		public String getDisplayName() {
			return displayName;
		}

		public void setDisplayName(String displayName) {
			this.displayName = displayName;
		}

		public Long getTenantId() {
			return tenantId;
		}

		public void setTenantId(Long tenantId) {
			this.tenantId = tenantId;
		}

		public String getSystemCode() {
			return systemCode;
		}

		public void setSystemCode(String systemCode) {
			this.systemCode = systemCode;
		}

		public String getStatus() {
			return status;
		}

		public void setStatus(String status) {
			this.status = status;
		}

		public long getAccessExpiresAtEpochSecond() {
			return accessExpiresAtEpochSecond;
		}

		public void setAccessExpiresAtEpochSecond(long accessExpiresAtEpochSecond) {
			this.accessExpiresAtEpochSecond = accessExpiresAtEpochSecond;
		}

		public long getRefreshExpiresAtEpochSecond() {
			return refreshExpiresAtEpochSecond;
		}

		public void setRefreshExpiresAtEpochSecond(long refreshExpiresAtEpochSecond) {
			this.refreshExpiresAtEpochSecond = refreshExpiresAtEpochSecond;
		}

	}

}
