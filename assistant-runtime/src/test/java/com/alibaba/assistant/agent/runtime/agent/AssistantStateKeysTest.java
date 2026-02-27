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
package com.alibaba.assistant.agent.runtime.agent;

import com.alibaba.assistant.agent.common.constant.CodeactStateKeys;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AssistantStateKeysTest {

	@Test
	void allKeysAreDefined() {
		assertNotNull(AssistantStateKeys.THREAD_ID);
		assertNotNull(AssistantStateKeys.ASSISTANT_UID);
		assertNotNull(AssistantStateKeys.SYSTEM_CODE);
		assertNotNull(AssistantStateKeys.MATCHED_TOOL_META);
		assertNotNull(AssistantStateKeys.COLLECTED_SLOTS);
		assertNotNull(AssistantStateKeys.COLLECT_ROUND);
		assertNotNull(AssistantStateKeys.CONVERSATION_PHASE);
		assertNotNull(AssistantStateKeys.ENRICHED_SLOTS);
		assertNotNull(AssistantStateKeys.IDENTITY_CONTEXT);
		assertNotNull(AssistantStateKeys.EXECUTION_RESULT);
		assertNotNull(AssistantStateKeys.AUDIT_TRACE_ID);
	}

	@Test
	void noKeyConflictsWithFrameworkKeys() {
		assertNotEquals(CodeactStateKeys.GENERATED_CODES, AssistantStateKeys.COLLECTED_SLOTS);
		assertNotEquals(CodeactStateKeys.USER_ID, AssistantStateKeys.ASSISTANT_UID);
	}

	@Test
	void keysFollowNamingConvention() {
		assertTrue(AssistantStateKeys.THREAD_ID.matches("[a-z_]+"));
		assertTrue(AssistantStateKeys.ASSISTANT_UID.matches("[a-z_]+"));
		assertTrue(AssistantStateKeys.SYSTEM_CODE.matches("[a-z_]+"));
	}

}
