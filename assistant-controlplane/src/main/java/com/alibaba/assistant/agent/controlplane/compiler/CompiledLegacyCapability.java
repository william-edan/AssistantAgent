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
package com.alibaba.assistant.agent.controlplane.compiler;

import com.alibaba.assistant.agent.controlplane.action.ActionSpec;
import com.alibaba.assistant.agent.controlplane.interaction.InteractionSpec;
import com.alibaba.assistant.agent.controlplane.workflow.WorkflowSpec;
import com.alibaba.assistant.agent.controlplane.workflow.WorkflowStep;

import java.util.List;

/**
 * In-memory result of compiling one legacy capability into new control-plane artifacts.
 *
 * @param baseCode canonical capability code
 * @param interactionSpec compiled interaction definition
 * @param actionSpecs compiled action definitions
 * @param workflowSpec compiled workflow definition if capability is flow-based
 * @param workflowSteps compiled workflow steps for flow-based capability
 */
public record CompiledLegacyCapability(
        String baseCode,
        InteractionSpec interactionSpec,
        List<ActionSpec> actionSpecs,
        WorkflowSpec workflowSpec,
        List<WorkflowStep> workflowSteps) {
}
