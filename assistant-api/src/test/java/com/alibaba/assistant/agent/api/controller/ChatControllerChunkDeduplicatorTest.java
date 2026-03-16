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
package com.alibaba.assistant.agent.api.controller;

import com.alibaba.assistant.agent.api.protocol.FrontendEvent;
import com.alibaba.assistant.agent.api.protocol.FrontendEventType;
import com.alibaba.assistant.agent.api.protocol.FrontendStage;
import com.alibaba.assistant.agent.api.protocol.V3ProtocolAdapter;
import com.alibaba.cloud.ai.graph.streaming.OutputType;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatControllerChunkDeduplicatorTest {

	@Test
	void shouldEmitOnlyDeltaForCumulativeText() {
		ChatController.ChunkDeduplicator deduplicator = new ChatController.ChunkDeduplicator();

		assertEquals("请", deduplicator.nextChunk(null, "请"));
		assertEquals("提供", deduplicator.nextChunk(null, "请提供"));
		assertEquals("本期", deduplicator.nextChunk(null, "请提供本期"));
		assertEquals("工作内容", deduplicator.nextChunk(null, "请提供本期工作内容"));
	}

	@Test
	void shouldNotDuplicateWhenFinishedRepeatsFullText() {
		ChatController.ChunkDeduplicator deduplicator = new ChatController.ChunkDeduplicator();
		deduplicator.nextChunk(null, "请提供本期工作内容");

		assertEquals("", deduplicator.nextChunk(OutputType.AGENT_MODEL_FINISHED, "请提供本期工作内容"));
	}

	@Test
	void shouldKeepDeltaWhenFinishedCarriesLongerText() {
		ChatController.ChunkDeduplicator deduplicator = new ChatController.ChunkDeduplicator();
		deduplicator.nextChunk(null, "请提供本期工作内容");

		assertEquals("，我将为您生成工作汇报。",
				deduplicator.nextChunk(OutputType.AGENT_MODEL_FINISHED, "请提供本期工作内容，我将为您生成工作汇报。"));
	}

	@Test
	void shouldFlushVisibleAssistantTextAsSingleDoneMessage() {
		ChatController.AssistantTextBuffer buffer = new ChatController.AssistantTextBuffer();
		ChatController.StageTracker stageTracker = new ChatController.StageTracker();
		V3ProtocolAdapter protocolAdapter = new V3ProtocolAdapter(new ObjectMapper());

		buffer.capture("请先选择汇报类型，我会自动带出本期时间范围。");

		List<FrontendEvent> events = buffer.flush("thread-visible", protocolAdapter, stageTracker);

		assertEquals(2, events.size());
		assertEquals(FrontendEventType.STAGE, events.get(0).eventType());
		assertEquals(FrontendStage.DONE, events.get(0).stage());
		assertEquals(FrontendEventType.MESSAGE, events.get(1).eventType());
		assertEquals("请先选择汇报类型，我会自动带出本期时间范围。", events.get(1).payload().get("text"));
	}

	@Test
	void shouldDropInternalPlanningNarrationWhenFlushed() {
		ChatController.AssistantTextBuffer buffer = new ChatController.AssistantTextBuffer();
		ChatController.StageTracker stageTracker = new ChatController.StageTracker();
		V3ProtocolAdapter protocolAdapter = new V3ProtocolAdapter(new ObjectMapper());

		buffer.capture("用户明确表示“我要写汇报”，意图清晰，匹配可用工具 `gougu_oa.work_report`，需先调用slot_collect 收集必要参数。");

		List<FrontendEvent> events = buffer.flush("thread-hidden", protocolAdapter, stageTracker);

		assertTrue(events.isEmpty());
	}

	@Test
	void shouldDropBufferedAssistantTextAfterToolInteraction() {
		ChatController.AssistantTextBuffer buffer = new ChatController.AssistantTextBuffer();
		ChatController.StageTracker stageTracker = new ChatController.StageTracker();
		V3ProtocolAdapter protocolAdapter = new V3ProtocolAdapter(new ObjectMapper());

		buffer.capture("我来帮你处理这个流程。");
		buffer.markToolInteraction();

		List<FrontendEvent> events = buffer.flush("thread-tool", protocolAdapter, stageTracker);

		assertTrue(events.isEmpty());
	}
}
