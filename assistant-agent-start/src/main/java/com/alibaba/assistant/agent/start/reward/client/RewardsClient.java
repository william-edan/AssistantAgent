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
package com.alibaba.assistant.agent.start.reward.client;

import com.alibaba.assistant.agent.runtime.planner.ToolExecutor;
import com.alibaba.assistant.agent.start.reward.model.RewardAddRequest;
import com.alibaba.assistant.agent.start.reward.model.RewardAddResult;
import com.alibaba.assistant.agent.start.reward.model.RewardCategoryRecord;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.context.annotation.Profile;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 奖惩接口客户端。
 *
 * <p>统一通过 {@code tool_meta} 执行，避免再走裸 HTTP。</p>
 */
@Component
@Profile("migration")
public class RewardsClient {

    private static final Logger log = LoggerFactory.getLogger(RewardsClient.class);

    private static final String DEFAULT_TENANT = "default";

    public static final String REWARD_CATEGORY_TOOL_CODE = "gougu_oa.reward_category_options";

    public static final String REWARD_CREATE_TOOL_CODE = "gougu_oa.employee_reward_punishment_create_one";

    private final ToolExecutor toolExecutor;

    private final ObjectMapper objectMapper;

    public RewardsClient(ToolExecutor toolExecutor, ObjectMapper objectMapper) {
        this.toolExecutor = toolExecutor;
        this.objectMapper = objectMapper;
    }

    public Mono<List<RewardCategoryRecord>> listCategories() {
        return listCategories(null);
    }

    public Mono<List<RewardCategoryRecord>> listCategories(@Nullable ToolContext toolContext) {
        return Mono.fromCallable(() -> execute(REWARD_CATEGORY_TOOL_CODE, Map.of(), toolContext))
                .map(this::parseCategories)
                .map(categories -> {
                    if (categories.isEmpty()) {
                        throw new IllegalStateException(REWARD_CATEGORY_TOOL_CODE + " returned no categories");
                    }
                    return categories;
                })
                .doOnSuccess(categories -> log.info("RewardsClient#listCategories - size={}", categories.size()))
                .doOnError(error -> log.warn("RewardsClient#listCategories - failed, error={}", error.getMessage()))
                .subscribeOn(Schedulers.boundedElastic());
    }

    public Mono<RewardAddResult> addReward(RewardAddRequest request) {
        return addReward(request, null);
    }

    public Mono<RewardAddResult> addReward(RewardAddRequest request, @Nullable ToolContext toolContext) {
        return Mono.fromCallable(() -> execute(REWARD_CREATE_TOOL_CODE, buildAddArguments(request), toolContext))
                .map(this::parseAddResult)
                .doOnSuccess(result -> log.info(
                        "RewardsClient#addReward - success={}, rewardId={}, message={}",
                        result.success(),
                        result.rewardId(),
                        result.message()))
                .doOnError(error -> log.warn("RewardsClient#addReward - failed, error={}", error.getMessage()))
                .subscribeOn(Schedulers.boundedElastic());
    }

    private ToolExecutor.ExecutionResult execute(
            String toolCode,
            Map<String, Object> arguments,
            @Nullable ToolContext toolContext) {
        ToolExecutor.ExecutionResult executionResult = toolExecutor.execute(
                DEFAULT_TENANT,
                toolCode,
                arguments,
                toolContext);
        if (!executionResult.success()) {
            String message = Optional.ofNullable(executionResult.errorMessage())
                    .filter(StringUtils::hasText)
                    .orElse("Dependency execution failed");
            log.warn(
                    "RewardsClient#execute - toolCode={}, success=false, error={}, payload={}",
                    toolCode,
                    message,
                    summarizePayload(executionResult.payload()));
            throw new IllegalStateException(toolCode + " execution failed: " + message);
        }
        log.info(
                "RewardsClient#execute - toolCode={}, outputFields={}, payload={}",
                toolCode,
                summarizePayload(executionResult.outputFields()),
                summarizePayload(executionResult.payload()));
        return executionResult;
    }

    private Map<String, Object> buildAddArguments(RewardAddRequest request) {
        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("rewards_cate", request.rewardsCate());
        arguments.put("types", String.valueOf(request.types()));
        arguments.put("status", "1");
        arguments.put("uname", request.uname());
        arguments.put("uid", String.valueOf(request.uid()));
        arguments.put("cost", request.cost().stripTrailingZeros().toPlainString());
        arguments.put("rewards_time", String.valueOf(request.rewardsTime()));
        arguments.put("thing", "1");
        arguments.put("remark", StringUtils.hasText(request.remark()) ? request.remark() : "");
        arguments.put("id", "0");
        return Map.copyOf(arguments);
    }

    private List<RewardCategoryRecord> parseCategories(ToolExecutor.ExecutionResult executionResult) {
        try {
            JsonNode root = objectMapper.valueToTree(executionResult.outputFields());
            JsonNode dataNode = root.path("data");
            if (dataNode.isMissingNode() || dataNode.isNull()) {
                dataNode = root;
            }
            if (dataNode.isObject()) {
                dataNode = firstContainer(dataNode, "list", "rows", "data");
            }
            if (!dataNode.isArray()) {
                return List.of();
            }
            List<RewardCategoryRecord> categories = new ArrayList<>();
            for (JsonNode item : dataNode) {
                String id = firstText(item, "id", "value", "cate_id");
                String name = firstText(item, "title", "name", "label", "cate_name");
                if (StringUtils.hasText(id) && StringUtils.hasText(name)) {
                    categories.add(new RewardCategoryRecord(id, name));
                }
            }
            return List.copyOf(categories);
        }
        catch (Exception exception) {
            throw new IllegalStateException("Reward category parse failed: " + exception.getMessage(), exception);
        }
    }

    private RewardAddResult parseAddResult(ToolExecutor.ExecutionResult executionResult) {
        try {
            JsonNode payloadNode = objectMapper.valueToTree(executionResult.payload());
            JsonNode root = objectMapper.valueToTree(executionResult.outputFields());
            JsonNode dataNode = root.path("data");
            if (dataNode.isMissingNode() || dataNode.isNull()) {
                dataNode = payloadNode.path("finalOutputs").path("data");
            }
            String message = firstText(root, "message", "msg");
            if (!StringUtils.hasText(message)) {
                message = firstText(payloadNode, "message", "msg", "error");
            }
            String rewardId = firstText(dataNode, "aid", "id", "return_id");
            if (!StringUtils.hasText(rewardId)) {
                rewardId = firstText(root, "reward_id", "aid", "id", "return_id");
            }
            if (!StringUtils.hasText(rewardId)) {
                rewardId = firstText(payloadNode.path("finalOutputs"), "rewardId", "reward_id", "aid", "id");
            }
            String rawPayload = objectMapper.writeValueAsString(executionResult.payload());
            if (!StringUtils.hasText(rewardId)) {
                throw new IllegalStateException("保存失败：接口未返回 rewardId，message=%s，payload=%s".formatted(
                        StringUtils.hasText(message) ? message : "无返回消息",
                        summarizePayload(rawPayload)));
            }
            return new RewardAddResult(
                    true,
                    StringUtils.hasText(message) ? message : "保存成功",
                    rewardId,
                    rawPayload);
        }
        catch (IllegalStateException exception) {
            throw exception;
        }
        catch (Exception exception) {
            throw new IllegalStateException("Reward add result parse failed: " + exception.getMessage(), exception);
        }
    }

    private String summarizePayload(Object payload) {
        try {
            String raw = payload instanceof String text ? text : objectMapper.writeValueAsString(payload);
            if (!StringUtils.hasText(raw)) {
                return "";
            }
            return raw.length() > 400 ? raw.substring(0, 400) + "..." : raw;
        }
        catch (Exception exception) {
            return String.valueOf(payload);
        }
    }

    private JsonNode firstContainer(JsonNode node, String... fieldNames) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return node;
        }
        for (String fieldName : fieldNames) {
            JsonNode candidate = node.path(fieldName);
            if (!candidate.isMissingNode() && !candidate.isNull()) {
                return candidate;
            }
        }
        return node;
    }

    private String firstText(JsonNode node, String... fieldNames) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        for (String fieldName : fieldNames) {
            JsonNode fieldValue = node.path(fieldName);
            if (!fieldValue.isMissingNode() && !fieldValue.isNull()) {
                String text = fieldValue.asText();
                if (StringUtils.hasText(text)) {
                    return text;
                }
            }
        }
        return null;
    }
}
