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
import com.alibaba.assistant.agent.runtime.tool.codeact.ArtifactToolFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Materializes published tool descriptors into runtime CodeactTool instances.
 */
@Component
public class ToolPublicationMaterializer {

	private final ArtifactToolFactory artifactToolFactory;

	public ToolPublicationMaterializer(ArtifactToolFactory artifactToolFactory) {
		this.artifactToolFactory = artifactToolFactory;
	}

	/**
	 * Materialize descriptors in declared provider order.
	 */
	public List<CodeactTool> materialize(List<PublishedToolDescriptor> descriptors) {
		List<CodeactTool> tools = new ArrayList<>();
		Set<String> usedToolNames = new LinkedHashSet<>();
		for (PublishedToolDescriptor descriptor : descriptors != null ? descriptors : List.<PublishedToolDescriptor>of()) {
			if (descriptor == null) {
				continue;
			}
			if (descriptor.isDirectToolPublication() && descriptor.directTool() != null) {
				String toolName = descriptor.directTool().getToolDefinition() != null
						? descriptor.directTool().getToolDefinition().name() : null;
				if (StringUtils.hasText(toolName) && usedToolNames.add(toolName)) {
					tools.add(descriptor.directTool());
				}
				continue;
			}
			Optional<CodeactTool> artifactTool = artifactToolFactory.createTool(descriptor, usedToolNames);
			artifactTool.ifPresent(tools::add);
		}
		return List.copyOf(tools);
	}

}
