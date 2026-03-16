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
package com.alibaba.assistant.agent.infra.migration;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GouguOaRegistrySeedTest {

    private static final String CANONICAL_SEED_RESOURCE = "db/migration/V8__seed_gougu_oa_registry.sql";

    private static final String QUERY_TOOL_RESOURCE = "db/migration/V28__convert_slot_option_apis_to_query_tools.sql";

    private static final String CLEANUP_RESOURCE = "db/migration/V29__promote_tool_meta_and_drop_legacy_tables.sql";

    private static final String APPROVAL_PROBE_RESOURCE = "db/migration/V33__seed_platform_approval_probe.sql";

    @Test
    void shouldSeedCanonicalBusinessToolsDirectly() throws IOException {
        String sql = readMigrationResource(CANONICAL_SEED_RESOURCE);

        assertTrue(sql.contains("INSERT INTO system_access_profile"));
        for (String toolCode : List.of(
                "gougu_oa.leave_application",
                "gougu_oa.meeting_room_booking",
                "gougu_oa.work_report")) {
            assertTrue(sql.contains(toolCode), "expected canonical business tool seed for " + toolCode);
        }

        assertTrue(sql.contains("\"toolType\": \"WORKFLOW\""));
        assertTrue(sql.contains("\"visibility\": \"USER\""));
        assertTrue(sql.contains("\"invocationPolicy\": \"DIRECT\""));
        assertTrue(sql.contains("\"function\": \"selector_switch\""));
        assertTrue(sql.contains("\"name\": \"submit_range_date\""));
        assertTrue(sql.contains("\"name\": \"to_uids\""));
        assertTrue(sql.contains("\"type\": \"string\""));
        assertTrue(sql.contains("\"toolCode\": \"gougu_oa.leave_type_options\""));
        assertTrue(sql.contains("\"toolCode\": \"gougu_oa.approver_candidates\""));
        assertTrue(sql.contains("\"toolCode\": \"gougu_oa.user_directory\""));
    }

    @Test
    void shouldRegisterInternalResolverTools() throws IOException {
        String sql = readMigrationResource(QUERY_TOOL_RESOURCE);

        for (String toolCode : List.of(
                "gougu_oa.leave_type_options",
                "gougu_oa.approver_candidates",
                "gougu_oa.user_directory",
                "gougu_oa.leave_flow_options",
                "gougu_oa.meeting_room_options",
                "gougu_oa.meeting_requirement_options",
                "gougu_oa.meeting_flow_options")) {
            assertTrue(sql.contains(toolCode), "expected query tool seed for " + toolCode);
        }

        assertTrue(sql.contains("\"visibility\":\"INTERNAL\""));
        assertTrue(sql.contains("\"invocationPolicy\":\"DEPENDENCY_ONLY\""));
        assertTrue(sql.contains("\"toolType\":\"QUERY\""));
    }

    @Test
    void shouldSeedPlatformApprovalProbeTool() throws IOException {
        String sql = readMigrationResource(APPROVAL_PROBE_RESOURCE);

        assertTrue(sql.contains("gougu_oa.platform_approval_probe"));
        assertTrue(sql.contains("平台审批探针"));
        assertTrue(sql.contains("\"toolType\":\"WORKFLOW\""));
        assertTrue(sql.contains("\"visibility\":\"USER\""));
        assertTrue(sql.contains("\"invocationPolicy\":\"DIRECT\""));
        assertTrue(sql.contains("\"approvalGate\":{\"channel\":\"platform\""));
        assertTrue(sql.contains("\"type\":\"CONDITION\""));
    }

    @Test
    void shouldDropObsoleteDefinitionTables() throws IOException {
        String sql = readMigrationResource(CLEANUP_RESOURCE);

        assertTrue(sql.contains("DROP TABLE IF EXISTS workflow_step"));
        assertTrue(sql.contains("DROP TABLE IF EXISTS workflow_spec"));
        assertTrue(sql.contains("DROP TABLE IF EXISTS interaction_spec"));
        assertTrue(sql.contains("DROP TABLE IF EXISTS action_spec"));
        assertTrue(sql.contains("DROP TABLE IF EXISTS precondition_check"));
        assertTrue(sql.contains("DROP TABLE IF EXISTS business_query_action"));
        assertTrue(sql.contains("DROP TABLE IF EXISTS reference_resolver"));
        assertTrue(sql.contains("DROP TABLE IF EXISTS assistant_capability_registry"));
        assertTrue(sql.contains("DROP TABLE IF EXISTS assistant_system_registry"));
    }

    private String readMigrationResource(String resource) throws IOException {
        InputStream inputStream = Thread.currentThread()
                .getContextClassLoader()
                .getResourceAsStream(resource);
        assertNotNull(inputStream, "migration resource should exist: " + resource);
        try (inputStream) {
            return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
