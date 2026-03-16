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

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * Migration profile scan configuration.
 * Activates component scanning for new platform modules only when "migration" profile is active.
 * This prevents MyBatis Mapper injection failures in demo mode (no database).
 *
 * @author Assistant Agent Team
 * @since 1.0.0
 */
@Configuration
@Profile("migration")
@ComponentScan(basePackages = {
        "com.alibaba.assistant.agent.api",
        "com.alibaba.assistant.agent.runtime",
        "com.alibaba.assistant.agent.slot",
        "com.alibaba.assistant.agent.execution",
        "com.alibaba.assistant.agent.controlplane",
        "com.alibaba.assistant.agent.infra"
})
@MapperScan(basePackages = {
        "com.alibaba.assistant.agent.controlplane",
        "com.alibaba.assistant.agent.execution.persistence.mapper"
})
public class MigrationScanConfiguration {

}
