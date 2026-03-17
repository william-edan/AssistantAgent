/*
 * Copyright 2024-2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 */
package com.alibaba.assistant.agent.execution.flow;

import com.alibaba.assistant.agent.execution.model.StepDefinition;
import com.alibaba.assistant.agent.execution.model.StepResult;

import java.util.Map;

public interface FlowExecutionListener {
    default void onStepStarted(StepDefinition step, FlowContext context) { }
    default void onStepProgress(StepDefinition step, Map<String, Object> progressPayload, FlowContext context) { }
    default void onStepCompleted(StepDefinition step, StepResult result, FlowContext context) { }
    default void onStepFailed(StepDefinition step, StepResult result, FlowContext context) { }
    default void onStepSkipped(StepDefinition step, StepResult result, FlowContext context) { }
    default void onStepWaitingApproval(StepDefinition step, FlowContext context) { }
}
