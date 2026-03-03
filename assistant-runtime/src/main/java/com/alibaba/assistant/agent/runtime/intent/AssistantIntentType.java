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
package com.alibaba.assistant.agent.runtime.intent;

/**
 * Route type decided by {@link AssistantIntentRouter}.
 *
 * @author Assistant Agent Team
 * @since 1.0.0
 */
public enum AssistantIntentType {

	/**
	 * Fast-intent matched, can short-circuit to tool execution.
	 */
	FAST_INTENT,

	/**
	 * Fall back to the normal ReAct loop.
	 */
	MAIN_FLOW

}
