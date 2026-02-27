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
package com.alibaba.assistant.agent.slot.computed;

import java.util.Map;

/**
 * Interface for computed field functions.
 * Functions are used to calculate field values based on other fields.
 *
 * @author Assistant Agent Team
 * @since 1.0.0
 */
public interface ComputedFunction {

	String getName();

	Object execute(Map<String, Object> params, ComputationContext context) throws ComputationException;

	default boolean validate(Map<String, Object> params) {
		return true;
	}

	class ComputationException extends Exception {

		public ComputationException(String message) {
			super(message);
		}

		public ComputationException(String message, Throwable cause) {
			super(message, cause);
		}

	}

}
