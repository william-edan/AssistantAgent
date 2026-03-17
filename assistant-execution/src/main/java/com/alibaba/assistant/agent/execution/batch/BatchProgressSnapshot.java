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
import java.util.Map;

public record BatchProgressSnapshot(
        int totalItems,
        int selectedItems,
        int processedItems,
        int succeededItems,
        int failedItems,
        int percent) {

    public Map<String, Object> toMap() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("totalItems", totalItems);
        payload.put("selectedItems", selectedItems);
        payload.put("processedItems", processedItems);
        payload.put("succeededItems", succeededItems);
        payload.put("failedItems", failedItems);
        payload.put("percent", percent);
        return payload;
    }
}
