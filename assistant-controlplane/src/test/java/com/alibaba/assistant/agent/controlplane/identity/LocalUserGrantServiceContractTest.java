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

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

class LocalUserGrantServiceContractTest {

	@Test
	void shouldReturnEmptyRoleListWhenLocalUserIdNull() {
		LocalUserGrantService service = spy(new LocalUserGrantService());

		List<String> roles = service.findRoles(null);

		assertTrue(roles.isEmpty());
		verify(service, never()).list(any(Wrapper.class));
	}

	@Test
	void shouldResolveDistinctActiveRolesInOrder() {
		LocalUserGrantService service = spy(new LocalUserGrantService());
		doReturn(List.of(
				grant("role", "assistant_user"),
				grant("role", "assistant_controlplane_admin"),
				grant("role", "assistant_user"),
				grant("permission", "assistant:chat")))
				.when(service).list(any(Wrapper.class));

		List<String> roles = service.findRoles(1001L);

		assertEquals(List.of("assistant_user", "assistant_controlplane_admin"), roles);
		verify(service).list(any(Wrapper.class));
	}

	@Test
	void shouldResolveDistinctPermissionsInOrder() {
		LocalUserGrantService service = spy(new LocalUserGrantService());
		doReturn(List.of(
				grant("permission", "assistant:chat"),
				grant("permission", "assistant:controlplane"),
				grant("permission", "assistant:chat")))
				.when(service).list(any(Wrapper.class));

		List<String> permissions = service.findPermissions(1001L);

		assertEquals(List.of("assistant:chat", "assistant:controlplane"), permissions);
		verify(service).list(any(Wrapper.class));
	}

	@Test
	void shouldReturnFalseWhenScopedGrantCheckHasNoUser() {
		LocalUserGrantService service = spy(new LocalUserGrantService());

		boolean present = service.hasGrant(null, "permission", "assistant:controlplane", "space", "enterprise-default");

		assertFalse(present);
		verify(service, never()).count(any(Wrapper.class));
	}

	@Test
	void shouldMatchScopedGrantLookup() {
		LocalUserGrantService service = spy(new LocalUserGrantService());
		doReturn(1L).when(service).count(any(Wrapper.class));

		boolean present = service.hasGrant(1001L, "permission", "assistant:controlplane", "space", "enterprise-default");

		assertTrue(present);
		verify(service).count(any(Wrapper.class));
	}

	@Test
	void shouldMatchGlobalGrantLookupWithDefaultScope() {
		LocalUserGrantService service = spy(new LocalUserGrantService());
		doReturn(1L).when(service).count(any(Wrapper.class));

		boolean present = service.hasGrant(1001L, "role", "assistant_controlplane_admin");

		assertTrue(present);
		verify(service).count(any(Wrapper.class));
	}

	private LocalUserGrant grant(String grantType, String grantCode) {
		LocalUserGrant grant = new LocalUserGrant();
		grant.setGrantType(grantType);
		grant.setGrantCode(grantCode);
		grant.setScopeType(LocalUserGrantService.SCOPE_TYPE_GLOBAL);
		grant.setScopeCode(LocalUserGrantService.SCOPE_CODE_GLOBAL);
		grant.setStatus("active");
		return grant;
	}

}
