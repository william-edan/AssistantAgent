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
package com.alibaba.assistant.agent.slot;

import com.alibaba.assistant.agent.controlplane.identity.TokenBroker;
import com.alibaba.assistant.agent.slot.model.*;
import com.alibaba.assistant.agent.slot.port.SystemAccessProfilePort;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Method;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
class SlotResolverServiceTest {

	@Mock
	private SystemAccessProfilePort systemAccessProfilePort;

	@Mock
	private TokenBroker tokenBroker;

	private SlotResolverService resolverService;

	@BeforeEach
	void setUp() {
		resolverService = new SlotResolverService(systemAccessProfilePort, tokenBroker, new ObjectMapper());
	}

	@Test
	void shouldResolveSearchKeywordVariable() throws Exception {
		// Given: template with ${search_keyword}
		String template = "/api/employees?keyword=${search_keyword}";
		ResolverContext context = new ResolverContext("user1", "hr");

		// When: resolve with user input "张三"
		String result = invokeResolveVariables(template, "张三", context);

		// Then
		assertEquals("/api/employees?keyword=张三", result);
	}

	@Test
	void shouldResolveSlotNameVariable() throws Exception {
		// Given: template with ${leave_type} referencing a collected slot
		String template = "/api/leave/balance?type=${leave_type}";
		ResolverContext context = new ResolverContext("user1", "hr");
		context.getCollectedSlots().put("leave_type", SlotValue.fromUser("leave_type", "1"));

		// When
		String result = invokeResolveVariables(template, "", context);

		// Then
		assertEquals("/api/leave/balance?type=1", result);
	}

	@Test
	void shouldResolveCollectedParamsVariable() throws Exception {
		// Given: template with ${department_id} from collected params
		String template = "/api/employees?dept=${department_id}";
		ResolverContext context = new ResolverContext("user1", "hr");
		context.getCollectedParams().put("department_id", "D001");

		// When
		String result = invokeResolveVariables(template, "", context);

		// Then
		assertEquals("/api/employees?dept=D001", result);
	}

	@Test
	void shouldReplaceUnknownVariableWithEmpty() throws Exception {
		// Given: template referencing a non-existent variable
		String template = "/api/data?param=${unknown_var}";
		ResolverContext context = new ResolverContext("user1", "hr");

		// When
		String result = invokeResolveVariables(template, "", context);

		// Then
		assertEquals("/api/data?param=", result);
	}

	@Test
	void shouldHandleMultipleVariables() throws Exception {
		// Given: template with multiple variables
		String template = "/api/leave?type=${leave_type}&user=${search_keyword}&dept=${department_id}";
		ResolverContext context = new ResolverContext("user1", "hr");
		context.getCollectedSlots().put("leave_type", SlotValue.fromUser("leave_type", "2"));
		context.getCollectedParams().put("department_id", "D002");

		// When
		String result = invokeResolveVariables(template, "李四", context);

		// Then
		assertEquals("/api/leave?type=2&user=李四&dept=D002", result);
	}

	@Test
	void shouldHandleTemplateWithNoVariables() throws Exception {
		String template = "/api/leave-types";
		ResolverContext context = new ResolverContext("user1", "hr");

		String result = invokeResolveVariables(template, "", context);

		assertEquals("/api/leave-types", result);
	}

	@Test
	void shouldResolveKeywordAlias() throws Exception {
		// ${keyword} should also be resolved as user input
		String template = "/api/search?q=${keyword}";
		ResolverContext context = new ResolverContext("user1", "hr");

		String result = invokeResolveVariables(template, "搜索词", context);

		assertEquals("/api/search?q=搜索词", result);
	}

	@Test
	void shouldReturnEmptyOptionsForNullSlot() {
		List<SlotOptions.OptionValue> result = resolverService.loadOptionsFromApi(null,
				new ResolverContext("user1", "hr"));
		assertTrue(result.isEmpty());
	}

	@Test
	void shouldReturnEmptyOptionsForSlotWithoutApiConfig() {
		SlotDefinition slot = new SlotDefinition();
		slot.setName("reason");
		slot.setType("string");

		List<SlotOptions.OptionValue> result = resolverService.loadOptionsFromApi(slot,
				new ResolverContext("user1", "hr"));
		assertTrue(result.isEmpty());
	}

	/**
	 * Invoke private resolveVariables method via reflection for testing.
	 */
	private String invokeResolveVariables(String template, Object userInput, ResolverContext context) throws Exception {
		Method method = SlotResolverService.class.getDeclaredMethod("resolveVariables", String.class, Object.class,
				ResolverContext.class);
		method.setAccessible(true);
		return (String) method.invoke(resolverService, template, userInput, context);
	}

}
