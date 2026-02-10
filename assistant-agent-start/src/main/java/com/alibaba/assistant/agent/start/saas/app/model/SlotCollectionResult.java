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
package com.alibaba.assistant.agent.start.saas.app.model;

import java.util.List;
import java.util.Map;

/**
 * Slot collection result.
 *
 * @author Assistant Agent Team
 * @since 1.0.0
 */
public class SlotCollectionResult {

    private Map<String, Object> mergedInput;

    private List<String> missingSlots;

    public Map<String, Object> getMergedInput() {
        return mergedInput;
    }

    public void setMergedInput(Map<String, Object> mergedInput) {
        this.mergedInput = mergedInput;
    }

    public List<String> getMissingSlots() {
        return missingSlots;
    }

    public void setMissingSlots(List<String> missingSlots) {
        this.missingSlots = missingSlots;
    }
}
