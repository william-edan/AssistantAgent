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

import com.alibaba.assistant.agent.slot.model.ComputedFieldConfig;
import com.alibaba.assistant.agent.slot.model.SlotDefinition;
import com.alibaba.assistant.agent.slot.model.SlotValue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Service for processing computed fields in slot definitions.
 * Automatically calculates field values based on other collected values.
 *
 * @author Assistant Agent Team
 * @since 1.0.0
 */
@Service
public class ComputedFieldProcessor {

	private static final Logger logger = LoggerFactory.getLogger(ComputedFieldProcessor.class);

	private final Map<String, ComputedFunction> functions;

	public ComputedFieldProcessor(List<ComputedFunction> functionList) {
		this.functions = new HashMap<>();
		for (ComputedFunction function : functionList) {
			this.functions.put(function.getName(), function);
			logger.info("ComputedFieldProcessor#init - registered function: {}", function.getName());
		}
	}

	/**
	 * Process all computed fields and add calculated values to slot values.
	 *
	 * @param slotDefinitions list of slot definitions
	 * @param slotValues collected slot values (will be modified with computed values)
	 */
	public void processComputedFields(List<SlotDefinition> slotDefinitions, Map<String, SlotValue> slotValues) {
		if (slotDefinitions == null || slotValues == null) {
			return;
		}

		List<SlotDefinition> computedSlots = slotDefinitions.stream()
			.filter(SlotDefinition::isComputed)
			.collect(Collectors.toList());

		if (computedSlots.isEmpty()) {
			return;
		}

		logger.info("ComputedFieldProcessor#processComputedFields - processing {} computed fields",
				computedSlots.size());

		// Build computation context from collected slot values
		Map<String, Object> contextValues = new HashMap<>();
		for (Map.Entry<String, SlotValue> entry : slotValues.entrySet()) {
			contextValues.put(entry.getKey(), entry.getValue().getResolvedValue());
		}
		ComputationContext context = new ComputationContext(contextValues);

		for (SlotDefinition slot : computedSlots) {
			try {
				if (!areDependenciesSatisfied(slot, context)) {
					logger.debug("ComputedFieldProcessor#processComputedFields - skipping, deps unsatisfied: name={}",
							slot.getName());
					continue;
				}

				Object computedValue = computeField(slot, context);
				if (computedValue != null) {
					SlotValue slotValue = SlotValue.resolved(slot.getName(), computedValue.toString(), computedValue,
							computedValue.toString());
					slotValues.put(slot.getName(), slotValue);
					context.setValue(slot.getName(), computedValue);

					logger.info("ComputedFieldProcessor#processComputedFields - computed: name={}, value={}",
							slot.getName(), computedValue);
				}
			}
			catch (Exception e) {
				logger.warn("ComputedFieldProcessor#processComputedFields - failed: name={}, error={}",
						slot.getName(), e.getMessage());

				Object defaultValue = slot.getComputed().getDefaultValue();
				if (defaultValue != null) {
					SlotValue slotValue = SlotValue.resolved(slot.getName(), defaultValue.toString(), defaultValue,
							defaultValue.toString());
					slotValues.put(slot.getName(), slotValue);
				}
			}
		}
	}

	private Object computeField(SlotDefinition slot, ComputationContext context)
			throws ComputedFunction.ComputationException {
		ComputedFieldConfig config = slot.getComputed();

		switch (config.getType()) {
			case FUNCTION:
				return computeByFunction(config, context);
			case EXPRESSION:
				return computeByExpression(config, context);
			case SCRIPT:
				throw new UnsupportedOperationException("Script-based computation not yet supported");
			default:
				throw new ComputedFunction.ComputationException("Unknown computation type: " + config.getType());
		}
	}

	private Object computeByFunction(ComputedFieldConfig config, ComputationContext context)
			throws ComputedFunction.ComputationException {
		String functionName = config.getFunction();
		if (functionName == null || functionName.isEmpty()) {
			throw new ComputedFunction.ComputationException("Function name not specified");
		}

		ComputedFunction function = functions.get(functionName);
		if (function == null) {
			throw new ComputedFunction.ComputationException("Unknown function: " + functionName);
		}

		Map<String, Object> params = config.getParams();
		if (params == null) {
			params = Collections.emptyMap();
		}

		if (!function.validate(params)) {
			throw new ComputedFunction.ComputationException("Invalid parameters for function: " + functionName);
		}

		return function.execute(params, context);
	}

	private Object computeByExpression(ComputedFieldConfig config, ComputationContext context)
			throws ComputedFunction.ComputationException {
		String expression = config.getExpression();
		if (expression == null || expression.isEmpty()) {
			throw new ComputedFunction.ComputationException("Expression not specified");
		}
		throw new UnsupportedOperationException("Expression-based computation not yet implemented");
	}

	private static final Set<String> LITERAL_VALUES = Set.of("true", "false", "yes", "no", "days", "weeks", "months",
			"years", "hours", "minutes", "seconds");

	private boolean areDependenciesSatisfied(SlotDefinition slot, ComputationContext context) {
		ComputedFieldConfig config = slot.getComputed();

		if (config.getType() == ComputedFieldConfig.ComputationType.FUNCTION) {
			Map<String, Object> params = config.getParams();
			if (params == null) {
				return true;
			}

			for (Object value : params.values()) {
				if (!isParamDependenciesSatisfied(value, context)) {
					return false;
				}
			}
		}

		return true;
	}

	private boolean isParamDependenciesSatisfied(Object value, ComputationContext context) {
		if (value == null) {
			return true;
		}
		if (value instanceof String) {
			String fieldName = (String) value;
			if (fieldName.matches("\\d+") || LITERAL_VALUES.contains(fieldName.toLowerCase())
					|| !fieldName.matches("[a-zA-Z_][a-zA-Z0-9_]*")) {
				return true;
			}
			return context.hasValue(fieldName);
		}
		if (value instanceof List<?>) {
			for (Object item : (List<?>) value) {
				if (!isParamDependenciesSatisfied(item, context)) {
					return false;
				}
			}
			return true;
		}
		if (value instanceof Map<?, ?>) {
			for (Object nested : ((Map<?, ?>) value).values()) {
				if (!isParamDependenciesSatisfied(nested, context)) {
					return false;
				}
			}
			return true;
		}
		return true;
	}

}
