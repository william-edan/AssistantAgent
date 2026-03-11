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

import com.alibaba.assistant.agent.controlplane.connector.ResolvedAuthProfileManagementView;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Managed auth-profile payload.
 */
public record ConnectorManagedAuthProfileData(
        String authProfileCode,
        String authType,
        String usagePolicy,
        String tokenEndpoint,
        String tokenHeaderName,
        String tokenHeaderPrefix,
        String audience,
        List<String> scopes,
        String credentialRef,
        Map<String, Object> refreshPolicy,
        String status) {

    public static ConnectorManagedAuthProfileData from(ResolvedAuthProfileManagementView resolved) {
        return new ConnectorManagedAuthProfileData(
                resolved.authProfileCode(),
                upper(resolved.authType()),
                upper(resolved.usagePolicy()),
                resolved.tokenEndpoint(),
                resolved.tokenHeaderName(),
                resolved.tokenHeaderPrefix(),
                resolved.audience(),
                resolved.scopes(),
                resolved.credentialRef(),
                resolved.refreshPolicy(),
                upper(resolved.status()));
    }

    private static String upper(String value) {
        return StringUtils.hasText(value) ? value.trim().toUpperCase(Locale.ROOT) : "";
    }
}
