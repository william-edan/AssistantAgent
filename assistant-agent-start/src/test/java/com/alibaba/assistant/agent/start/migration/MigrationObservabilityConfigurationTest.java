/*
 * Copyright 2024-2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 */
package com.alibaba.assistant.agent.start.migration;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.metrics.MetricsEndpoint;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MigrationObservabilityConfigurationTest {

    @Test
    void shouldExposeActuatorObservationInfrastructureUnderMigrationProfile() throws IOException {
        String migrationYaml = readClasspathResource("application-migration.yml");
        assertTrue(migrationYaml.contains("management:"));
        assertTrue(migrationYaml.contains("metrics"));
        assertTrue(migrationYaml.contains("tracing"));

        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().setActiveProfiles("migration");
            context.register(TestObservabilityConfiguration.class);
            context.refresh();

            assertNotNull(context.getBean(ObservationRegistry.class));
            assertNotNull(context.getBean(MeterRegistry.class));
            assertNotNull(MetricsEndpoint.class);
        }
    }

    private String readClasspathResource(String resource) throws IOException {
        InputStream inputStream = Thread.currentThread().getContextClassLoader().getResourceAsStream(resource);
        assertNotNull(inputStream, "resource should exist: " + resource);
        try (inputStream) {
            return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Configuration
    @Profile("migration")
    static class TestObservabilityConfiguration {

        @Bean
        ObservationRegistry observationRegistry() {
            return ObservationRegistry.create();
        }

        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }
    }
}

