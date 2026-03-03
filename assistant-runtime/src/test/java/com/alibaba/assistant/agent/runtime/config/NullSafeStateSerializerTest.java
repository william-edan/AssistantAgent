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
package com.alibaba.assistant.agent.runtime.config;

import com.alibaba.cloud.ai.graph.OverAllState;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.ToolResponseMessage;

import java.io.*;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class NullSafeStateSerializerTest {

	@Test
	void shouldSerializeToolResponseMessageWithNullResponses() throws Exception {
		NullSafeStateSerializer serializer = new NullSafeStateSerializer(OverAllState::new);

		// Build a ToolResponseMessage without .responses() — getResponses() returns null
		ToolResponseMessage brokenMessage = ToolResponseMessage.builder()
				.metadata(Map.of())
				.build();

		// Verify getResponses() is indeed null (the bug condition)
		assertNull(brokenMessage.getResponses());

		Map<String, Object> state = Map.of("messages", List.of(brokenMessage));

		// Should NOT throw NPE
		assertDoesNotThrow(() -> {
			ByteArrayOutputStream baos = new ByteArrayOutputStream();
			ObjectOutputStream oos = new ObjectOutputStream(baos);
			serializer.writeData(state, oos);
			oos.flush();
			assertTrue(baos.size() > 0);
		});
	}

	@Test
	void shouldRoundTripToolResponseMessageWithValidResponses() throws Exception {
		NullSafeStateSerializer serializer = new NullSafeStateSerializer(OverAllState::new);

		ToolResponseMessage validMessage = ToolResponseMessage.builder()
				.responses(List.of(
						new ToolResponseMessage.ToolResponse("call-1", "slot_collect", "{\"ok\":true}")))
				.metadata(Map.of())
				.build();

		Map<String, Object> state = Map.of("messages", List.of(validMessage));

		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		ObjectOutputStream oos = new ObjectOutputStream(baos);
		serializer.writeData(state, oos);
		oos.flush();

		byte[] bytes = baos.toByteArray();
		assertTrue(bytes.length > 0);

		ByteArrayInputStream bais = new ByteArrayInputStream(bytes);
		ObjectInputStream ois = new ObjectInputStream(bais);
		Map<String, Object> restored = serializer.readData(ois);

		assertNotNull(restored);
		assertTrue(restored.containsKey("messages"));
	}

}
