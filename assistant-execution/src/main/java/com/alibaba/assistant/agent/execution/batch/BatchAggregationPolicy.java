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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class BatchAggregationPolicy {

    public Map<String, Object> aggregate(
            int totalItems,
            int selectedItems,
            List<BatchStepExecutor.ItemExecutionResult> results) {
        int succeededItems = (int) results.stream().filter(BatchStepExecutor.ItemExecutionResult::success).count();
        int failedItems = results.size() - succeededItems;
        BatchProgressSnapshot snapshot = new BatchProgressSnapshot(
                totalItems,
                selectedItems,
                results.size(),
                succeededItems,
                failedItems,
                selectedItems <= 0 ? 100 : Math.min(100, (results.size() * 100) / selectedItems));
        Map<String, Object> outputs = new LinkedHashMap<>();
        outputs.put("totalItems", totalItems);
        outputs.put("selectedItems", selectedItems);
        outputs.put("processedItems", results.size());
        outputs.put("succeededItems", succeededItems);
        outputs.put("failedItems", failedItems);
        outputs.put("processedItemIds", results.stream().map(BatchStepExecutor.ItemExecutionResult::itemId).toList());
        outputs.put("batchProgress", snapshot.toMap());
        return outputs;
    }
}
