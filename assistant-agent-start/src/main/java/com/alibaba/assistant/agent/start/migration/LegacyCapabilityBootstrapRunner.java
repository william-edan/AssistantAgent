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

import com.alibaba.assistant.agent.controlplane.toolregistry.CapabilityToToolMetaMigrator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Bootstraps legacy capability migration on startup for migration profile.
 * Converts seeded rows from assistant_capability_registry into tool_meta.
 *
 * @author Assistant Agent Team
 * @since 1.0.0
 */
@Component
@Profile("migration")
public class LegacyCapabilityBootstrapRunner implements CommandLineRunner {

	private static final Logger logger = LoggerFactory.getLogger(LegacyCapabilityBootstrapRunner.class);

	private final CapabilityToToolMetaMigrator migrator;

	private final boolean enabled;

	public LegacyCapabilityBootstrapRunner(
			@Autowired(required = false) CapabilityToToolMetaMigrator migrator,
			@Value("${assistant.migration.bootstrap.legacy-capability.enabled:true}") boolean enabled) {
		this.migrator = migrator;
		this.enabled = enabled;
	}

	@Override
	public void run(String... args) {
		if (!enabled) {
			logger.info("LegacyCapabilityBootstrapRunner#run - disabled by config");
			return;
		}
		if (migrator == null) {
			logger.warn("LegacyCapabilityBootstrapRunner#run - migrator bean not found, skip");
			return;
		}

		CapabilityToToolMetaMigrator.MigrationResult result = migrator.migrateAll();
		logger.info("LegacyCapabilityBootstrapRunner#run - sourceTable={}, scanned={}, inserted={}, updated={}, "
				+ "skipped={}",
				result.sourceTable(), result.scanned(), result.inserted(), result.updated(), result.skipped());
	}

}

