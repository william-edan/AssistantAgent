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

import com.alibaba.assistant.agent.execution.flow.FlowDefinition;
import com.alibaba.assistant.agent.runtime.compiler.RuntimeArtifact;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class ToolPublicationProviderTest {

	@Test
	void shouldListPublishedToolDescriptorsForTenantAndSpaceScope() {
		RuntimeArtifact artifact = new RuntimeArtifact(
				1L,
				"oa.leave.apply",
				RuntimeArtifact.ArtifactType.WORKFLOW,
				"请假申请",
				3,
				"max_step_risk",
				"strictest_step_policy",
				null,
				null,
				null,
				new FlowDefinition(),
				Map.of(),
				Map.of());
		PublishedToolDescriptor descriptor = new PublishedToolDescriptor(
				"tool-meta-catalog",
				"workflow:oa.leave.apply",
				"请假申请",
				"oa_tools",
				"OA workflow tools",
				false,
				"gougu_oa",
				artifact);

		ToolPublicationProvider provider = scope -> {
			assertEquals("default", scope.tenantId());
			assertEquals(1L, scope.spaceId());
			assertEquals("prod", scope.environment());
			assertEquals("hr-assistant", scope.agentAppCode());
			return List.of(descriptor);
		};

		List<PublishedToolDescriptor> published =
				provider.listPublishedTools(new ToolPublicationProvider.PublicationScope(
						"default", 1L, "prod", "hr-assistant"));

		assertEquals(1, published.size());
		assertEquals("tool-meta-catalog", published.get(0).sourceType());
		assertEquals("gougu_oa", published.get(0).executionSystemCode());
		assertSame(artifact, published.get(0).artifact());
	}

}

