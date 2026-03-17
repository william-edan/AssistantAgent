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
package com.alibaba.assistant.agent.start.migration;

import com.alibaba.assistant.agent.controlplane.connector.AuthProfileService;
import com.alibaba.assistant.agent.controlplane.connector.ConnectorService;
import com.alibaba.assistant.agent.controlplane.identity.PrincipalBindingService;
import com.alibaba.assistant.agent.controlplane.identity.TokenBroker;
import com.alibaba.assistant.agent.runtime.execution.ControlPlaneCredentialBroker;
import com.alibaba.assistant.agent.runtime.execution.CredentialBroker;
import com.alibaba.assistant.agent.runtime.execution.ResolvedCredentialLease;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DigitalAdminAssistantFlowTest {

    @Test
    void shouldExposeSeededOffice1ToolsUnderMigrationProfile() throws IOException {
        String migrationYaml = readClasspathResource("application-migration.yml");
        assertTrue(migrationYaml.contains("office1:"));

        String seedSql = readClasspathResource("db/migration/V38__seed_office1_connector_and_tool_meta.sql");
        assertTrue(seedSql.contains("office1.meeting_rooms"));
        assertTrue(seedSql.contains("office1.work_types"));
        assertTrue(seedSql.contains("office1.my_leader"));

        Path contractPath = findWorkspaceFile(Paths.get("docs", "contracts", "office1-adapter-openapi.json"));
        assertTrue(Files.exists(contractPath));
        String contract = Files.readString(contractPath, StandardCharsets.UTF_8);
        assertTrue(contract.contains("/get_meeting_rooms"));
        assertTrue(contract.contains("/get_approval_flows"));
    }

    @Test
    void shouldWireSingleCredentialBrokerPathUnderMigrationProfile() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().setActiveProfiles("migration");
            context.register(MigrationSchedulingConfiguration.class, TestCredentialBrokerConfiguration.class);
            context.refresh();

            Map<String, CredentialBroker> brokers = context.getBeansOfType(CredentialBroker.class);
            assertEquals(1, brokers.size());
            assertTrue(context.getBean(CredentialBroker.class) instanceof ControlPlaneCredentialBroker);
            assertNotNull(context.getBean("migrationExecutionExecutor"));
        }
    }

    private String readClasspathResource(String resource) throws IOException {
        InputStream inputStream = Thread.currentThread().getContextClassLoader().getResourceAsStream(resource);
        assertNotNull(inputStream, "resource should exist: " + resource);
        try (inputStream) {
            return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private Path findWorkspaceFile(Path relativePath) {
        Path cursor = Paths.get("").toAbsolutePath();
        while (cursor != null) {
            Path candidate = cursor.resolve(relativePath);
            if (Files.exists(candidate)) {
                return candidate;
            }
            cursor = cursor.getParent();
        }
        throw new IllegalStateException("workspace file not found: " + relativePath);
    }

    @Configuration
    @Profile("migration")
    static class TestCredentialBrokerConfiguration {

        @Bean
        TokenBroker tokenBroker() {
            return new TokenBroker() {
                @Override
                public Optional<com.alibaba.assistant.agent.controlplane.identity.TokenLease> acquire(String assistantUid, String systemCode) {
                    return Optional.empty();
                }

                @Override
                public void revoke(String leaseId) {
                }
            };
        }

        @Bean
        ControlPlaneCredentialBroker controlPlaneCredentialBroker(TokenBroker tokenBroker) {
            return new ControlPlaneCredentialBroker(
                    new ConnectorService(),
                    new AuthProfileService(),
                    new PrincipalBindingService(),
                    tokenBroker);
        }
    }
}

