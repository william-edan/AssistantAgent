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
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class ChatControllerCorsTest {

	@Test
	void shouldProvideCorsConfigForChatRunSse() {
		SecurityConfig securityConfig = new SecurityConfig();
		CorsConfigurationSource source = securityConfig.corsConfigurationSource();
		MockHttpServletRequest request = new MockHttpServletRequest("OPTIONS", "/api/chat/run_sse");
		request.addHeader(HttpHeaders.ORIGIN, "http://localhost:5173");
		request.addHeader(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST");
		request.addHeader(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "authorization,content-type,tenant-id");

		CorsConfiguration configuration = source.getCorsConfiguration(request);

		assertNotNull(configuration);
		assertEquals("http://localhost:5173", configuration.checkOrigin("http://localhost:5173"));
		assertNotNull(configuration.checkHttpMethod(HttpMethod.POST));
		assertNotNull(configuration.checkHeaders(List.of("authorization", "content-type", "tenant-id")));
	}

}