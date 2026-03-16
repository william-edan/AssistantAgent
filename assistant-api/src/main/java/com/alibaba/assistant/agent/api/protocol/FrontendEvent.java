package com.alibaba.assistant.agent.api.protocol;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Map;

/**
 * 面向前端输出的聊天事件统一包。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record FrontendEvent(
		String protocolVersion,
		String eventId,
		String threadId,
		String timestamp,
		FrontendEventType eventType,
		FrontendStage stage,
		Map<String, Object> payload) {
}
