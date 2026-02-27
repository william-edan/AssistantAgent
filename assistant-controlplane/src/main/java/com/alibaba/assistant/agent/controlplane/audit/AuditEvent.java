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
package com.alibaba.assistant.agent.controlplane.audit;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * Audit event entity mapped to the audit_event table.
 *
 * @author Assistant Agent Team
 * @since 1.0.0
 */
@TableName("audit_event")
public class AuditEvent {

	@TableId(type = IdType.AUTO)
	private Long id;

	private String eventId;

	private String traceId;

	private String executionId;

	private String threadId;

	private String assistantUid;

	private String systemCode;

	private String toolName;

	private String agentPhase;

	private String toolInput;

	private String toolOutput;

	private Long durationMs;

	private String status;

	private String errorMessage;

	private LocalDateTime createdAt;

	public Long getId() {
		return id;
	}

	public void setId(Long id) {
		this.id = id;
	}

	public String getEventId() {
		return eventId;
	}

	public void setEventId(String eventId) {
		this.eventId = eventId;
	}

	public String getTraceId() {
		return traceId;
	}

	public void setTraceId(String traceId) {
		this.traceId = traceId;
	}

	public String getExecutionId() {
		return executionId;
	}

	public void setExecutionId(String executionId) {
		this.executionId = executionId;
	}

	public String getThreadId() {
		return threadId;
	}

	public void setThreadId(String threadId) {
		this.threadId = threadId;
	}

	public String getAssistantUid() {
		return assistantUid;
	}

	public void setAssistantUid(String assistantUid) {
		this.assistantUid = assistantUid;
	}

	public String getSystemCode() {
		return systemCode;
	}

	public void setSystemCode(String systemCode) {
		this.systemCode = systemCode;
	}

	public String getToolName() {
		return toolName;
	}

	public void setToolName(String toolName) {
		this.toolName = toolName;
	}

	public String getAgentPhase() {
		return agentPhase;
	}

	public void setAgentPhase(String agentPhase) {
		this.agentPhase = agentPhase;
	}

	public String getToolInput() {
		return toolInput;
	}

	public void setToolInput(String toolInput) {
		this.toolInput = toolInput;
	}

	public String getToolOutput() {
		return toolOutput;
	}

	public void setToolOutput(String toolOutput) {
		this.toolOutput = toolOutput;
	}

	public Long getDurationMs() {
		return durationMs;
	}

	public void setDurationMs(Long durationMs) {
		this.durationMs = durationMs;
	}

	public String getStatus() {
		return status;
	}

	public void setStatus(String status) {
		this.status = status;
	}

	public String getErrorMessage() {
		return errorMessage;
	}

	public void setErrorMessage(String errorMessage) {
		this.errorMessage = errorMessage;
	}

	public LocalDateTime getCreatedAt() {
		return createdAt;
	}

	public void setCreatedAt(LocalDateTime createdAt) {
		this.createdAt = createdAt;
	}

}
