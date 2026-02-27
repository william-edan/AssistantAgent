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
package com.alibaba.assistant.agent.runtime.policy;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SqlGuardPolicyTest {

	@Test
	void shouldBuildRowFilterFromAssistantUid() {
		SqlGuardPolicy policy = new SqlGuardPolicy();
		assertEquals("assistant_uid = 'assistant-1001'", policy.buildRowFilter("assistant-1001"));
	}

	@Test
	void shouldRejectNonReadOnlySql() {
		SqlGuardPolicy policy = new SqlGuardPolicy();
		assertThrows(IllegalArgumentException.class, () -> policy.validate("DELETE FROM leave_records"));
	}

	@Test
	void shouldAllowReadOnlySql() {
		SqlGuardPolicy policy = new SqlGuardPolicy();
		assertDoesNotThrow(() -> policy.validate("SELECT id, assistant_uid FROM leave_records"));
	}

	@Test
	void shouldMaskSensitiveColumns() {
		SqlGuardPolicy policy = new SqlGuardPolicy();
		List<Map<String, Object>> rows = List.of(Map.of(
				"assistant_uid", "assistant-1001",
				"phone", "13800138000",
				"leave_days", 2));

		List<Map<String, Object>> masked = policy.maskSensitiveColumns(rows);
		assertEquals(1, masked.size());
		assertEquals("assistant-1001", masked.get(0).get("assistant_uid"));
		assertNotEquals("13800138000", masked.get(0).get("phone"));
	}

}
