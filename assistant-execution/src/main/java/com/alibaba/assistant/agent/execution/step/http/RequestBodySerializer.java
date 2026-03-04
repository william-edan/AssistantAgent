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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Serializes request parameters to form-urlencoded format
 * using configurable array strategies.
 *
 * @author Assistant Agent Team
 * @since 1.0.0
 */
@Component
public class RequestBodySerializer {

	private static final Logger logger = LoggerFactory.getLogger(RequestBodySerializer.class);

	/**
	 * Gougu OA style uid collections are accepted as comma-separated strings.
	 */
	private static final Set<String> COMMA_SEPARATED_ARRAY_KEYS = Set.of(
			"to_uids",
			"check_uids",
			"check_copy_uids",
			"join_uids");

	private ArraySerializationStrategy arrayStrategy;

	public RequestBodySerializer() {
		this.arrayStrategy = new PhpArrayStrategy();
	}

	public RequestBodySerializer(ArraySerializationStrategy arrayStrategy) {
		this.arrayStrategy = arrayStrategy;
	}

	/**
	 * Convert params to form-urlencoded format.
	 */
	public MultiValueMap<String, String> toFormUrlEncoded(Map<String, Object> params) {
		MultiValueMap<String, String> result = new LinkedMultiValueMap<>();

		if (params == null) {
			return result;
		}

		for (Map.Entry<String, Object> entry : params.entrySet()) {
			String key = entry.getKey();
			Object value = entry.getValue();

			if (value == null) {
				continue;
			}

			if (value instanceof List) {
				List<?> listValue = (List<?>) value;
				if (shouldSerializeAsCommaSeparated(key)) {
					String joined = listValue.stream()
						.filter(item -> item != null && StringUtils.hasText(String.valueOf(item)))
						.map(String::valueOf)
						.collect(Collectors.joining(","));
					if (StringUtils.hasText(joined)) {
						result.add(key, joined);
					}
					continue;
				}
				Map<String, List<String>> serialized = arrayStrategy.serialize(key, listValue);
				result.putAll(serialized);
			}
			else {
				result.add(key, String.valueOf(value));
			}
		}

		logger.debug("RequestBodySerializer#toFormUrlEncoded - serialized keys={}", result.keySet());

		return result;
	}

	public void setArrayStrategy(ArraySerializationStrategy strategy) {
		this.arrayStrategy = strategy;
	}

	private boolean shouldSerializeAsCommaSeparated(String key) {
		if (!StringUtils.hasText(key)) {
			return false;
		}
		return COMMA_SEPARATED_ARRAY_KEYS.contains(key.trim().toLowerCase());
	}

}
