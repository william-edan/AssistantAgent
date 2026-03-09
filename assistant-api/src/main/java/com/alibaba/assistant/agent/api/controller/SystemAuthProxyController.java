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
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Migration auth controller for legacy /system/auth compatibility routes.
 *
 * @author Assistant Agent Team
 * @since 1.0.0
 */
@RestController
@Profile("migration")
@RequestMapping("/system/auth")
public class SystemAuthProxyController {

	private final MigrationAuthService migrationAuthService;

	public SystemAuthProxyController(MigrationAuthService migrationAuthService) {
		this.migrationAuthService = migrationAuthService;
	}

	@PostMapping("/login")
	public ResponseEntity<Object> login(
			@RequestBody(required = false) Map<String, Object> requestBody,
			@RequestHeader HttpHeaders requestHeaders) {
		Map<String, Object> body = requestBody == null ? Map.of() : requestBody;
		String username = readText(body.get("username"));
		String password = readText(body.get("password"));
		Long tenantId = resolveTenantId(requestHeaders);
		String systemCode = readText(body.get("systemCode"), body.get("system_code"));
		LoginResult loginResult = migrationAuthService.login(username, password, tenantId, systemCode);
		return ok(loginResultToMap(loginResult));
	}

	@PostMapping("/refresh-token")
	public ResponseEntity<Object> refreshToken(@RequestParam("refreshToken") String refreshToken) {
		LoginResult loginResult = migrationAuthService.refresh(refreshToken);
		return ok(loginResultToMap(loginResult));
	}

	@GetMapping("/get-permission-info")
	public ResponseEntity<Object> getPermissionInfo(@RequestHeader HttpHeaders requestHeaders) {
		String accessToken = resolveBearerToken(requestHeaders);
		Map<String, Object> permissionInfo = migrationAuthService.getPermissionInfo(accessToken);
		return ok(permissionInfo);
	}

	@PostMapping("/logout")
	public ResponseEntity<Object> logout(
			@RequestHeader HttpHeaders requestHeaders,
			@RequestParam(value = "refreshToken", required = false) String refreshToken) {
		String accessToken = resolveBearerTokenOrNull(requestHeaders);
		migrationAuthService.logout(accessToken, refreshToken);
		return ok(Map.of("success", true));
	}

	private ResponseEntity<Object> ok(Object data) {
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("code", 0);
		payload.put("msg", "");
		payload.put("data", data);
		return ResponseEntity.ok(payload);
	}

	private Map<String, Object> loginResultToMap(LoginResult result) {
		Map<String, Object> data = new LinkedHashMap<>();
		data.put("accessToken", result.accessToken());
		data.put("refreshToken", result.refreshToken());
		data.put("expiresTime", result.expiresTime());
		data.put("userId", result.userId());
		return data;
	}

	private Long resolveTenantId(HttpHeaders requestHeaders) {
		String tenantHeader = requestHeaders == null ? null : requestHeaders.getFirst("tenant-id");
		if (!StringUtils.hasText(tenantHeader)) {
			return null;
		}
		try {
			return Long.parseLong(tenantHeader.trim());
		}
		catch (NumberFormatException ignored) {
			return null;
		}
	}

	private String resolveBearerToken(HttpHeaders requestHeaders) {
		String token = resolveBearerTokenOrNull(requestHeaders);
		if (!StringUtils.hasText(token)) {
			throw new ResponseStatusException(org.springframework.http.HttpStatus.UNAUTHORIZED, "missing_access_token");
		}
		return token;
	}

	private String resolveBearerTokenOrNull(HttpHeaders requestHeaders) {
		if (requestHeaders == null) {
			return null;
		}
		String authorization = requestHeaders.getFirst(HttpHeaders.AUTHORIZATION);
		if (!StringUtils.hasText(authorization) || !authorization.startsWith("Bearer ")) {
			return null;
		}
		String token = authorization.substring("Bearer ".length()).trim();
		return StringUtils.hasText(token) ? token : null;
	}

	private String readText(Object... values) {
		for (Object value : values) {
			if (value == null) {
				continue;
			}
			String text = String.valueOf(value).trim();
			if (StringUtils.hasText(text)) {
				return text;
			}
		}
		return null;
	}

}
