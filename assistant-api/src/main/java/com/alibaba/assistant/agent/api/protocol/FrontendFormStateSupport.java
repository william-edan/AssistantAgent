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
package com.alibaba.assistant.agent.api.protocol;

import com.alibaba.assistant.agent.common.util.StructuredValueSanitizer;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 前端表单状态载荷的统一归一化规则。
 */
public final class FrontendFormStateSupport {

    private FrontendFormStateSupport() {
    }

    public static boolean isConfirmationForm(Map<String, Object> payload, String stage, String status) {
        Map<String, Object> safePayload = payload != null ? payload : Map.of();
        String mode = asText(safePayload.get("mode"));
        if ("CONFIRM".equalsIgnoreCase(mode)) {
            return true;
        }
        String phase = asText(safePayload.get("phase"));
        if ("READY_TO_CONFIRM".equalsIgnoreCase(phase)
                || "CONFIRMING".equalsIgnoreCase(phase)
                || "WAITING_CONFIRMATION".equalsIgnoreCase(phase)) {
            return true;
        }
        String normalizedStatus = firstText(status, safePayload.get("status"));
        if ("WAITING_CONFIRMATION".equalsIgnoreCase(normalizedStatus)
                || "CONFIRMING".equalsIgnoreCase(normalizedStatus)
                || "READY_TO_CONFIRM".equalsIgnoreCase(normalizedStatus)) {
            return true;
        }
        String normalizedStage = firstText(stage, safePayload.get("stage"));
        if ("CONFIRMING".equalsIgnoreCase(normalizedStage)) {
            return true;
        }
        return Boolean.TRUE.equals(safePayload.get("canSubmit"));
    }

    public static String normalizedMode(Map<String, Object> payload, String stage, String status) {
        return isConfirmationForm(payload, stage, status) ? "CONFIRM" : "COLLECT";
    }

    public static String normalizedStatus(Map<String, Object> payload, String stage, String status) {
        return isConfirmationForm(payload, stage, status) ? "WAITING_CONFIRMATION" : "WAITING_INPUT";
    }

    public static String normalizedPhase(Map<String, Object> payload, String stage, String status) {
        return isConfirmationForm(payload, stage, status)
                ? FrontendStage.CONFIRMING.name()
                : FrontendStage.COLLECTING.name();
    }

    public static FrontendStage normalizedStage(Map<String, Object> payload, String stage, String status) {
        return isConfirmationForm(payload, stage, status)
                ? FrontendStage.CONFIRMING
                : FrontendStage.COLLECTING;
    }

    public static Map<String, Object> normalizePayload(Map<String, Object> payload, String stage, String status) {
        Map<String, Object> normalized = payload != null
                ? StructuredValueSanitizer.sanitizeMap(payload)
                : new LinkedHashMap<>();
        normalized.put("mode", normalizedMode(normalized, stage, status));
        normalized.put("status", normalizedStatus(normalized, stage, status));
        normalized.put("phase", normalizedPhase(normalized, stage, status));
        normalized.put("canSubmit", isConfirmationForm(normalized, stage, status));
        return normalized;
    }

    private static String firstText(Object... values) {
        if (values == null) {
            return null;
        }
        for (Object value : values) {
            String text = asText(value);
            if (StringUtils.hasText(text)) {
                return text;
            }
        }
        return null;
    }

    private static String asText(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return StringUtils.hasText(text) ? text : null;
    }
}
