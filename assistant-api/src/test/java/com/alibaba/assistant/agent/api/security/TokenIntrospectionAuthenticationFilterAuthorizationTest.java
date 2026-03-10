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
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TokenIntrospectionAuthenticationFilterAuthorizationTest {

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void shouldKeepFilteringControlplaneRequests() {
        TestableTokenIntrospectionAuthenticationFilter filter =
                new TestableTokenIntrospectionAuthenticationFilter(mock(MigrationAuthService.class));
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/controlplane/spaces/demo/agent-apps/app/publication-source-policy");

        assertFalse(filter.shouldNotFilterPublic(request));
    }

    @Test
    void shouldPopulateSpringAuthoritiesFromAuthenticatedUserPermissions() throws ServletException, IOException {
        MigrationAuthService authService = mock(MigrationAuthService.class);
        when(authService.introspect("token-x")).thenReturn(Optional.of(new AuthenticatedUserContext(
                "1001",
                1L,
                "gougu_oa",
                "assistant-ui",
                "token-x",
                "admin",
                "管理员",
                List.of("assistant_user", "assistant_controlplane_admin"),
                List.of("assistant:chat", "assistant:controlplane"))));
        TestableTokenIntrospectionAuthenticationFilter filter = new TestableTokenIntrospectionAuthenticationFilter(authService);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/controlplane/spaces/demo/agent-apps/app/publication-source-policy");
        request.addHeader("Authorization", "Bearer token-x");
        MockHttpServletResponse response = new MockHttpServletResponse();
        CapturingFilterChain chain = new CapturingFilterChain();

        filter.doFilterInternalPublic(request, response, chain);

        assertEquals(200, response.getStatus());
        assertNotNull(chain.capturedAuthentication);
        assertTrue(chain.capturedAuthentication instanceof UsernamePasswordAuthenticationToken);
        assertTrue(chain.capturedAuthentication.getAuthorities().stream()
                .anyMatch(a -> "assistant:controlplane".equals(a.getAuthority())));
        assertTrue(chain.capturedAuthentication.getAuthorities().stream()
                .anyMatch(a -> "ROLE_assistant_controlplane_admin".equals(a.getAuthority())));
    }

    private static final class CapturingFilterChain implements FilterChain {

        private Authentication capturedAuthentication;

        @Override
        public void doFilter(ServletRequest request, ServletResponse response) {
            capturedAuthentication = SecurityContextHolder.getContext().getAuthentication();
        }
    }

    private static final class TestableTokenIntrospectionAuthenticationFilter
            extends TokenIntrospectionAuthenticationFilter {

        private TestableTokenIntrospectionAuthenticationFilter(MigrationAuthService migrationAuthService) {
            super(migrationAuthService);
        }

        private boolean shouldNotFilterPublic(MockHttpServletRequest request) {
            return super.shouldNotFilter(request);
        }

        private void doFilterInternalPublic(
                MockHttpServletRequest request,
                MockHttpServletResponse response,
                FilterChain chain) throws ServletException, IOException {
            super.doFilterInternal(request, response, chain);
        }
    }

}
