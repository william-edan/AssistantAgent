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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * HTTP connector invoker for real external system calls.
 *
 * @author Assistant Agent Team
 * @since 1.0.0
 */
@Component
@Order(100)
public class HttpConnectorInvoker implements ConnectorInvoker {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    private static final String REQUEST_MODE_JSON = "JSON";

    private static final String REQUEST_MODE_FORM = "FORM_URLENCODED";

    private static final Pattern ACTION_ID_PATTERN = Pattern.compile("\"action_id\"\\s*:\\s*(\\d+)");

    private final ObjectMapper objectMapper;

    private final HttpClient httpClient;

    public HttpConnectorInvoker(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(TIMEOUT)
                .build();
    }

    @Override
    public boolean supports(String connectorType) {
        return "HTTP".equalsIgnoreCase(connectorType);
    }

    @Override
    public Map<String, Object> invoke(ConnectorInvokeContext context) {
        try {
            String httpMethod = context.getConnectorApi().getHttpMethod().toUpperCase();
            Map<String, Object> requestBody = context.getStepInput() == null ? Map.of() : context.getStepInput();
            String requestMode = resolveRequestMode(context);
            String targetUrl = buildTargetUrl(context, httpMethod, requestBody);

            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(targetUrl))
                    .timeout(TIMEOUT)
                    .header("Accept", "*/*");
            applyAuth(builder, context);
            applyStepHeaders(builder, context);

            HttpRequest request = buildRequest(builder, httpMethod, requestBody, requestMode);
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return toInvokeResult(response);
        }
        catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("http invoke failed: " + ex.getMessage(), ex);
        }
        catch (IOException ex) {
            throw new IllegalStateException("http invoke failed: " + ex.getMessage(), ex);
        }
    }

    private HttpRequest buildRequest(HttpRequest.Builder builder, String httpMethod, Map<String, Object> requestBody,
            String requestMode) throws IOException {
        if ("GET".equals(httpMethod)) {
            return builder.GET().build();
        }
        if ("DELETE".equals(httpMethod)) {
            return builder.DELETE().build();
        }
        String payload;
        if (REQUEST_MODE_FORM.equalsIgnoreCase(requestMode)) {
            payload = toQuery(requestBody);
            builder.header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
        }
        else {
            payload = objectMapper.writeValueAsString(requestBody);
            builder.header("Content-Type", "application/json; charset=UTF-8");
        }
        return builder.method(httpMethod, HttpRequest.BodyPublishers.ofString(payload)).build();
    }

    private void applyAuth(HttpRequest.Builder builder, ConnectorInvokeContext context) {
        if (context.getAuthHeaders() != null) {
            context.getAuthHeaders().forEach(builder::header);
        }
        if (context.getAuthCookies() != null && !context.getAuthCookies().isEmpty()) {
            StringJoiner joiner = new StringJoiner("; ");
            context.getAuthCookies().forEach((name, value) -> joiner.add(name + "=" + value));
            builder.header("Cookie", joiner.toString());
        }
    }

    private void applyStepHeaders(HttpRequest.Builder builder, ConnectorInvokeContext context) {
        if (context.getStepHeaders() != null) {
            context.getStepHeaders().forEach(builder::header);
        }
    }

    private String buildTargetUrl(ConnectorInvokeContext context, String httpMethod, Map<String, Object> requestBody) {
        String base = context.getConnector().getBaseUrl();
        String path = context.getConnectorApi().getPathTemplate();
        String normalizedBase = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        String normalizedPath = path.startsWith("/") ? path : "/" + path;
        String url = normalizedBase + normalizedPath;
        if (!"GET".equals(httpMethod) || requestBody.isEmpty()) {
            return url;
        }
        return url + "?" + toQuery(requestBody);
    }

    private String toQuery(Map<String, Object> requestBody) {
        StringJoiner joiner = new StringJoiner("&");
        for (Map.Entry<String, Object> entry : requestBody.entrySet()) {
            String key = URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8);
            String rawValue = entry.getValue() == null ? "" : String.valueOf(entry.getValue());
            String value = URLEncoder.encode(rawValue, StandardCharsets.UTF_8);
            joiner.add(key + "=" + value);
        }
        return joiner.toString();
    }

    private Map<String, Object> toInvokeResult(HttpResponse<String> response) {
        Map<String, Object> result = new HashMap<>();
        result.put("statusCode", response.statusCode());
        result.put("headers", response.headers().map());
        String body = response.body() == null ? "" : response.body();
        result.put("rawBody", body);
        result.put("data", parseBody(body));
        return result;
    }

    private Object parseBody(String body) {
        if (body == null || body.isBlank()) {
            return Map.of();
        }
        try {
            JsonNode node = objectMapper.readTree(body);
            Object parsed = objectMapper.convertValue(node, Object.class);
            if (parsed instanceof Map<?, ?> parsedMap) {
                Map<String, Object> normalized = new HashMap<>();
                parsedMap.forEach((k, v) -> normalized.put(String.valueOf(k), v));
                Object actionId = extractActionId(normalized);
                if (actionId != null && !normalized.containsKey("action_id")) {
                    normalized.put("action_id", actionId);
                }
                return normalized;
            }
            return parsed;
        }
        catch (Exception ex) {
            Matcher matcher = ACTION_ID_PATTERN.matcher(body);
            if (matcher.find()) {
                Map<String, Object> fallback = new HashMap<>();
                fallback.put("action_id", Long.parseLong(matcher.group(1)));
                fallback.put("raw", body);
                return fallback;
            }
            return body;
        }
    }

    @SuppressWarnings("unchecked")
    private Object extractActionId(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Map<?, ?> map) {
            List<String> preferred = Arrays.asList("action_id", "actionId", "id");
            for (String key : preferred) {
                Object direct = map.get(key);
                if (isNumberLike(direct)) {
                    return direct;
                }
            }
            List<String> containerKeys = Arrays.asList("data", "result", "payload");
            for (String containerKey : containerKeys) {
                Object nested = extractActionId(map.get(containerKey));
                if (nested != null) {
                    return nested;
                }
            }
            for (Object nestedValue : new ArrayList<>(map.values())) {
                Object nested = extractActionId(nestedValue);
                if (nested != null) {
                    return nested;
                }
            }
            return null;
        }
        if (value instanceof List<?> list) {
            for (Object item : list) {
                Object nested = extractActionId(item);
                if (nested != null) {
                    return nested;
                }
            }
            return null;
        }
        return isNumberLike(value) ? value : null;
    }

    private boolean isNumberLike(Object value) {
        if (value instanceof Number) {
            return true;
        }
        if (!(value instanceof String text)) {
            return false;
        }
        if (text.isBlank()) {
            return false;
        }
        for (int i = 0; i < text.length(); i++) {
            if (!Character.isDigit(text.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private String resolveRequestMode(ConnectorInvokeContext context) {
        if (context.getStepRequestMode() != null && !context.getStepRequestMode().isBlank()) {
            String stepMode = context.getStepRequestMode().trim().toUpperCase();
            if ("FORM".equals(stepMode) || "FORM_URLENCODED".equals(stepMode)) {
                return REQUEST_MODE_FORM;
            }
            return REQUEST_MODE_JSON;
        }
        String requestSchemaJson = context.getConnectorApi().getRequestSchemaJson();
        if (requestSchemaJson == null || requestSchemaJson.isBlank()) {
            return REQUEST_MODE_JSON;
        }
        try {
            JsonNode schema = objectMapper.readTree(requestSchemaJson);
            JsonNode bodyMode = schema.get("x-body-mode");
            if (bodyMode != null && !bodyMode.asText().isBlank()) {
                String mode = bodyMode.asText().trim().toUpperCase();
                if ("FORM".equals(mode) || "FORM_URLENCODED".equals(mode)) {
                    return REQUEST_MODE_FORM;
                }
            }
        }
        catch (Exception ex) {
            return REQUEST_MODE_JSON;
        }
        return REQUEST_MODE_JSON;
    }
}
