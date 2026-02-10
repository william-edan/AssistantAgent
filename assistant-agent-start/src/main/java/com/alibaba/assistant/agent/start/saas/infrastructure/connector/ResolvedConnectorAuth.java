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
package com.alibaba.assistant.agent.start.saas.infrastructure.connector;

import java.util.HashMap;
import java.util.Map;

/**
 * Resolved auth values for connector invoke.
 *
 * @author Assistant Agent Team
 * @since 1.0.0
 */
public class ResolvedConnectorAuth {

    private final Map<String, String> headers = new HashMap<>();

    private final Map<String, String> cookies = new HashMap<>();

    public Map<String, String> getHeaders() {
        return headers;
    }

    public Map<String, String> getCookies() {
        return cookies;
    }
}
