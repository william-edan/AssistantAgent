/*
 * Copyright 2024-2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 */
package com.alibaba.assistant.agent.execution.batch;

import com.alibaba.assistant.agent.execution.flow.FlowContext;
import com.alibaba.assistant.agent.execution.model.StepConfig;
import com.alibaba.assistant.agent.execution.model.StepDefinition;
import com.alibaba.assistant.agent.execution.model.StepResult;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class BatchStepExecutor {

    private final BatchItemSelector batchItemSelector;
    private final BatchAggregationPolicy batchAggregationPolicy;

    public BatchStepExecutor(BatchItemSelector batchItemSelector, BatchAggregationPolicy batchAggregationPolicy) {
        this.batchItemSelector = batchItemSelector;
        this.batchAggregationPolicy = batchAggregationPolicy;
    }

    public StepResult execute(StepDefinition step, FlowContext context) {
        if (step == null || step.getConfig() == null) {
            return StepResult.failure("batch_step_config_missing");
        }
        StepConfig config = step.getConfig();
        Map<String, Object> selectorArguments = resolveArguments(config.getInputMapping(), context);
        List<Map<String, Object>> items = batchItemSelector.selectItems(config.getSelectorToolCode(), selectorArguments, context);
        List<Map<String, Object>> selectedItems = (items == null ? List.<Map<String, Object>>of() : items)
                .stream()
                .filter(item -> matchesFilter(item, config.getFilterExpression()))
                .toList();
        List<ItemExecutionResult> results = new ArrayList<>();
        for (Map<String, Object> item : selectedItems) {
            Map<String, Object> actionArguments = new LinkedHashMap<>(item);
            actionArguments.putAll(selectorArguments);
            StepResult actionResult = batchItemSelector.executeAction(config.getActionToolCode(), actionArguments, context);
            results.add(new ItemExecutionResult(resolveItemId(item), actionResult != null && actionResult.isSuccess(), actionResult));
        }
        Map<String, Object> outputs = batchAggregationPolicy.aggregate(items == null ? 0 : items.size(), selectedItems.size(), results);
        boolean allSucceeded = results.stream().allMatch(ItemExecutionResult::success);
        if (allSucceeded) {
            return StepResult.success(outputs);
        }
        StepResult failed = StepResult.failure("batch_step_failed");
        failed.setOutputs(outputs);
        return failed;
    }

    private Map<String, Object> resolveArguments(Map<String, String> mappings, FlowContext context) {
        Map<String, Object> resolved = new LinkedHashMap<>();
        if (mappings == null || mappings.isEmpty()) {
            return resolved;
        }
        for (Map.Entry<String, String> entry : mappings.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof String text && context != null) {
                value = context.resolve(text);
            }
            resolved.put(entry.getKey(), value);
        }
        return resolved;
    }

    private boolean matchesFilter(Map<String, Object> item, String filterExpression) {
        if (item == null || !StringUtils.hasText(filterExpression)) {
            return true;
        }
        String normalized = filterExpression.trim();
        if (!normalized.startsWith("$.") || !normalized.contains("==")) {
            return true;
        }
        String[] tokens = normalized.split("==", 2);
        String field = tokens[0].trim().substring(2);
        String expected = tokens[1].trim();
        if ((expected.startsWith("'") && expected.endsWith("'")) || (expected.startsWith("\"") && expected.endsWith("\""))) {
            expected = expected.substring(1, expected.length() - 1);
        }
        Object actual = item.get(field);
        return actual != null && expected.equals(String.valueOf(actual));
    }

    private String resolveItemId(Map<String, Object> item) {
        for (String key : List.of("approvalId", "id", "itemId", "code")) {
            Object value = item.get(key);
            if (value != null && StringUtils.hasText(String.valueOf(value))) {
                return String.valueOf(value);
            }
        }
        return "unknown";
    }

    public record ItemExecutionResult(String itemId, boolean success, StepResult result) {
    }
}
