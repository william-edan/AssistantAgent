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
package com.alibaba.assistant.agent.start.reward.util;

import com.alibaba.assistant.agent.start.reward.model.RewardUserRecord;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * DataAgent 结果解析器。
 */
public class DataAgentResultParser {

    private static final Pattern CODE_BLOCK_PATTERN =
            Pattern.compile("```(?:json)?\\s*(.*?)\\s*```", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private static final Pattern UID_THEN_NAME_PATTERN =
            Pattern.compile(
                    "(?:uid|用户id|用户ID|员工id|员工ID)\\s*[:：=]?\\s*(\\d+).*?"
                            + "(?:uname|姓名|员工姓名|用户名|用户名称)\\s*[:：=]?\\s*([\\p{IsHan}A-Za-z0-9_·-]+)",
                    Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private static final Pattern NAME_THEN_UID_PATTERN =
            Pattern.compile(
                    "(?:uname|姓名|员工姓名|用户名|用户名称)\\s*[:：=]?\\s*([\\p{IsHan}A-Za-z0-9_·-]+).*?"
                            + "(?:uid|用户id|用户ID|员工id|员工ID)\\s*[:：=]?\\s*(\\d+)",
                    Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private final ObjectMapper objectMapper;

    public DataAgentResultParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public Optional<RewardUserRecord> parseUser(String agentResult) {
        return parseUserList(agentResult).stream().findFirst();
    }

    public List<RewardUserRecord> parseUserList(String agentResult) {
        if (!StringUtils.hasText(agentResult)) {
            return List.of();
        }
        Map<String, RewardUserRecord> users = new LinkedHashMap<>();
        extractJsonCandidates(agentResult).forEach(candidate -> parseStructured(candidate)
                .forEach(user -> users.putIfAbsent(uniqueKey(user), user)));
        if (!users.isEmpty()) {
            return new ArrayList<>(users.values());
        }
        parseText(agentResult).forEach(user -> users.putIfAbsent(uniqueKey(user), user));
        return new ArrayList<>(users.values());
    }

    private List<String> extractJsonCandidates(String agentResult) {
        List<String> candidates = new ArrayList<>();
        String trimmed = agentResult.trim();
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            candidates.add(trimmed);
        }
        Matcher matcher = CODE_BLOCK_PATTERN.matcher(agentResult);
        while (matcher.find()) {
            String candidate = matcher.group(1);
            if (StringUtils.hasText(candidate)) {
                candidates.add(candidate.trim());
            }
        }
        addCandidateFromBounds(candidates, agentResult, '{', '}');
        addCandidateFromBounds(candidates, agentResult, '[', ']');
        return candidates.stream().distinct().toList();
    }

    private void addCandidateFromBounds(List<String> candidates, String text, char start, char end) {
        int startIndex = text.indexOf(start);
        int endIndex = text.lastIndexOf(end);
        if (startIndex >= 0 && endIndex > startIndex) {
            String candidate = text.substring(startIndex, endIndex + 1).trim();
            if (StringUtils.hasText(candidate)) {
                candidates.add(candidate);
            }
        }
    }

    private List<RewardUserRecord> parseStructured(String candidate) {
        try {
            JsonNode root = objectMapper.readTree(candidate);
            Map<String, RewardUserRecord> users = new LinkedHashMap<>();
            collectUsers(root, users);
            return new ArrayList<>(users.values());
        }
        catch (Exception ignored) {
            return List.of();
        }
    }

    private void collectUsers(JsonNode node, Map<String, RewardUserRecord> users) {
        if (node == null || node.isNull()) {
            return;
        }
        toUser(node).ifPresent(user -> users.putIfAbsent(uniqueKey(user), user));
        if (node.isArray()) {
            node.forEach(item -> collectUsers(item, users));
            return;
        }
        if (node.isObject()) {
            node.properties().forEach(entry -> collectUsers(entry.getValue(), users));
        }
    }

    private Optional<RewardUserRecord> toUser(JsonNode node) {
        if (node == null || !node.isObject()) {
            return Optional.empty();
        }
        Long uid = firstLong(node, "uid", "userId", "user_id", "id", "用户ID", "用户id", "员工ID", "员工id");
        String uname = firstText(
                node,
                "uname",
                "userName",
                "user_name",
                "username",
                "name",
                "姓名",
                "员工姓名",
                "用户名",
                "用户名称");
        if (uid == null || !StringUtils.hasText(uname)) {
            return Optional.empty();
        }
        return Optional.of(new RewardUserRecord(uid, uname.trim()));
    }

    private Long firstLong(JsonNode node, String... fieldNames) {
        for (String fieldName : fieldNames) {
            JsonNode value = node.get(fieldName);
            if (value == null || value.isNull()) {
                continue;
            }
            if (value.isNumber()) {
                return value.longValue();
            }
            if (value.isTextual() && value.asText().matches("\\d+")) {
                return Long.parseLong(value.asText());
            }
        }
        return null;
    }

    private String firstText(JsonNode node, String... fieldNames) {
        for (String fieldName : fieldNames) {
            JsonNode value = node.get(fieldName);
            if (value == null || value.isNull()) {
                continue;
            }
            String text = value.asText();
            if (StringUtils.hasText(text)) {
                return text;
            }
        }
        return null;
    }

    private List<RewardUserRecord> parseText(String agentResult) {
        Map<String, RewardUserRecord> users = new LinkedHashMap<>();
        collectFromMatcher(users, UID_THEN_NAME_PATTERN.matcher(agentResult), true);
        collectFromMatcher(users, NAME_THEN_UID_PATTERN.matcher(agentResult), false);
        return new ArrayList<>(users.values());
    }

    private void collectFromMatcher(
            Map<String, RewardUserRecord> users,
            Matcher matcher,
            boolean uidFirst) {
        while (matcher.find()) {
            String uidText = uidFirst ? matcher.group(1) : matcher.group(2);
            String nameText = uidFirst ? matcher.group(2) : matcher.group(1);
            if (!StringUtils.hasText(uidText) || !StringUtils.hasText(nameText)) {
                continue;
            }
            RewardUserRecord user = new RewardUserRecord(Long.parseLong(uidText), nameText.trim());
            users.putIfAbsent(uniqueKey(user), user);
        }
    }

    private String uniqueKey(RewardUserRecord user) {
        return user.uid() + ":" + user.uname().toLowerCase(Locale.ROOT);
    }
}
