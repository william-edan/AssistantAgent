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

import org.junit.jupiter.api.Test;
import org.apache.ibatis.annotations.Mapper;
import org.mybatis.spring.annotation.MapperScan;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MigrationScanConfigurationTest {

    @Test
    void shouldScanAllMigrationMapperPackages() {
        MapperScan mapperScan = MigrationScanConfiguration.class.getAnnotation(MapperScan.class);
        assertNotNull(mapperScan);
        List<String> packages = List.of(mapperScan.basePackages());

        assertTrue(packages.contains("com.alibaba.assistant.agent.controlplane"));
        assertTrue(packages.contains("com.alibaba.assistant.agent.execution.persistence.mapper"));
        assertEquals(Mapper.class, mapperScan.annotationClass());
    }
}
