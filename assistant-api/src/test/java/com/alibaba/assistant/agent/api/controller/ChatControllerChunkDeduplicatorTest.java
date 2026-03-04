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

import com.alibaba.cloud.ai.graph.streaming.OutputType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
