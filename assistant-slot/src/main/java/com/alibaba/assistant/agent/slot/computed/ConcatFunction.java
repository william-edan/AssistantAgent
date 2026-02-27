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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Function to concatenate multiple field values or literal strings.
 *
 * @author Assistant Agent Team
 * @since 1.0.0
 */
@Component
public class ConcatFunction implements ComputedFunction {

	private static final Logger logger = LoggerFactory.getLogger(ConcatFunction.class);

	@Override
	public String getName() {
		return "concat";
	}

	@Override
	public Object execute(Map<String, Object> params, ComputationContext context) throws ComputationException {
		Object fieldsParam = params.get("fields");
		String separator = params.getOrDefault("separator", "").toString();

		if (fieldsParam == null) {
			throw new ComputationException("concat requires 'fields' parameter");
		}

		if (!(fieldsParam instanceof List)) {
			throw new ComputationException("'fields' parameter must be a list");
		}

		List<?> fields = (List<?>) fieldsParam;
		if (fields.isEmpty()) {
			throw new ComputationException("'fields' parameter must not be empty");
		}

		StringBuilder result = new StringBuilder();

		for (int i = 0; i < fields.size(); i++) {
			Object fieldObj = fields.get(i);
			if (fieldObj == null) {
				throw new ComputationException("Field at index " + i + " is null");
			}

			String field = fieldObj.toString();
			String value = resolveValue(field, context);

			if (value == null) {
				throw new ComputationException("Cannot resolve value for field '" + field + "'");
			}

			if (i > 0 && !separator.isEmpty()) {
				result.append(separator);
			}
			result.append(value);
		}

		String concatResult = result.toString();
		logger.debug("ConcatFunction#execute - fields={}, separator='{}', result='{}'", fields, separator,
				concatResult);

		return concatResult;
	}

	@Override
	public boolean validate(Map<String, Object> params) {
		if (!params.containsKey("fields")) {
			return false;
		}
		Object fields = params.get("fields");
		return fields instanceof List && !((List<?>) fields).isEmpty();
	}

	private String resolveValue(String param, ComputationContext context) {
		if (param == null) {
			return null;
		}
		if (context.hasValue(param)) {
			Object value = context.getValue(param);
			return value != null ? value.toString() : null;
		}
		return param;
	}

}
