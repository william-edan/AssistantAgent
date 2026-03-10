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

import com.alibaba.assistant.agent.common.tools.CodeactTool;
import com.alibaba.assistant.agent.common.tools.CodeactToolMetadata;
import com.alibaba.assistant.agent.runtime.compiler.RuntimeArtifact;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.util.StringUtils;

/**
 * Published tool descriptor consumed by the runtime registry.
 */
public record PublishedToolDescriptor(
		String sourceType,
		String publicationKey,
		String displayName,
		String targetClassName,
		String targetClassDescription,
		boolean alwaysAvailable,
		String executionSystemCode,
		RuntimeArtifact artifact,
		CodeactTool directTool) {

	public PublishedToolDescriptor(
			String sourceType,
			String publicationKey,
			String displayName,
			String targetClassName,
			String targetClassDescription,
			boolean alwaysAvailable,
			String executionSystemCode,
			RuntimeArtifact artifact) {
		this(sourceType, publicationKey, displayName, targetClassName, targetClassDescription,
				alwaysAvailable, executionSystemCode, artifact, null);
	}

	public static PublishedToolDescriptor forArtifact(
			String sourceType,
			String publicationKey,
			String displayName,
			String targetClassName,
			String targetClassDescription,
			boolean alwaysAvailable,
			String executionSystemCode,
			RuntimeArtifact artifact) {
		return new PublishedToolDescriptor(sourceType, publicationKey, displayName, targetClassName,
				targetClassDescription, alwaysAvailable, executionSystemCode, artifact, null);
	}

	public static PublishedToolDescriptor forDirectTool(
			String sourceType,
			String publicationKey,
			String displayName,
			CodeactTool directTool) {
		CodeactToolMetadata metadata = directTool != null ? directTool.getCodeactMetadata() : null;
		ToolDefinition definition = directTool != null ? directTool.getToolDefinition() : null;
		return new PublishedToolDescriptor(
				sourceType,
				publicationKey,
				firstNonBlank(displayName,
						metadata != null ? metadata.displayName() : null,
						definition != null ? definition.description() : null,
						definition != null ? definition.name() : null),
				metadata != null ? metadata.targetClassName() : null,
				metadata != null ? metadata.targetClassDescription() : null,
				metadata != null && metadata.alwaysAvailable(),
				null,
				null,
				directTool);
	}

	public boolean isArtifactPublication() {
		return artifact != null;
	}

	public boolean isDirectToolPublication() {
		return directTool != null;
	}

	private static String firstNonBlank(String... values) {
		if (values == null) {
			return null;
		}
		for (String value : values) {
			if (StringUtils.hasText(value)) {
				return value;
			}
		}
		return null;
	}

}
