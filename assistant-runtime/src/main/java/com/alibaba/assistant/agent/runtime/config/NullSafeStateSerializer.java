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
import com.alibaba.cloud.ai.graph.serializer.plain_text.jackson.SpringAIJacksonStateSerializer;
import com.alibaba.cloud.ai.graph.state.AgentStateFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.type.WritableTypeId;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.jsontype.TypeSerializer;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;
import org.springframework.ai.chat.messages.ToolResponseMessage;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Null-safe extension of {@link SpringAIJacksonStateSerializer}.
 * Registers a custom ToolResponseMessage serializer that handles null {@code getResponses()}
 * and null {@code getText()}, working around a framework bug in
 * {@code ToolResponseMessageHandler.Serializer} that throws NPE when responses is null.
 *
 * @author Assistant Agent Team
 * @since 1.0.0
 */
public class NullSafeStateSerializer extends SpringAIJacksonStateSerializer {

	public NullSafeStateSerializer(AgentStateFactory<OverAllState> stateFactory) {
		super(stateFactory);
		registerNullSafeModule();
	}

	private void registerNullSafeModule() {
		SimpleModule module = new SimpleModule("NullSafeToolResponseModule");
		module.addSerializer(ToolResponseMessage.class, new NullSafeToolResponseMessageSerializer());
		// Last-registered module wins in Jackson, so this overrides the framework's serializer
		this.objectMapper.registerModule(module);
	}

	/**
	 * Null-safe serializer for {@link ToolResponseMessage}.
	 * Mirrors the framework's ToolResponseMessageHandler.Serializer but adds null checks.
	 */
	static class NullSafeToolResponseMessageSerializer extends StdSerializer<ToolResponseMessage> {

		NullSafeToolResponseMessageSerializer() {
			super(ToolResponseMessage.class);
		}

		@Override
		public void serialize(ToolResponseMessage msg, JsonGenerator gen, SerializerProvider provider)
				throws IOException {
			gen.writeStartObject();
			serializeFields(msg, gen);
			gen.writeEndObject();
		}

		@Override
		public void serializeWithType(ToolResponseMessage msg, JsonGenerator gen, SerializerProvider provider,
				TypeSerializer typeSer) throws IOException {
			WritableTypeId typeIdDef = typeSer.writeTypePrefix(gen, typeSer.typeId(msg, JsonToken.START_OBJECT));
			serializeFields(msg, gen);
			typeSer.writeTypeSuffix(gen, typeIdDef);
		}

		private void serializeFields(ToolResponseMessage msg, JsonGenerator gen) throws IOException {
			// Null-safe text field
			String text = msg.getText();
			gen.writeStringField("text", text != null ? text : "");

			// Null-safe responses field
			List<ToolResponseMessage.ToolResponse> responses = msg.getResponses();
			if (responses == null) {
				responses = Collections.emptyList();
			}

			gen.writeArrayFieldStart("responses");
			for (ToolResponseMessage.ToolResponse response : responses) {
				gen.writeStartObject();
				gen.writeStringField("id", response.id());
				gen.writeStringField("name", response.name());
				gen.writeStringField("responseData", response.responseData());
				gen.writeEndObject();
			}
			gen.writeEndArray();

			// Metadata — delegate to Jackson (consistent with framework's SerializationHelper)
			gen.writeObjectField("metadata", msg.getMetadata());
		}

	}

}
