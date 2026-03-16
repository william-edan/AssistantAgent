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
package com.alibaba.assistant.agent.runtime.identity;

import com.alibaba.assistant.agent.controlplane.identity.IdentityBinding;
import com.alibaba.assistant.agent.controlplane.identity.TokenBroker;
import com.alibaba.assistant.agent.controlplane.identity.TokenLease;
import com.alibaba.assistant.agent.controlplane.identity.mapper.IdentityBindingMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 基于令牌交换的身份代理实现。
 *
 * <p>当平台用户和企业系统用户已经建立绑定关系后，
 * 该组件会读取系统接入配置，调用外部系统的令牌交换接口获取真实访问令牌，并在内存中做短期缓存。
 * 这是平台用户“以自己的企业身份”去调用 OA/ERP/CRM 的关键一环。</p>
 */
@Primary
@Service
@Profile("migration")
public class TokenExchangeTokenBroker implements TokenBroker {

	private static final Logger logger = LoggerFactory.getLogger(TokenExchangeTokenBroker.class);

	private final IdentityBindingMapper identityBindingMapper;

	private final JdbcTemplate jdbcTemplate;

	private final RestTemplate restTemplate;

	private final ObjectMapper objectMapper = new ObjectMapper();

	private final ConcurrentHashMap<String, CachedToken> tokenCache = new ConcurrentHashMap<>();

	public TokenExchangeTokenBroker(IdentityBindingMapper identityBindingMapper, JdbcTemplate jdbcTemplate) {
		this.identityBindingMapper = identityBindingMapper;
		this.jdbcTemplate = jdbcTemplate;
		this.restTemplate = new RestTemplate();
		logger.info("TokenExchangeTokenBroker#init - reason=tokenExchangeBrokerRegistered");
	}

	@Override
	public Optional<TokenLease> acquire(String assistantUid, String systemCode) {
		// 先查本地缓存，避免同一用户反复触发外部令牌交换接口。
		String cacheKey = assistantUid + ":" + systemCode;
		CachedToken cached = tokenCache.get(cacheKey);
		if (cached != null && !cached.isExpired()) {
			logger.debug("TokenExchangeTokenBroker#acquire - reason=cacheHit, cacheKey={}", cacheKey);
			return Optional.of(cached.toLease(assistantUid, systemCode));
		}

		// 再查平台用户和企业系统用户之间的绑定关系。
		IdentityBinding binding = identityBindingMapper.selectOne(
				new LambdaQueryWrapper<IdentityBinding>()
						.eq(IdentityBinding::getAssistantUid, assistantUid)
						.eq(IdentityBinding::getSystemCode, systemCode)
						.eq(IdentityBinding::getStatus, "active"));

		if (binding == null) {
			logger.warn("TokenExchangeTokenBroker#acquire - reason=noBinding, assistantUid={}, systemCode={}",
					assistantUid, systemCode);
			return Optional.empty();
		}

		// 如果绑定记录里已经直接存了可用凭证，则直接返回。
		if (StringUtils.hasText(binding.getCredentials())) {
			logger.debug("TokenExchangeTokenBroker#acquire - reason=directCredentials, systemCode={}", systemCode);
			String leaseId = UUID.randomUUID().toString();
			return Optional.of(new TokenLease(leaseId, binding.getCredentials(), systemCode, assistantUid,
					LocalDateTime.now().plusSeconds(7200)));
		}

		// 否则根据系统接入配置发起一次令牌交换。
		return exchangeToken(binding, cacheKey);
	}

	@Override
	public void revoke(String leaseId) {
		logger.debug("TokenExchangeTokenBroker#revoke - leaseId={}", leaseId);
	}

	private Optional<TokenLease> exchangeToken(IdentityBinding binding, String cacheKey) {
		String systemCode = binding.getSystemCode();
		try {
			// 从 system_access_profile 读取令牌交换所需的地址、模板和过期时间。
			Map<String, Object> profile = jdbcTemplate.queryForMap(
					"SELECT base_url, token_endpoint, token_method, token_request_tpl, "
							+ "token_response_path, token_ttl_seconds "
							+ "FROM system_access_profile WHERE system_code = ? AND status = 'enabled'",
					systemCode);

			String baseUrl = (String) profile.get("base_url");
			String tokenEndpoint = (String) profile.get("token_endpoint");
			String tokenRequestTpl = (String) profile.get("token_request_tpl");
			String tokenResponsePath = (String) profile.get("token_response_path");
			int ttlSeconds = profile.get("token_ttl_seconds") != null
					? ((Number) profile.get("token_ttl_seconds")).intValue() : 7200;

			if (!StringUtils.hasText(baseUrl) || !StringUtils.hasText(tokenEndpoint)) {
				logger.warn("TokenExchangeTokenBroker#exchangeToken - reason=missingProfileConfig, systemCode={}",
						systemCode);
				return Optional.empty();
			}

			// 用绑定里的 system_user_id 替换模板变量，组装出外部系统需要的请求体。
			String requestBody = tokenRequestTpl;
			if (StringUtils.hasText(requestBody)) {
				requestBody = requestBody.replace("${system_user_id}", binding.getSystemUserId());
			}
			else {
				requestBody = "{\"system_user_id\": \"" + binding.getSystemUserId() + "\"}";
			}

			// 真正调用外部系统令牌交换接口。
			String fullUrl = baseUrl + tokenEndpoint;
			logger.info("TokenExchangeTokenBroker#exchangeToken - reason=callingTokenEndpoint, "
					+ "url={}, systemCode={}, systemUserId={}", fullUrl, systemCode, binding.getSystemUserId());

			HttpHeaders headers = new HttpHeaders();
			headers.setContentType(MediaType.APPLICATION_JSON);
			HttpEntity<String> httpEntity = new HttpEntity<>(requestBody, headers);

			ResponseEntity<String> response = restTemplate.postForEntity(fullUrl, httpEntity, String.class);

			if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
				logger.error("TokenExchangeTokenBroker#exchangeToken - reason=tokenEndpointFailed, "
						+ "statusCode={}, systemCode={}", response.getStatusCode(), systemCode);
				return Optional.empty();
			}

			// Parse token from response using dot-path (e.g. "data.token")
			String token = extractByPath(response.getBody(), tokenResponsePath);
			if (!StringUtils.hasText(token)) {
				logger.error("TokenExchangeTokenBroker#exchangeToken - reason=tokenNotFoundInResponse, "
						+ "responsePath={}, systemCode={}", tokenResponsePath, systemCode);
				return Optional.empty();
			}

			// Cache the token
			LocalDateTime expiresAt = LocalDateTime.now().plusSeconds(ttlSeconds);
			CachedToken cachedToken = new CachedToken(token, expiresAt, ttlSeconds);
			tokenCache.put(cacheKey, cachedToken);

			logger.info("TokenExchangeTokenBroker#exchangeToken - reason=tokenAcquired, "
					+ "systemCode={}, ttl={}s", systemCode, ttlSeconds);

			String leaseId = UUID.randomUUID().toString();
			return Optional.of(new TokenLease(leaseId, token, systemCode, binding.getAssistantUid(), expiresAt));
		}
		catch (Exception e) {
			logger.error("TokenExchangeTokenBroker#exchangeToken - reason=tokenExchangeFailed, systemCode={}",
					systemCode, e);
			return Optional.empty();
		}
	}

	/**
	 * 按点分路径从 JSON 里提取字段，例如 {@code data.token}。
	 */
	private String extractByPath(String json, String path) {
		try {
			JsonNode node = objectMapper.readTree(json);
			if (!StringUtils.hasText(path)) {
				return null;
			}
			String[] segments = path.split("\\.");
			for (String segment : segments) {
				if (node == null || node.isMissingNode()) {
					return null;
				}
				node = node.get(segment);
			}
			return node != null && !node.isMissingNode() ? node.asText() : null;
		}
		catch (Exception e) {
			logger.error("TokenExchangeTokenBroker#extractByPath - reason=jsonParseFailed, path={}", path, e);
			return null;
		}
	}

	private record CachedToken(String token, LocalDateTime expiresAt, int ttlSeconds) {

		boolean isExpired() {
			// Expire slightly early (30s buffer) to avoid race conditions
			return LocalDateTime.now().isAfter(expiresAt.minusSeconds(30));
		}

		TokenLease toLease(String assistantUid, String systemCode) {
			return new TokenLease(UUID.randomUUID().toString(), token, systemCode, assistantUid, expiresAt);
		}

	}

}
