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

/**
 * OverAllState key constants for the enterprise assistant platform.
 * Complements framework's CodeactStateKeys.
 *
 * @author Assistant Agent Team
 * @since 1.0.0
 */
public final class AssistantStateKeys {

	private AssistantStateKeys() {
	}

	// Session Context
	public static final String THREAD_ID = "thread_id";

	public static final String ASSISTANT_UID = "assistant_uid";

	public static final String SYSTEM_CODE = "system_code";

	public static final String AGENT_APP_CODE = "agent_app_code";

	public static final String SPACE_ID = "space_id";

	public static final String SPACE_CODE = "space_code";

	public static final String SPACE_ENVIRONMENT = "space_environment";

	// Slot Collection
	public static final String MATCHED_TOOL_META = "matched_tool_meta";

	public static final String COLLECTED_SLOTS = "collected_slots";

	public static final String COLLECT_ROUND = "collect_round";

	public static final String CONVERSATION_PHASE = "conversation_phase";

	public static final String LAST_COLLECT_USER_INPUT = "last_collect_user_input";

	public static final String ENRICHED_SLOTS = "enriched_slots";

	public static final String SLOT_DEFINITIONS = "slot_definitions";

	public static final String DEPENDENCY_RESULTS = "dependency_results";

	// Identity
	public static final String IDENTITY_CONTEXT = "identity_context";

	// Execution
	public static final String EXECUTION_RESULT = "execution_result";

	public static final String EXECUTION_CONFIRM_GRANTED = "execution_confirm_granted";

	public static final String EXECUTION_CONFIRM_TOOL_NAME = "execution_confirm_tool_name";

	public static final String EXECUTION_CONFIRM_USER_INPUT = "execution_confirm_user_input";

	// Audit
	public static final String AUDIT_TRACE_ID = "audit_trace_id";

}
