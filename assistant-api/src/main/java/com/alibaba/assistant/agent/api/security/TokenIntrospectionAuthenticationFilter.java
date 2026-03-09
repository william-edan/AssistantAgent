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

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.cors.CorsUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Collections;
import java.util.Optional;

/**
 * Authenticates chat requests by introspecting Bearer token with current auth system.
 *
 * @author Assistant Agent Team
 * @since 1.0.0
 */
@Component
@Profile("migration")
public class TokenIntrospectionAuthenticationFilter extends OncePerRequestFilter {

	private static final String CHAT_API_PREFIX = "/api/chat/";

	private static final String BEARER_PREFIX = "Bearer ";

	private final MigrationAuthService migrationAuthService;

	public TokenIntrospectionAuthenticationFilter(MigrationAuthService migrationAuthService) {
		this.migrationAuthService = migrationAuthService;
	}

	@Override
	protected boolean shouldNotFilter(HttpServletRequest request) {
		if (CorsUtils.isPreFlightRequest(request)) {
			return true;
		}
		String requestUri = request.getRequestURI();
		return !StringUtils.hasText(requestUri) || !requestUri.startsWith(CHAT_API_PREFIX);
	}

	@Override
	protected boolean shouldNotFilterAsyncDispatch() {
		return false;
	}

	@Override
	protected boolean shouldNotFilterErrorDispatch() {
		return false;
	}

	@Override
	protected void doFilterInternal(
			HttpServletRequest request,
			HttpServletResponse response,
			FilterChain filterChain) throws ServletException, IOException {
		String accessToken = resolveAccessToken(request);
		if (!StringUtils.hasText(accessToken)) {
			writeUnauthorized(response, "missing_access_token");
			return;
		}

		Optional<AuthenticatedUserContext> authenticatedUser = migrationAuthService.introspect(accessToken);
		if (authenticatedUser.isEmpty()) {
			writeUnauthorized(response, "invalid_access_token");
			return;
		}

		SecurityContext securityContext = SecurityContextHolder.createEmptyContext();
		securityContext.setAuthentication(new UsernamePasswordAuthenticationToken(
				authenticatedUser.get(),
				accessToken,
				Collections.emptyList()));
		SecurityContextHolder.setContext(securityContext);
		try {
			filterChain.doFilter(request, response);
		}
		finally {
			SecurityContextHolder.clearContext();
		}
	}

	private String resolveAccessToken(HttpServletRequest request) {
		String authorizationHeader = request.getHeader(HttpHeaders.AUTHORIZATION);
		if (!StringUtils.hasText(authorizationHeader)) {
			return null;
		}
		if (!authorizationHeader.startsWith(BEARER_PREFIX)) {
			return null;
		}
		String token = authorizationHeader.substring(BEARER_PREFIX.length()).trim();
		return StringUtils.hasText(token) ? token : null;
	}

	private void writeUnauthorized(HttpServletResponse response, String reason) throws IOException {
		response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
		response.setContentType(MediaType.APPLICATION_JSON_VALUE);
		response.getWriter().write("{\"code\":401,\"msg\":\"" + reason + "\"}");
	}

}