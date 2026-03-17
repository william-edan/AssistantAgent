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
import com.alibaba.assistant.agent.execution.model.StepResult;

import java.util.List;
import java.util.Map;

public interface BatchItemSelector {
    List<Map<String, Object>> selectItems(String selectorToolCode, Map<String, Object> arguments, FlowContext context);
    StepResult executeAction(String actionToolCode, Map<String, Object> arguments, FlowContext context);
}
