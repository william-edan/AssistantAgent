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
package com.alibaba.assistant.agent.runtime.tool.codeact;

import com.alibaba.assistant.agent.common.enums.Language;
import com.alibaba.assistant.agent.common.tools.CodeExample;
import com.alibaba.assistant.agent.common.tools.CodeactToolMetadata;
import com.alibaba.assistant.agent.common.tools.DefaultCodeactToolMetadata;
import com.alibaba.assistant.agent.runtime.agent.AssistantStateKeys;
import com.alibaba.assistant.agent.runtime.policy.SqlGuardPolicy;
import com.alibaba.assistant.agent.extension.dynamic.tool.AbstractDynamicCodeactTool;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.agent.tools.ToolContextConstants;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.context.annotation.Profile;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;

/**
 * DataAgent query tool for CodeAct phase.
 * Supports guarded SQL execution with row-level filter and masking.
 *
 * @author Assistant Agent Team
 * @since 1.0.0
 */
@Component
@Profile("migration")
public class DataAgentQueryTool extends AbstractDynamicCodeactTool {

	private static final String TOOL_NAME = "data_agent_query";

	private final DataAgentQueryService queryService;

	private final SqlGuardPolicy sqlGuardPolicy;

	public DataAgentQueryTool(ObjectMapper objectMapper,
			DataAgentQueryService queryService,
			SqlGuardPolicy sqlGuardPolicy) {
		super(objectMapper, buildToolDefinition(), buildMetadata());
		this.queryService = queryService;
		this.sqlGuardPolicy = sqlGuardPolicy;
	}

	@Override
	protected String doCall(Map<String, Object> args, @Nullable ToolContext toolContext) throws Exception {
		String query = asText(args.get("query"));
		String agentId = firstNonBlank(
				asText(args.get("agent_id")),
				asText(args.get("agentId")));
		String assistantUid = firstNonBlank(
				asText(args.get("assistant_uid")),
				asText(args.get("assistantUid")),
				readStateValue(toolContext, AssistantStateKeys.ASSISTANT_UID));
		String rowFilter = firstNonBlank(
				asText(args.get("row_filter")),
				asText(args.get("rowFilter")));
		if (!StringUtils.hasText(rowFilter)) {
			rowFilter = sqlGuardPolicy.buildRowFilter(assistantUid);
		}

		DataAgentQueryResult result = queryService.query(agentId, query, rowFilter);
		return objectMapper.writeValueAsString(result);
	}

	private String readStateValue(@Nullable ToolContext toolContext, String key) {
		if (toolContext == null || toolContext.getContext() == null || !StringUtils.hasText(key)) {
			return null;
		}
		Object stateObject = toolContext.getContext().get(ToolContextConstants.AGENT_STATE_CONTEXT_KEY);
		if (!(stateObject instanceof OverAllState state)) {
			return null;
		}
		return state.value(key, String.class).orElse(null);
	}

	private String asText(Object value) {
		if (value == null) {
			return null;
		}
		String text = String.valueOf(value).trim();
		return StringUtils.hasText(text) ? text : null;
	}

	private String firstNonBlank(String... values) {
		if (values == null) {
			return null;
		}
		for (String value : values) {
			if (StringUtils.hasText(value)) {
				return value;
			}
		}
		return null;
	}

	private static ToolDefinition buildToolDefinition() {
		return DefaultToolDefinition.builder()
				.name(TOOL_NAME)
				.description("DataAgent query tool with SQL guard, row-level filter and masking")
				.inputSchema("""
						{
						  "type": "object",
						  "properties": {
						    "query": {
						      "type": "string",
						      "description": "Natural language query or SQL payload with SQL: prefix"
						    },
						    "agent_id": {
						      "type": "string",
						      "description": "Logical DataAgent identifier"
						    },
						    "assistant_uid": {
						      "type": "string",
						      "description": "Assistant user id for row-level filtering"
						    },
						    "row_filter": {
						      "type": "string",
						      "description": "Optional extra row-level filter expression"
						    }
						  },
						  "required": ["query"]
						}
						""")
				.build();
	}

	private static CodeactToolMetadata buildMetadata() {
		return DefaultCodeactToolMetadata.builder()
				.addSupportedLanguage(Language.PYTHON)
				.targetClassName("data_agent")
				.targetClassDescription("Data querying tools")
				.codeInvocationTemplate("query(query: str, agent_id: Optional[str] = None) -> Dict[str, Any]")
				.fewShots(List.of(new CodeExample(
						"query leave stats",
						"result = data_agent_query(query='本月请假人数统计', agent_id='leave')",
						"returns guarded rows")))
				.displayName("data_agent_query")
				.returnDirect(false)
				.alwaysAvailable(false)
				.build();
	}

}
