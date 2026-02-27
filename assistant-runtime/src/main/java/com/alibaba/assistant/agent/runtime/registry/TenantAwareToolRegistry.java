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
package com.alibaba.assistant.agent.runtime.registry;

import com.alibaba.assistant.agent.common.enums.Language;
import com.alibaba.assistant.agent.common.tools.CodeactTool;
import com.alibaba.assistant.agent.common.tools.definition.CodeactToolDefinition;
import com.alibaba.assistant.agent.common.tools.definition.ReturnSchema;
import com.alibaba.assistant.agent.core.tool.CodeactToolRegistry;
import com.alibaba.assistant.agent.core.tool.DefaultCodeactToolRegistry;
import com.alibaba.assistant.agent.core.tool.schema.ReturnSchemaRegistry;
import com.alibaba.assistant.agent.runtime.tool.codeact.CapabilityBridgeToolFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tenant-aware CodeactToolRegistry with snapshot cache.
 * Uses tenant-level cached snapshots and supports event-driven invalidation.
 *
 * @author Assistant Agent Team
 * @since 1.0.0
 */
@Component
@Profile("migration")
public class TenantAwareToolRegistry implements CodeactToolRegistry {

	private static final Logger logger = LoggerFactory.getLogger(TenantAwareToolRegistry.class);

	private static final Duration SNAPSHOT_TTL = Duration.ofMinutes(5);

	private final CapabilityBridgeToolFactory bridgeToolFactory;

	private final Map<String, SnapshotEntry> snapshotCache = new ConcurrentHashMap<>();

	private volatile DefaultCodeactToolRegistry delegate;

	public TenantAwareToolRegistry(CapabilityBridgeToolFactory bridgeToolFactory) {
		this.bridgeToolFactory = bridgeToolFactory;
		this.delegate = createSessionRegistry("default");
	}

	/**
	 * Build tenant-scoped immutable snapshot registry.
	 */
	public DefaultCodeactToolRegistry createSessionRegistry(String tenantId) {
		String effectiveTenant = StringUtils.hasText(tenantId) ? tenantId : "default";
		SnapshotEntry snapshot = snapshotCache.compute(effectiveTenant, (key, existing) -> {
			if (existing != null && !existing.isExpired()) {
				return existing;
			}
			List<CodeactTool> tools = bridgeToolFactory.createToolsForTenant(key);
			return new SnapshotEntry(Instant.now(), tools);
		});

		DefaultCodeactToolRegistry registry = new DefaultCodeactToolRegistry();
		for (CodeactTool tool : snapshot.tools()) {
			registry.register(tool);
		}
		return registry;
	}

	@EventListener
	public void onToolPublished(ToolPublishedEvent event) {
		String tenantId = event != null ? event.tenantId() : null;
		if (!StringUtils.hasText(tenantId)) {
			tenantId = "default";
		}
		snapshotCache.remove(tenantId);
		if ("default".equals(tenantId)) {
			delegate = createSessionRegistry("default");
		}
		logger.info("TenantAwareToolRegistry#onToolPublished - tenantId={} cache invalidated", tenantId);
	}

	@Override
	public void register(CodeactTool tool) {
		delegate.register(tool);
	}

	@Override
	public Optional<CodeactTool> getTool(String name) {
		return delegate.getTool(name);
	}

	@Override
	public Optional<CodeactTool> getToolByAlias(String alias) {
		return delegate.getToolByAlias(alias);
	}

	@Override
	public List<CodeactTool> getAllTools() {
		return delegate.getAllTools();
	}

	@Override
	public List<CodeactTool> getToolsForLanguage(Language language) {
		return delegate.getToolsForLanguage(language);
	}

	@Override
	public Optional<CodeactToolDefinition> getToolDefinition(String toolName) {
		return delegate.getToolDefinition(toolName);
	}

	@Override
	public Optional<ReturnSchema> getReturnSchema(String toolName) {
		return delegate.getReturnSchema(toolName);
	}

	@Override
	public String generateStructuredToolPrompt(Language language) {
		return delegate.generateStructuredToolPrompt(language);
	}

	@Override
	@Deprecated
	public String generateToolDescriptionPrompt(Language language) {
		return delegate.generateToolDescriptionPrompt(language);
	}

	@Override
	public ReturnSchemaRegistry getReturnSchemaRegistry() {
		return delegate.getReturnSchemaRegistry();
	}

	private record SnapshotEntry(Instant loadedAt, List<CodeactTool> tools) {
		private SnapshotEntry(Instant loadedAt, List<CodeactTool> tools) {
			this.loadedAt = loadedAt;
			this.tools = tools != null ? List.copyOf(new ArrayList<>(tools)) : List.of();
		}

		private boolean isExpired() {
			return loadedAt.plus(SNAPSHOT_TTL).isBefore(Instant.now());
		}
	}

	/**
	 * Tool publish event used for cache invalidation.
	 */
	public record ToolPublishedEvent(String tenantId) {
	}
}
