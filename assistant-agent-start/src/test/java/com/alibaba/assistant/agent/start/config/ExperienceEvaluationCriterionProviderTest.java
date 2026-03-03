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
package com.alibaba.assistant.agent.start.config;

import com.alibaba.assistant.agent.evaluation.model.EvaluationCriterion;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExperienceEvaluationCriterionProviderTest {

	@Test
	void shouldMakeIsFuzzyDependOnEnhancedInput() {
		ExperienceEvaluationCriterionProvider provider = new ExperienceEvaluationCriterionProvider();

		List<EvaluationCriterion> criteria = provider.getReactPhaseCriteria();
		EvaluationCriterion isFuzzy = criteria.stream()
				.filter(item -> "is_fuzzy".equals(item.getName()))
				.findFirst()
				.orElse(null);

		assertNotNull(isFuzzy);
		assertTrue(isFuzzy.getDependsOn().contains("enhanced_user_input"));
		assertTrue(isFuzzy.getContextBindings().contains("context.input.userInput"));
		assertTrue(isFuzzy.getContextBindings().contains("dependencies.enhanced_user_input.value"));
		assertTrue(isFuzzy.getWorkingMechanism().contains("缺少槽位参数不等于模糊"));
	}

}
