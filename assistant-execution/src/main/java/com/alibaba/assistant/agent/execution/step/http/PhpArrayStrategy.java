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
package com.alibaba.assistant.agent.execution.step.http;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * PHP-style array serialization: key[]=v1&amp;key[]=v2
 *
 * @author Assistant Agent Team
 * @since 1.0.0
 */
public class PhpArrayStrategy implements ArraySerializationStrategy {

	@Override
	public Map<String, List<String>> serialize(String key, List<?> values) {
		Map<String, List<String>> result = new HashMap<>();

		if (values == null || values.isEmpty()) {
			return result;
		}

		List<String> serializedValues = values.stream().map(String::valueOf).collect(Collectors.toList());

		result.put(key + "[]", serializedValues);
		return result;
	}

}
