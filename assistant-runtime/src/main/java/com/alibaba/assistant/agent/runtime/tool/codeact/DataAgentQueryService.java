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

/**
 * Query service for DataAgent tool.
 *
 * @author Assistant Agent Team
 * @since 1.0.0
 */
public interface DataAgentQueryService {

	/**
	 * Execute query through DataAgent flow.
	 *
	 * @param agentId logical data-agent id
	 * @param query natural language query or SQL payload
	 * @param rowFilter row-level filter expression
	 * @return query result
	 */
	DataAgentQueryResult query(String agentId, String query, String rowFilter);

}
