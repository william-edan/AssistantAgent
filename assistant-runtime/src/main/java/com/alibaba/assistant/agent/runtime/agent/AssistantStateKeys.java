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
 * Runtime state-key constants for the enterprise assistant.
 */
public final class AssistantStateKeys {

    private AssistantStateKeys() {
    }

    // Conversation context
    public static final String THREAD_ID = "thread_id";

    public static final String ASSISTANT_UID = "assistant_uid";

    public static final String SYSTEM_CODE = "system_code";

    public static final String AGENT_APP_CODE = "agent_app_code";

    public static final String ROLE_PACKAGE_CODE = "role_package_code";

    public static final String ROLE_PACKAGE_VERSION = "role_package_version";

    public static final String ROLE_SCENARIO_CODE = "role_scenario_code";

    public static final String SPACE_ID = "space_id";

    public static final String SPACE_CODE = "space_code";

    public static final String SPACE_ENVIRONMENT = "space_environment";

    // Slot collection
    public static final String MATCHED_TOOL_META = "matched_tool_meta";

    public static final String COLLECTED_SLOTS = "collected_slots";

    public static final String CURRENT_TURN_SLOT_INPUTS = "current_turn_slot_inputs";

    public static final String COLLECT_ROUND = "collect_round";

    public static final String CONVERSATION_PHASE = "conversation_phase";

    public static final String LAST_COLLECT_USER_INPUT = "last_collect_user_input";
    public static final String FORM_FLOW_EXTRACTION_PENDING = "form_flow_extraction_pending";

    public static final String ENRICHED_SLOTS = "enriched_slots";

    public static final String SLOT_DEFINITIONS = "slot_definitions";

    public static final String DEPENDENCY_RESULTS = "dependency_results";

    // Frontend projection
    public static final String FRONTEND_THREAD_STATE = "frontend_thread_state";

    // Identity context
    public static final String IDENTITY_CONTEXT = "identity_context";

    public static final String PLATFORM_PRINCIPAL_ID = "platform_principal_id";

    public static final String PLATFORM_PRINCIPAL_TYPE = "platform_principal_type";

    public static final String EXECUTION_SUBJECT_ID = "execution_subject_id";

    public static final String EXECUTION_SUBJECT_TYPE = "execution_subject_type";

    public static final String PROACTIVE_TASK_CODE = "proactive_task_code";

    // Execution context
    public static final String EXECUTION_RESULT = "execution_result";

    public static final String EXECUTION_CONFIRM_GRANTED = "execution_confirm_granted";

    public static final String EXECUTION_CONFIRM_TOOL_NAME = "execution_confirm_tool_name";

    public static final String EXECUTION_CONFIRM_USER_INPUT = "execution_confirm_user_input";

    // Audit context
    public static final String AUDIT_TRACE_ID = "audit_trace_id";
}
