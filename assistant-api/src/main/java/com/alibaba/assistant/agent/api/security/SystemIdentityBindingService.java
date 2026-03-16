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
package com.alibaba.assistant.agent.api.security;

import com.alibaba.assistant.agent.controlplane.identity.IdentityBinding;
import com.alibaba.assistant.agent.controlplane.identity.mapper.IdentityBindingMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Binds a logged-in assistant user to a downstream enterprise account by calling the
 * configured bind endpoint from {@code assistant_system_registry} and upserting
 * {@code identity_binding}.
 */
@Service
@Profile("migration")
public class SystemIdentityBindingService {

    private final JdbcTemplate jdbcTemplate;

    private final IdentityBindingMapper identityBindingMapper;

    private final ObjectMapper objectMapper;

    private final RestTemplate restTemplate;

    @Autowired
    public SystemIdentityBindingService(
            JdbcTemplate jdbcTemplate,
            IdentityBindingMapper identityBindingMapper,
            ObjectMapper objectMapper) {
        this(jdbcTemplate, identityBindingMapper, objectMapper, new RestTemplate());
    }

    SystemIdentityBindingService(
            JdbcTemplate jdbcTemplate,
            IdentityBindingMapper identityBindingMapper,
            ObjectMapper objectMapper,
            RestTemplate restTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        this.identityBindingMapper = identityBindingMapper;
        this.objectMapper = objectMapper;
        this.restTemplate = restTemplate;
    }

    public void ensureBound(String assistantUid, String systemCode, String username, String password) {
        if (!StringUtils.hasText(assistantUid)
                || !StringUtils.hasText(systemCode)
                || !StringUtils.hasText(username)
                || !StringUtils.hasText(password)) {
            return;
        }

        IdentityBinding existingBinding = findActiveBinding(assistantUid.trim(), systemCode.trim());
        if (existingBinding != null && StringUtils.hasText(existingBinding.getSystemUserId())) {
            return;
        }

        Optional<SystemBindingProfile> profileOpt = findProfile(systemCode);
        if (profileOpt.isEmpty()) {
            return;
        }

        SystemBindingProfile profile = profileOpt.get();
        if (!StringUtils.hasText(profile.bindEndpoint())) {
            return;
        }

        String systemUserId = bindAndResolveSystemUserId(profile, username.trim(), password);
        upsertIdentityBinding(assistantUid.trim(), systemCode.trim(), systemUserId);
    }

    private Optional<SystemBindingProfile> findProfile(String systemCode) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT base_url, bind_endpoint, bind_method, bind_request, bind_response "
                        + "FROM assistant_system_registry "
                        + "WHERE system_code = ? AND status = 'active' "
                        + "ORDER BY priority DESC, id ASC LIMIT 1",
                systemCode.trim());
        if (rows == null || rows.isEmpty()) {
            return Optional.empty();
        }
        Map<String, Object> row = rows.get(0);
        return Optional.of(new SystemBindingProfile(
                text(row.get("base_url")),
                text(row.get("bind_endpoint")),
                normalizeMethod(text(row.get("bind_method"))),
                text(row.get("bind_request")),
                text(row.get("bind_response"))));
    }

    private String bindAndResolveSystemUserId(SystemBindingProfile profile, String username, String password) {
        try {
            Map<String, Object> requestBody = resolveRequestTemplate(profile.bindRequest(), username, password);
            ResponseEntity<String> response = callBindEndpoint(profile, requestBody);
            if (!response.getStatusCode().is2xxSuccessful() || !StringUtils.hasText(response.getBody())) {
                throw new ResponseStatusException(
                        org.springframework.http.HttpStatus.UNAUTHORIZED,
                        "downstream_bind_failed");
            }
            String systemUserId = extractSystemUserId(response.getBody(), profile.bindResponse());
            if (!StringUtils.hasText(systemUserId)) {
                throw new ResponseStatusException(
                        org.springframework.http.HttpStatus.UNAUTHORIZED,
                        "downstream_bind_failed");
            }
            return systemUserId.trim();
        }
        catch (ResponseStatusException ex) {
            throw ex;
        }
        catch (Exception ex) {
            throw new ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_GATEWAY,
                    "downstream_bind_unavailable",
                    ex);
        }
    }

    private ResponseEntity<String> callBindEndpoint(SystemBindingProfile profile, Map<String, Object> requestBody) {
        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        String url = joinUrl(profile.baseUrl(), profile.bindEndpoint());
        if (HttpMethod.GET.matches(profile.bindMethod())) {
            URI uri = appendQuery(url, requestBody);
            return restTemplate.exchange(uri, HttpMethod.GET, new HttpEntity<>(headers), String.class);
        }

        headers.setContentType(MediaType.APPLICATION_JSON);
        String body = writeJson(requestBody);
        return restTemplate.exchange(url, HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
    }

    private Map<String, Object> resolveRequestTemplate(@Nullable String bindRequest, String username, String password) {
        if (!StringUtils.hasText(bindRequest)) {
            return Map.of("username", username, "password", password);
        }
        try {
            Object rawTemplate = objectMapper.readValue(bindRequest, Object.class);
            Object resolved = resolveTemplateValue(rawTemplate, username, password);
            if (resolved instanceof Map<?, ?> rawMap) {
                Map<String, Object> result = new LinkedHashMap<>();
                for (Map.Entry<?, ?> entry : rawMap.entrySet()) {
                    if (entry.getKey() != null) {
                        result.put(String.valueOf(entry.getKey()), entry.getValue());
                    }
                }
                return result;
            }
            return Map.of("username", username, "password", password);
        }
        catch (Exception ex) {
            return Map.of("username", username, "password", password);
        }
    }

    private Object resolveTemplateValue(Object rawValue, String username, String password) {
        if (rawValue instanceof Map<?, ?> rawMap) {
            Map<String, Object> resolved = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : rawMap.entrySet()) {
                if (entry.getKey() != null) {
                    resolved.put(String.valueOf(entry.getKey()), resolveTemplateValue(entry.getValue(), username, password));
                }
            }
            return resolved;
        }
        if (rawValue instanceof List<?> rawList) {
            List<Object> resolved = new ArrayList<>();
            for (Object item : rawList) {
                resolved.add(resolveTemplateValue(item, username, password));
            }
            return resolved;
        }
        if (rawValue instanceof String text) {
            return text
                    .replace("${username}", username)
                    .replace("${password}", password);
        }
        return rawValue;
    }

    private String extractSystemUserId(String responseBody, @Nullable String bindResponseConfig) {
        String userIdPath = "data.user_id";
        if (StringUtils.hasText(bindResponseConfig)) {
            try {
                Map<String, Object> config = objectMapper.readValue(
                        bindResponseConfig,
                        new TypeReference<LinkedHashMap<String, Object>>() {
                        });
                userIdPath = firstText(config.get("user_id_field"), config.get("system_user_id_field"), userIdPath);
            }
            catch (Exception ignored) {
            }
        }
        return readPath(responseBody, userIdPath);
    }

    private IdentityBinding findActiveBinding(String assistantUid, String systemCode) {
        return identityBindingMapper.selectOne(
                new LambdaQueryWrapper<IdentityBinding>()
                        .eq(IdentityBinding::getAssistantUid, assistantUid)
                        .eq(IdentityBinding::getSystemCode, systemCode)
                        .eq(IdentityBinding::getStatus, "active")
                        .last("LIMIT 1"));
    }

    private void upsertIdentityBinding(String assistantUid, String systemCode, String systemUserId) {
        IdentityBinding existing = identityBindingMapper.selectOne(
                new LambdaQueryWrapper<IdentityBinding>()
                        .eq(IdentityBinding::getAssistantUid, assistantUid)
                        .eq(IdentityBinding::getSystemCode, systemCode)
                        .last("LIMIT 1"));
        if (existing == null) {
            IdentityBinding created = new IdentityBinding();
            created.setAssistantUid(assistantUid);
            created.setSystemCode(systemCode);
            created.setSystemUserId(systemUserId);
            created.setAuthType("TOKEN");
            created.setCredentials(null);
            created.setStatus("active");
            identityBindingMapper.insert(created);
            return;
        }
        existing.setSystemUserId(systemUserId);
        existing.setAuthType("TOKEN");
        existing.setCredentials(null);
        existing.setStatus("active");
        identityBindingMapper.updateById(existing);
    }

    private URI appendQuery(String url, Map<String, Object> requestBody) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromHttpUrl(url);
        MultiValueMap<String, String> query = new LinkedMultiValueMap<>();
        for (Map.Entry<String, Object> entry : requestBody.entrySet()) {
            if (entry.getKey() != null && entry.getValue() != null) {
                query.add(entry.getKey(), String.valueOf(entry.getValue()));
            }
        }
        builder.queryParams(query);
        return builder.build(true).toUri();
    }

    private String joinUrl(String baseUrl, String endpoint) {
        String normalizedBase = text(baseUrl);
        String normalizedEndpoint = text(endpoint);
        if (!StringUtils.hasText(normalizedBase)) {
            return normalizedEndpoint;
        }
        if (!StringUtils.hasText(normalizedEndpoint)) {
            return normalizedBase;
        }
        if (normalizedBase.endsWith("/") && normalizedEndpoint.startsWith("/")) {
            return normalizedBase.substring(0, normalizedBase.length() - 1) + normalizedEndpoint;
        }
        if (!normalizedBase.endsWith("/") && !normalizedEndpoint.startsWith("/")) {
            return normalizedBase + "/" + normalizedEndpoint;
        }
        return normalizedBase + normalizedEndpoint;
    }

    private String readPath(String json, String path) {
        try {
            Object current = objectMapper.readValue(json, Object.class);
            String normalized = text(path);
            if (!StringUtils.hasText(normalized)) {
                return null;
            }
            for (String segment : normalized.split("\\.")) {
                if (!(current instanceof Map<?, ?> rawMap) || !StringUtils.hasText(segment)) {
                    return null;
                }
                current = rawMap.get(segment);
            }
            return current != null ? String.valueOf(current) : null;
        }
        catch (Exception ex) {
            return null;
        }
    }

    private String writeJson(Map<String, Object> body) {
        try {
            return objectMapper.writeValueAsString(body);
        }
        catch (Exception ex) {
            throw new IllegalStateException("failed_to_serialize_bind_request", ex);
        }
    }

    private String normalizeMethod(String method) {
        return StringUtils.hasText(method) ? method.trim().toUpperCase() : HttpMethod.POST.name();
    }

    private String text(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return StringUtils.hasText(text) ? text : null;
    }

    private String firstText(Object... values) {
        if (values == null) {
            return null;
        }
        for (Object value : values) {
            String text = text(value);
            if (StringUtils.hasText(text)) {
                return text;
            }
        }
        return null;
    }

    private record SystemBindingProfile(
            String baseUrl,
            String bindEndpoint,
            String bindMethod,
            String bindRequest,
            String bindResponse) {
    }
}


