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
package com.alibaba.assistant.agent.execution.flow;

import com.alibaba.assistant.agent.controlplane.toolregistry.ToolMeta;
import com.alibaba.assistant.agent.execution.model.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Converts ToolMeta execution plan JSON into DAG FlowDefinition objects.
 * Supports DAG format parsing and fallback to single-step flow.
 *
 * @author Assistant Agent Team
 * @since 1.0.0
 */
@Component
public class FlowDefinitionConverter {

	private static final Logger logger = LoggerFactory.getLogger(FlowDefinitionConverter.class);

	private final ObjectMapper objectMapper;

	public FlowDefinitionConverter(ObjectMapper objectMapper) {
		this.objectMapper = objectMapper;
	}

	/**
	 * Parse flow definition from ToolMeta's executionPlan field.
	 * Falls back to single-step flow when no definition exists.
	 */
	public FlowDefinition parseFromToolMeta(ToolMeta toolMeta) {
		if (toolMeta == null) {
			logger.warn("FlowDefinitionConverter#parseFromToolMeta - toolMeta is null");
			return createEmptyFlow();
		}

		return parseFromExecutionPlan(toolMeta.getExecutionPlan(), toolMeta);
	}

	/**
	 * Parse flow definition from an execution plan JSON string.
	 * Compatible with both new executionPlan format and legacy flow_steps format.
	 */
	public FlowDefinition parseFromExecutionPlan(String executionPlanJson, ToolMeta toolMeta) {
		// Try parsing DAG format from executionPlan
		if (executionPlanJson != null && !executionPlanJson.trim().isEmpty()) {
			try {
				logger.info("FlowDefinitionConverter#parseFromExecutionPlan - parsing DAG, toolCode={}",
						toolMeta != null ? toolMeta.getToolCode() : "unknown");
				return objectMapper.readValue(executionPlanJson, FlowDefinition.class);
			}
			catch (Exception e) {
				logger.error("FlowDefinitionConverter#parseFromExecutionPlan - parse failed, error={}", e.getMessage());
			}
		}

		// Fallback: create single-step flow from ToolMeta fields
		if (toolMeta != null) {
			logger.info("FlowDefinitionConverter#parseFromExecutionPlan - creating single-step flow, toolCode={}",
					toolMeta.getToolCode());
			return createSingleStepFlow(toolMeta);
		}

		return createEmptyFlow();
	}

	/**
	 * Create a single-step flow from ToolMeta HTTP endpoint info.
	 */
	private FlowDefinition createSingleStepFlow(ToolMeta toolMeta) {
		StepConfig config = new StepConfig();
		config.setMethod(toolMeta.getHttpMethod());
		config.setEndpoint(toolMeta.getApiEndpoint());
		config.setContentType(toolMeta.getContentType());

		StepDefinition step = new StepDefinition();
		step.setStepId("main");
		step.setName("Execute " + toolMeta.getToolName());
		step.setType(StepType.HTTP);
		step.setConfig(config);
		step.setJoinType(JoinType.ALL);

		FlowDefinition flow = new FlowDefinition();
		flow.setVersion("2.0");
		flow.setSteps(Map.of("main", step));
		flow.setEntry(List.of("main"));
		flow.setTerminal(List.of("main"));

		return flow;
	}

	private FlowDefinition createEmptyFlow() {
		FlowDefinition flow = new FlowDefinition();
		flow.setVersion("2.0");
		flow.setSteps(Collections.emptyMap());
		flow.setEntry(Collections.emptyList());
		flow.setTerminal(Collections.emptyList());
		return flow;
	}

	/**
	 * Validate flow definition structure.
	 */
	public boolean validate(FlowDefinition flow) {
		if (flow == null) {
			logger.error("FlowDefinitionConverter#validate - flow is null");
			return false;
		}

		if (flow.getSteps() == null || flow.getSteps().isEmpty()) {
			logger.error("FlowDefinitionConverter#validate - flow has no steps");
			return false;
		}

		if (flow.getEntry() == null || flow.getEntry().isEmpty()) {
			logger.error("FlowDefinitionConverter#validate - flow has no entry point");
			return false;
		}

		for (StepDefinition step : flow.getSteps().values()) {
			if (step.getNext() != null) {
				for (String nextId : step.getNext()) {
					if (!flow.getSteps().containsKey(nextId)) {
						logger.error("FlowDefinitionConverter#validate - step {} references non-existent next: {}",
								step.getStepId(), nextId);
						return false;
					}
				}
			}
			if (step.getDependsOn() != null) {
				for (String depId : step.getDependsOn()) {
					if (!flow.getSteps().containsKey(depId)) {
						logger.error("FlowDefinitionConverter#validate - step {} references non-existent dep: {}",
								step.getStepId(), depId);
						return false;
					}
				}
			}
		}

		logger.info("FlowDefinitionConverter#validate - passed, stepsCount={}, entry={}, terminal={}",
				flow.getSteps().size(), flow.getEntry(), flow.getTerminal());
		return true;
	}

}
