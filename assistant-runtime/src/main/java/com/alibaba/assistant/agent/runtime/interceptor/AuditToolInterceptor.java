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
package com.alibaba.assistant.agent.runtime.interceptor;

import com.alibaba.assistant.agent.controlplane.audit.AuditEvent;
import com.alibaba.assistant.agent.controlplane.audit.AuditEventService;
import com.alibaba.assistant.agent.runtime.agent.AssistantStateKeys;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallExecutionContext;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallHandler;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallRequest;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallResponse;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolInterceptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * Full-link audit interceptor for tool calls.
 * Persists audit records with traceId and execution metadata.
 *
 * @author Assistant Agent Team
 * @since 1.0.0
 */
@Component
@Profile("migration")
public class AuditToolInterceptor extends ToolInterceptor {

	private static final Logger logger = LoggerFactory.getLogger(AuditToolInterceptor.class);

	private final AuditEventService auditEventService;

	public AuditToolInterceptor(AuditEventService auditEventService) {
		this.auditEventService = auditEventService;
	}

	@Override
	public ToolCallResponse interceptToolCall(ToolCallRequest request, ToolCallHandler handler) {
		long startMs = System.currentTimeMillis();
		ToolCallExecutionContext executionContext = request.getExecutionContext().orElse(null);
		OverAllState state = executionContext != null ? executionContext.state() : null;
		String traceId = resolveTraceId(state, executionContext);
		String executionId = resolveExecutionId(request, executionContext);
		String threadId = resolveThreadId(state, executionContext);

		String status = "SUCCESS";
		String errorMessage = null;
		String toolOutput = null;

		try {
			ToolCallResponse response = handler.call(request);
			if (response != null) {
				toolOutput = response.getResult();
				if (response.isError()) {
					status = "ERROR";
					errorMessage = response.getStatus();
				}
			}
			return response;
		}
		catch (Exception e) {
			status = "ERROR";
			errorMessage = e.getMessage();
			throw e;
		}
		finally {
			long durationMs = System.currentTimeMillis() - startMs;
			persistAuditEvent(request, state, traceId, executionId, threadId, status, errorMessage, toolOutput,
					durationMs);
		}
	}

	@Override
	public String getName() {
		return "AuditToolInterceptor";
	}

	private void persistAuditEvent(ToolCallRequest request, OverAllState state, String traceId,
			String executionId, String threadId, String status, String errorMessage,
			String toolOutput, long durationMs) {
		try {
			AuditEvent event = new AuditEvent();
			event.setEventId(UUID.randomUUID().toString());
			event.setTraceId(traceId);
			event.setExecutionId(executionId);
			event.setThreadId(threadId);
			event.setAssistantUid(readStateValue(state, AssistantStateKeys.ASSISTANT_UID));
			event.setSystemCode(readStateValue(state, AssistantStateKeys.SYSTEM_CODE));
			event.setToolName(request.getToolName());
			event.setAgentPhase(readStateValue(state, AssistantStateKeys.CONVERSATION_PHASE));
			event.setToolInput(request.getArguments());
			event.setToolOutput(toolOutput);
			event.setDurationMs(durationMs);
			event.setStatus(status);
			event.setErrorMessage(errorMessage);
			event.setCreatedAt(LocalDateTime.now());
			auditEventService.save(event);
		}
		catch (Exception e) {
			logger.error("AuditToolInterceptor#persistAuditEvent - persist failed, toolName={}, error={}",
					request.getToolName(), e.getMessage(), e);
		}
	}

	private String resolveTraceId(OverAllState state, ToolCallExecutionContext executionContext) {
		String existing = readStateValue(state, AssistantStateKeys.AUDIT_TRACE_ID);
		if (StringUtils.hasText(existing)) {
			return existing;
		}

		String traceId = null;
		if (executionContext != null && executionContext.config() != null) {
			traceId = firstNonBlank(
					executionContext.config().metadata("trace_id").map(String::valueOf).orElse(null),
					executionContext.config().metadata("traceId").map(String::valueOf).orElse(null));
		}
		if (!StringUtils.hasText(traceId)) {
			traceId = UUID.randomUUID().toString();
		}
		if (state != null) {
			state.updateState(Map.of(AssistantStateKeys.AUDIT_TRACE_ID, traceId));
		}
		return traceId;
	}

	private String resolveExecutionId(ToolCallRequest request, ToolCallExecutionContext executionContext) {
		String checkpointId = executionContext != null
				? executionContext.checkpointId().orElse(null)
				: null;
		return firstNonBlank(checkpointId, request.getToolCallId(), UUID.randomUUID().toString());
	}

	private String resolveThreadId(OverAllState state, ToolCallExecutionContext executionContext) {
		String stateThread = readStateValue(state, AssistantStateKeys.THREAD_ID);
		String executionThread = executionContext != null
				? executionContext.threadId().orElse(null)
				: null;
		return firstNonBlank(executionThread, stateThread);
	}

	private String readStateValue(OverAllState state, String key) {
		if (state == null || !StringUtils.hasText(key)) {
			return null;
		}
		return state.value(key, String.class).orElse(null);
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

}
