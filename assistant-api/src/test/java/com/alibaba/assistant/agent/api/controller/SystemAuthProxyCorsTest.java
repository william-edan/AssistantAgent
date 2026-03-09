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

import com.alibaba.assistant.agent.api.security.SecurityConfig;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SystemAuthProxyCorsTest {

	@Test
	void shouldApplyGlobalCorsConfigurationToLoginEndpoint() {
		SecurityConfig securityConfig = new SecurityConfig();
		CorsConfigurationSource source = securityConfig.corsConfigurationSource();
		MockHttpServletRequest request = new MockHttpServletRequest(HttpMethod.OPTIONS.name(), "/system/auth/login");
		request.addHeader("Origin", "http://localhost:5173");
		request.addHeader("Access-Control-Request-Method", "POST");

		CorsConfiguration configuration = source.getCorsConfiguration(request);

		assertNotNull(configuration);
		assertTrue(configuration.getAllowedOriginPatterns().contains("http://localhost:*"));
		assertTrue(configuration.getAllowedMethods().contains(HttpMethod.POST.name()));
	}

}
