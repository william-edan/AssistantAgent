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

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SecurityConfigCorsTest {

    @Test
    void shouldRegisterGlobalCorsForAuthChatAndControlplaneEndpoints() {
        SecurityConfig securityConfig = new SecurityConfig();
        CorsConfigurationSource source = securityConfig.corsConfigurationSource();

        CorsConfiguration authConfig = source.getCorsConfiguration(request(HttpMethod.OPTIONS.name(), "/system/auth/login"));
        CorsConfiguration chatConfig = source.getCorsConfiguration(request(HttpMethod.OPTIONS.name(), "/api/chat/run_sse"));
        CorsConfiguration controlplaneConfig = source.getCorsConfiguration(request(
                HttpMethod.OPTIONS.name(),
                "/api/controlplane/spaces/enterprise-default/agent-apps/finance-agent/publication-source-policy"));

        assertNotNull(authConfig);
        assertNotNull(chatConfig);
        assertNotNull(controlplaneConfig);
        assertEquals(authConfig.getAllowedOriginPatterns(), chatConfig.getAllowedOriginPatterns());
        assertEquals(chatConfig.getAllowedOriginPatterns(), controlplaneConfig.getAllowedOriginPatterns());
        assertTrue(authConfig.getAllowedOriginPatterns().contains("http://localhost:*"));
        assertTrue(authConfig.getAllowedOriginPatterns().contains("http://127.0.0.1:*"));
        assertTrue(authConfig.getAllowedMethods().contains(HttpMethod.POST.name()));
        assertTrue(controlplaneConfig.getAllowedMethods().contains(HttpMethod.GET.name()));
        assertTrue(controlplaneConfig.getAllowedMethods().contains(HttpMethod.PUT.name()));
        assertTrue(authConfig.getAllowedMethods().contains(HttpMethod.OPTIONS.name()));
    }

    private static MockHttpServletRequest request(String method, String path) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.addHeader("Origin", "http://localhost:5173");
        request.addHeader("Access-Control-Request-Method", "POST");
        return request;
    }

}
