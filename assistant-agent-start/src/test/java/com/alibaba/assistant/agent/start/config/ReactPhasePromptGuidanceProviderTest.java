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

import com.alibaba.assistant.agent.evaluation.model.CriterionResult;
import com.alibaba.assistant.agent.evaluation.model.CriterionStatus;
import com.alibaba.assistant.agent.evaluation.model.EvaluationResult;
import com.alibaba.assistant.agent.extension.evaluation.store.OverAllStateEvaluationResultStore;
import com.alibaba.assistant.agent.extension.prompt.OverAllStatePromptContributorContext;
import com.alibaba.assistant.agent.prompt.PromptContribution;
import com.alibaba.cloud.ai.graph.OverAllState;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReactPhasePromptGuidanceProviderTest {

	@Test
	void shouldGuideSlotCollectionWhenFuzzyButOperationIntent() {
		ReactPhasePromptGuidanceProvider provider = new ReactPhasePromptGuidanceProvider();
		OverAllState state = new OverAllState();
		state.updateState(Map.of(
				OverAllStateEvaluationResultStore.EVALUATION_RESULTS_KEY,
				Map.of("react-phase-suite", buildEvaluationResult("模糊", "我明天有点事情需要请假一天"))));

		OverAllStatePromptContributorContext context =
				new OverAllStatePromptContributorContext(state, null, "REACT");

		assertTrue(provider.shouldContribute(context));
		PromptContribution contribution = provider.contribute(context);

		assertFalse(contribution.isEmpty());
		UserMessage userMessage = (UserMessage) contribution.messagesToAppend().get(0);
		assertTrue(userMessage.getText().contains("直接进入 slot_collect 流程"));
		assertFalse(userMessage.getText().contains("提供几个可能的理解方向"));
	}

	@Test
	void shouldKeepClarificationGuidanceWhenFuzzyAndNotOperation() {
		ReactPhasePromptGuidanceProvider provider = new ReactPhasePromptGuidanceProvider();
		OverAllState state = new OverAllState();
		state.updateState(Map.of(
				OverAllStateEvaluationResultStore.EVALUATION_RESULTS_KEY,
				Map.of("react-phase-suite", buildEvaluationResult("模糊", "帮我处理一下这个问题"))));

		OverAllStatePromptContributorContext context =
				new OverAllStatePromptContributorContext(state, null, "REACT");

		assertTrue(provider.shouldContribute(context));
		PromptContribution contribution = provider.contribute(context);

		assertFalse(contribution.isEmpty());
		UserMessage userMessage = (UserMessage) contribution.messagesToAppend().get(0);
		assertTrue(userMessage.getText().contains("先向用户澄清具体的需求和意图"));
	}

	private EvaluationResult buildEvaluationResult(String isFuzzy, String enhancedInput) {
		EvaluationResult result = new EvaluationResult();
		result.setSuiteId("react-phase-suite");

		CriterionResult fuzzy = new CriterionResult();
		fuzzy.setCriterionName("is_fuzzy");
		fuzzy.setStatus(CriterionStatus.SUCCESS);
		fuzzy.setValue(isFuzzy);
		result.addCriterionResult("is_fuzzy", fuzzy);

		CriterionResult enhanced = new CriterionResult();
		enhanced.setCriterionName("enhanced_user_input");
		enhanced.setStatus(CriterionStatus.SUCCESS);
		enhanced.setValue(enhancedInput);
		result.addCriterionResult("enhanced_user_input", enhanced);
		return result;
	}

}
