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
package com.alibaba.assistant.agent.start.config;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HttpLogUtilsTest {

    @Test
    void shouldMaskBearerToken() {
        String masked = HttpLogUtils.maskAuthorization("Bearer sk-abc123");
        assertEquals("Bearer ***", masked);
    }

    @Test
    void shouldSanitizeAuthorizationHeader() {
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.AUTHORIZATION, "Bearer sk-secret");
        headers.add("X-Test", "value");

        String sanitized = HttpLogUtils.sanitizeHeaders(headers);

        assertTrue(sanitized.contains("Authorization=[Bearer ***]"));
        assertTrue(sanitized.contains("X-Test=[value]"));
    }

    @Test
    void shouldTruncateAndConvertBodyToSingleLine() {
        String raw = "{\n  \"a\": 1,\n  \"b\": 2\n}";
        String actual = HttpLogUtils.truncateAndSingleLine(raw, 12);

        assertTrue(actual.contains("\\n"));
        assertTrue(actual.contains("truncated"));
    }

    @Test
    void shouldIdentifyDashScopeUriWhenEnabled() throws Exception {
        URI uri = new URI("https://dashscope.aliyuncs.com/api/v1/services/aigc/text-generation/generation");
        assertTrue(HttpLogUtils.shouldLogUri(uri, true));
    }

    @Test
    void shouldSkipNonDashScopeUriWhenOnlyDashScope() throws Exception {
        URI uri = new URI("https://example.com/api/test");
        assertFalse(HttpLogUtils.shouldLogUri(uri, true));
    }
}

