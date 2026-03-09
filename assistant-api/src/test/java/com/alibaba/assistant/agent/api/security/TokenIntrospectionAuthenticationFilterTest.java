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
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class TokenIntrospectionAuthenticationFilterTest {

	@Test
	void shouldSkipCorsPreflightRequest() {
		TestableTokenIntrospectionAuthenticationFilter filter =
				new TestableTokenIntrospectionAuthenticationFilter(mock(MigrationAuthService.class));
		MockHttpServletRequest request = new MockHttpServletRequest("OPTIONS", "/api/chat/run_sse");
		request.addHeader("Origin", "http://localhost:5173");
		request.addHeader("Access-Control-Request-Method", "POST");
		request.addHeader("Access-Control-Request-Headers", "authorization,content-type,tenant-id");

		assertTrue(filter.shouldNotFilterPublic(request));
	}

	@Test
	void shouldFilterAsyncDispatchesForChatRequests() {
		TestableTokenIntrospectionAuthenticationFilter filter =
				new TestableTokenIntrospectionAuthenticationFilter(mock(MigrationAuthService.class));

		assertFalse(filter.shouldNotFilterAsyncDispatchPublic());
	}

	@Test
	void shouldFilterErrorDispatchesForChatRequests() {
		TestableTokenIntrospectionAuthenticationFilter filter =
				new TestableTokenIntrospectionAuthenticationFilter(mock(MigrationAuthService.class));

		assertFalse(filter.shouldNotFilterErrorDispatchPublic());
	}

	@Test
	void shouldKeepFilteringChatPostRequests() {
		TestableTokenIntrospectionAuthenticationFilter filter =
				new TestableTokenIntrospectionAuthenticationFilter(mock(MigrationAuthService.class));
		MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/chat/run_sse");

		assertFalse(filter.shouldNotFilterPublic(request));
	}

	private static final class TestableTokenIntrospectionAuthenticationFilter
			extends TokenIntrospectionAuthenticationFilter {

		private TestableTokenIntrospectionAuthenticationFilter(MigrationAuthService migrationAuthService) {
			super(migrationAuthService);
		}

		private boolean shouldNotFilterPublic(MockHttpServletRequest request) {
			return super.shouldNotFilter(request);
		}

		private boolean shouldNotFilterAsyncDispatchPublic() {
			return super.shouldNotFilterAsyncDispatch();
		}

		private boolean shouldNotFilterErrorDispatchPublic() {
			return super.shouldNotFilterErrorDispatch();
		}
	}

}