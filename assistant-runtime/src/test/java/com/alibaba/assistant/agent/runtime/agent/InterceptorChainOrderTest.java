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
package com.alibaba.assistant.agent.runtime.agent;

import com.alibaba.assistant.agent.controlplane.audit.AuditEventService;
import com.alibaba.assistant.agent.controlplane.identity.TokenBroker;
import com.alibaba.assistant.agent.controlplane.toolregistry.ToolMetaService;
import com.alibaba.assistant.agent.runtime.config.AssistantRuntimeProperties;
import com.alibaba.assistant.agent.runtime.config.RuntimeConfigCompatibilityAdapter;
import com.alibaba.assistant.agent.runtime.guard.BudgetTracker;
import com.alibaba.assistant.agent.runtime.interceptor.AuditToolInterceptor;
import com.alibaba.assistant.agent.runtime.interceptor.HumanInTheLoopToolInterceptor;
import com.alibaba.assistant.agent.runtime.interceptor.IdentityEnricherToolInterceptor;
import com.alibaba.assistant.agent.runtime.interceptor.InternalPromptContributionToolInterceptor;
import com.alibaba.assistant.agent.runtime.interceptor.PolicyCheckModelInterceptor;
import com.alibaba.assistant.agent.runtime.interceptor.PolicyGuardToolInterceptor;
import com.alibaba.cloud.ai.graph.agent.interceptor.Interceptor;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

class InterceptorChainOrderTest {

	@Test
	void shouldSortInterceptorsByExplicitOrder() {
		TokenBroker tokenBroker = mock(TokenBroker.class);
		ToolMetaService toolMetaService = mock(ToolMetaService.class);
		AuditEventService auditEventService = mock(AuditEventService.class);

		RuntimeConfigCompatibilityAdapter adapter = new RuntimeConfigCompatibilityAdapter(
				new AssistantRuntimeProperties(),
				new MockEnvironment());
		BudgetTracker budgetTracker = new BudgetTracker(adapter);

		List<Interceptor> input = List.of(
				new AuditToolInterceptor(auditEventService),
				new HumanInTheLoopToolInterceptor(new ObjectMapper()),
				new PolicyGuardToolInterceptor(new ObjectMapper(), toolMetaService, budgetTracker, adapter),
				new IdentityEnricherToolInterceptor(tokenBroker, new ObjectMapper()),
				new InternalPromptContributionToolInterceptor(),
				new PolicyCheckModelInterceptor());

		List<Interceptor> ordered = AssistantAgentFactory.orderInterceptors(input);

		assertEquals("PolicyCheckModelInterceptor", ordered.get(0).getName());
		assertEquals("InternalPromptContributionToolInterceptor", ordered.get(1).getName());
		assertEquals("IdentityEnricherToolInterceptor", ordered.get(2).getName());
		assertEquals("PolicyGuardToolInterceptor", ordered.get(3).getName());
		assertEquals("HumanInTheLoopToolInterceptor", ordered.get(4).getName());
		assertEquals("AuditToolInterceptor", ordered.get(5).getName());
	}

}
