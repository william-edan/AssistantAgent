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

class DigitalEmployeePlatformSeedTest {

    private static final String OFFICE1_SEED_RESOURCE = "db/migration/V38__seed_office1_connector_and_tool_meta.sql";

    private static final String DIGITAL_ADMIN_SEED_RESOURCE = "db/migration/V39__seed_digital_admin_role_package.sql";

    @Test
    void shouldSeedOffice1CredentialTopologyAndDigitalAdminRolePackage() throws IOException {
        String office1Sql = readMigrationResource(OFFICE1_SEED_RESOURCE);
        String digitalAdminSql = readMigrationResource(DIGITAL_ADMIN_SEED_RESOURCE);

        assertTrue(office1Sql.contains("INSERT INTO connector"));
        assertTrue(office1Sql.contains("office1"));
        assertTrue(office1Sql.contains("INSERT INTO auth_profile"));
        assertTrue(office1Sql.contains("service_account"));
        assertTrue(office1Sql.contains("delegated"));
        assertTrue(office1Sql.contains("INSERT INTO principal_binding"));
        for (String toolCode : List.of(
                "office1.meeting_rooms",
                "office1.approval_flows",
                "office1.employees",
                "office1.my_leader")) {
            assertTrue(office1Sql.contains(toolCode), "expected seeded office1 tool: " + toolCode);
        }

        assertTrue(digitalAdminSql.contains("INSERT INTO role_package"));
        assertTrue(digitalAdminSql.contains("digital-admin"));
        assertTrue(digitalAdminSql.contains("admin-agent"));
        assertTrue(digitalAdminSql.contains("INSERT INTO role_scenario"));
        for (String scenarioCode : List.of(
                "meeting_coordination",
                "weekly_report_collection",
                "approval_cleanup",
                "leave_duty_coordination")) {
            assertTrue(digitalAdminSql.contains(scenarioCode), "expected scenario seed for " + scenarioCode);
        }
        assertTrue(digitalAdminSql.contains("INSERT INTO role_proactive_task"));
        assertTrue(digitalAdminSql.contains("INSERT INTO role_tool_scope"));
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
