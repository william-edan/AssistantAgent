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

import com.alibaba.assistant.agent.runtime.tool.react.AssistantReactToolConfiguration;
import org.springframework.util.StringUtils;

import java.util.Map;

/**
 * Resolves the runtime agent profile from conversation state.
 */
public class AgentProfileResolver {

	private final AgentPromptTemplateFactory promptTemplateFactory;

	public AgentProfileResolver(AgentPromptTemplateFactory promptTemplateFactory) {
		this.promptTemplateFactory = promptTemplateFactory;
	}

	/**
	 * Resolve the effective profile from request or thread state.
	 *
	 * @param state conversation state snapshot
	 * @return resolved immutable profile definition
	 */
	public AgentProfile resolve(Map<String, Object> state) {
		String profileCode = hasRolePackageContext(state) ? AgentProfile.ROLE_PACKAGE_CHAT : AgentProfile.FORM_FLOW;
		return new AgentProfile(
				profileCode,
				promptTemplateFactory.systemPromptFor(profileCode),
				AssistantReactToolConfiguration.builtInReactToolNames());
	}

	private boolean hasRolePackageContext(Map<String, Object> state) {
		if (state == null || state.isEmpty()) {
			return false;
		}
		return hasText(state.get("role_package_code"))
				|| hasText(state.get("rolePackageCode"))
				|| hasText(state.get("role_package_version"))
				|| hasText(state.get("rolePackageVersion"));
	}

	private boolean hasText(Object value) {
		return value != null && StringUtils.hasText(String.valueOf(value));
	}
}
