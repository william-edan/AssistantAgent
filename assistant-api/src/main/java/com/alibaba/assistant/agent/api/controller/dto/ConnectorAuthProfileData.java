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
package com.alibaba.assistant.agent.api.controller.dto;

import com.alibaba.assistant.agent.controlplane.connector.ResolvedAuthProfileView;
import org.springframework.util.StringUtils;

import java.util.Locale;

/**
 * Auth-profile response payload.
 */
public record ConnectorAuthProfileData(
        String authProfileCode,
        String authType,
        String usagePolicy,
        String tokenHeaderName,
        String tokenHeaderPrefix,
        String status) {

    public static ConnectorAuthProfileData from(ResolvedAuthProfileView resolved) {
        return new ConnectorAuthProfileData(
                resolved.authProfileCode(),
                upper(resolved.authType()),
                upper(resolved.usagePolicy()),
                resolved.tokenHeaderName(),
                resolved.tokenHeaderPrefix(),
                upper(resolved.status()));
    }

    private static String upper(String value) {
        return StringUtils.hasText(value) ? value.trim().toUpperCase(Locale.ROOT) : "";
    }
}
