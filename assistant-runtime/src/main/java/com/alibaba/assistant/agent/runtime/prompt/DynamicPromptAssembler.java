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
package com.alibaba.assistant.agent.runtime.prompt;

import com.alibaba.assistant.agent.prompt.PromptContribution;
import com.alibaba.assistant.agent.prompt.PromptContributorContext;
import com.alibaba.assistant.agent.prompt.PromptContributorManager;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Runtime wrapper of {@link PromptContributorManager} for migration orchestration path.
 *
 * @author Assistant Agent Team
 * @since 1.0.0
 */
@Component
@Profile("migration")
public class DynamicPromptAssembler {

	private final PromptContributorManager promptContributorManager;

	public DynamicPromptAssembler(PromptContributorManager promptContributorManager) {
		this.promptContributorManager = promptContributorManager;
	}

	public PromptContribution assemble(PromptContributorContext context) {
		return promptContributorManager.assemble(context);
	}

}
