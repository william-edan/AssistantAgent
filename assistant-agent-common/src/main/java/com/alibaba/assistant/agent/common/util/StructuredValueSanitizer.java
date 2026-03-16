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
package com.alibaba.assistant.agent.common.util;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 清洗结构化参数里残留的 Java 集合包装标记。
 */
public final class StructuredValueSanitizer {

    private StructuredValueSanitizer() {
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> sanitizeMap(Map<String, Object> rawValue) {
        if (rawValue == null || rawValue.isEmpty()) {
            return new LinkedHashMap<>();
        }
        Object sanitized = sanitize(rawValue);
        if (sanitized instanceof Map<?, ?> map) {
            return new LinkedHashMap<>((Map<String, Object>) map);
        }
        return new LinkedHashMap<>();
    }

    public static Object sanitize(Object rawValue) {
        if (rawValue instanceof Map<?, ?> rawMap) {
            Map<String, Object> sanitized = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : rawMap.entrySet()) {
                if (entry.getKey() == null) {
                    continue;
                }
                sanitized.put(String.valueOf(entry.getKey()), sanitize(entry.getValue()));
            }
            return sanitized;
        }
        if (rawValue instanceof List<?> rawList) {
            return sanitizeList(rawList);
        }
        if (rawValue != null && rawValue.getClass().isArray()) {
            return sanitizeList(toList(rawValue));
        }
        return rawValue;
    }

    private static Object sanitizeList(List<?> rawList) {
        if (isCollectionWrapper(rawList)) {
            return sanitize(rawList.get(1));
        }
        List<Object> sanitized = new ArrayList<>(rawList.size());
        for (Object item : rawList) {
            sanitized.add(sanitize(item));
        }
        return sanitized;
    }

    private static boolean isCollectionWrapper(List<?> rawList) {
        if (rawList == null || rawList.size() != 2) {
            return false;
        }
        if (!(rawList.get(0) instanceof String marker) || !isJavaCollectionMarker(marker)) {
            return false;
        }
        Object nestedValue = rawList.get(1);
        return nestedValue instanceof List<?> || (nestedValue != null && nestedValue.getClass().isArray());
    }

    private static boolean isJavaCollectionMarker(String marker) {
        if (marker == null) {
            return false;
        }
        String normalized = marker.trim();
        return normalized.startsWith("java.util.")
                && (normalized.contains("List") || normalized.contains("Collection"));
    }

    private static List<Object> toList(Object arrayValue) {
        int length = Array.getLength(arrayValue);
        List<Object> values = new ArrayList<>(length);
        for (int index = 0; index < length; index++) {
            values.add(Array.get(arrayValue, index));
        }
        return values;
    }
}
