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
package com.alibaba.assistant.agent.start.reward.service;

import com.alibaba.assistant.agent.runtime.planner.ToolExecutor;
import com.alibaba.assistant.agent.start.reward.model.RewardUserRecord;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.context.annotation.Profile;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Queries employee records through reward-related internal tool meta.
 */
@Service
@Profile("migration")
public class RewardEmployeeHttpService {

    private static final Logger log = LoggerFactory.getLogger(RewardEmployeeHttpService.class);

    private static final String DEFAULT_TENANT = "default";

    public static final String REWARD_EMPLOYEE_QUERY_TOOL_CODE = "gougu_oa.reward_employee_lookup";

    private static final List<String> USER_ID_KEYS = List.of(
            "id",
            "uid",
            "userId",
            "user_id",
            "employeeId",
            "employee_id",
            "用户ID",
            "用户id",
            "员工ID",
            "员工id");

    private static final List<String> USER_NAME_KEYS = List.of(
            "name",
            "realname",
            "real_name",
            "trueName",
            "true_name",
            "userName",
            "user_name",
            "username",
            "nickName",
            "nick_name",
            "nickname",
            "employeeName",
            "employee_name",
            "uname",
            "姓名",
            "员工姓名",
            "用户名",
            "真实姓名");

    private final ToolExecutor toolExecutor;

    private final ObjectMapper objectMapper;

    public RewardEmployeeHttpService(ToolExecutor toolExecutor, ObjectMapper objectMapper) {
        this.toolExecutor = toolExecutor;
        this.objectMapper = objectMapper;
    }

    public Mono<Optional<RewardUserRecord>> findUser(String keyword) {
        return findUser(keyword, null);
    }

    public Mono<Optional<RewardUserRecord>> findUser(String keyword, @Nullable ToolContext toolContext) {
        if (!StringUtils.hasText(keyword)) {
            return Mono.just(Optional.empty());
        }
        String normalizedKeyword = keyword.trim();
        log.info("RewardEmployeeHttpService#findUser - keyword={}", normalizedKeyword);
        return Mono.fromCallable(() -> execute(normalizedKeyword, toolContext))
                .map(result -> parseUser(result, normalizedKeyword))
                .doOnSuccess(user -> log.info(
                        "RewardEmployeeHttpService#findUser - matched={}, keyword={}",
                        user.isPresent(),
                        normalizedKeyword))
                .doOnError(error -> log.warn(
                        "RewardEmployeeHttpService#findUser - failed, keyword={}, error={}",
                        normalizedKeyword,
                        error.getMessage()))
                .subscribeOn(Schedulers.boundedElastic());
    }

    private ToolExecutor.ExecutionResult execute(String keyword, @Nullable ToolContext toolContext) {
        ToolExecutor.ExecutionResult executionResult = toolExecutor.execute(
                DEFAULT_TENANT,
                REWARD_EMPLOYEE_QUERY_TOOL_CODE,
                Map.of(),
                toolContext);
        if (!executionResult.success()) {
            String message = StringUtils.hasText(executionResult.errorMessage())
                    ? executionResult.errorMessage()
                    : "employee lookup failed";
            throw new IllegalStateException(REWARD_EMPLOYEE_QUERY_TOOL_CODE + " execution failed: " + message);
        }
        return executionResult;
    }

    private Optional<RewardUserRecord> parseUser(ToolExecutor.ExecutionResult executionResult, String keyword) {
        try {
            JsonNode outputRoot = objectMapper.valueToTree(executionResult.outputFields());
            JsonNode dataNode = outputRoot.path("data");
            if (dataNode.isMissingNode() || dataNode.isNull()) {
                dataNode = objectMapper.valueToTree(executionResult.payload()).path("finalOutputs").path("data");
            }
            Map<String, RewardUserRecord> users = new LinkedHashMap<>();
            collectUsers(dataNode, users);
            return users.values().stream()
                    .sorted((left, right) -> Integer.compare(score(keyword, right), score(keyword, left)))
                    .findFirst()
                    .filter(user -> score(keyword, user) > 0);
        }
        catch (Exception exception) {
            throw new IllegalStateException("员工查询结果解析失败", exception);
        }
    }

    private void collectUsers(JsonNode node, Map<String, RewardUserRecord> users) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return;
        }
        if (node.isObject()) {
            toUser(node).ifPresent(user -> users.putIfAbsent(user.uid() + ":" + user.uname(), user));
            node.fields().forEachRemaining(entry -> collectUsers(entry.getValue(), users));
            return;
        }
        if (node.isArray()) {
            List<JsonNode> items = new ArrayList<>();
            node.elements().forEachRemaining(items::add);
            items.forEach(item -> collectUsers(item, users));
        }
    }

    private Optional<RewardUserRecord> toUser(JsonNode node) {
        Long uid = firstLong(node, USER_ID_KEYS);
        String uname = firstText(node, USER_NAME_KEYS);
        if (uid == null || !StringUtils.hasText(uname)) {
            return Optional.empty();
        }
        return Optional.of(new RewardUserRecord(uid, uname.trim()));
    }

    private Long firstLong(JsonNode node, List<String> aliases) {
        if (node == null || !node.isObject()) {
            return null;
        }
        for (String alias : aliases) {
            JsonNode value = findField(node, alias);
            if (value == null || value.isNull()) {
                continue;
            }
            if (value.isNumber()) {
                return value.longValue();
            }
            String text = asText(value);
            if (!StringUtils.hasText(text)) {
                continue;
            }
            try {
                return Long.parseLong(text);
            }
            catch (NumberFormatException ignored) {
                // Ignore fields that are not numeric ids.
            }
        }
        return null;
    }

    private String firstText(JsonNode node, List<String> aliases) {
        if (node == null || !node.isObject()) {
            return null;
        }
        for (String alias : aliases) {
            JsonNode value = findField(node, alias);
            String text = asText(value);
            if (StringUtils.hasText(text)) {
                return text;
            }
        }
        return null;
    }

    private JsonNode findField(JsonNode node, String alias) {
        if (node == null || !node.isObject()) {
            return null;
        }
        String normalizedAlias = normalizeKey(alias);
        var fields = node.fields();
        while (fields.hasNext()) {
            var entry = fields.next();
            if (normalizedAlias.equals(normalizeKey(entry.getKey()))) {
                return entry.getValue();
            }
        }
        return null;
    }

    private int score(String keyword, RewardUserRecord user) {
        if (user == null || !StringUtils.hasText(user.uname())) {
            return 0;
        }
        String normalizedKeyword = normalizeText(keyword);
        String normalizedName = normalizeText(user.uname());
        if (!StringUtils.hasText(normalizedKeyword)) {
            return 1;
        }
        if (normalizedName.equals(normalizedKeyword)) {
            return 4;
        }
        if (normalizedName.contains(normalizedKeyword) || normalizedKeyword.contains(normalizedName)) {
            return 3;
        }
        return 1;
    }

    private String normalizeKey(String key) {
        if (!StringUtils.hasText(key)) {
            return "";
        }
        return key.replaceAll("[\\s_\\-]", "").toLowerCase(Locale.ROOT);
    }

    private String normalizeText(String text) {
        return StringUtils.hasText(text) ? text.trim().toLowerCase(Locale.ROOT) : "";
    }

    private String asText(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        String text = node.isTextual() ? node.textValue() : node.asText();
        return StringUtils.hasText(text) ? text.trim() : null;
    }
}
