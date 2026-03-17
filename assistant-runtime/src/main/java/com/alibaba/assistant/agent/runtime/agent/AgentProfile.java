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

import java.util.Set;

/**
 * Agent runtime profile snapshot.
 *
 * <p>Profiles control the system prompt and which built-in React tools remain exposed
 * for a given interaction mode.</p>
 */
public record AgentProfile(
		String profileCode,
		String systemPrompt,
		Set<String> builtinReactTools) {

	public static final String FORM_FLOW = "FORM_FLOW";

	public static final String ROLE_PACKAGE_CHAT = "ROLE_PACKAGE_CHAT";

	public AgentProfile {
		builtinReactTools = builtinReactTools != null ? Set.copyOf(builtinReactTools) : Set.of();
	}

	public boolean isRolePackageChat() {
		return ROLE_PACKAGE_CHAT.equals(profileCode);
	}
}
