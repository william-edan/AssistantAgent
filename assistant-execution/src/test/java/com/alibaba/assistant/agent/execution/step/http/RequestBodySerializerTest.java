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
package com.alibaba.assistant.agent.execution.step.http;

import org.junit.jupiter.api.Test;
import org.springframework.util.MultiValueMap;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RequestBodySerializerTest {

    @Test
    void shouldSerializeArrayParamsAsPhpStyleKeyBrackets() {
        RequestBodySerializer serializer = new RequestBodySerializer();

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("key", List.of("v1", "v2"));

        MultiValueMap<String, String> encoded = serializer.toFormUrlEncoded(params);

        assertTrue(encoded.containsKey("key[]"));
        assertEquals(List.of("v1", "v2"), encoded.get("key[]"));

        String flat = encoded.entrySet().stream()
                .flatMap(entry -> entry.getValue().stream().map(value -> entry.getKey() + "=" + value))
                .collect(Collectors.joining("&"));
        assertEquals("key[]=v1&key[]=v2", flat);
    }
}
